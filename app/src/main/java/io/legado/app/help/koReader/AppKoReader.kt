package io.legado.app.help.koReader

import android.os.SystemClock
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.SSLHelper
import io.legado.app.help.http.newCallResponse
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import splitties.init.appCtx
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** KOReader KOSync client compatible with the API shipped in KOReader v2026.07.2. */
object AppKoReader {
    const val DEFAULT_SERVER = KoReaderDefaults.SERVER_URL
    private const val PERCENTAGE_EPSILON = 0.0001
    private const val API_CALL_DEBOUNCE_MILLIS = 25_000L
    private const val ACCEPT = "application/vnd.koreader.v1+json"
    private val pageTurnTimes = ConcurrentHashMap<String, Long>()
    private val pageTurnTrackingBooks = Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )
    private val queueLock = Any()
    @Volatile
    private var lastPushElapsedMillis = 0L
    @Volatile
    private var lastPullElapsedMillis = 0L
    private val JSON = "application/json; charset=UTF-8".toMediaType()
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
            .hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
            .connectionSpecs(
                listOf(
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT,
                )
            )
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    data class RemoteProgress(
        val document: String? = null,
        val progress: String? = null,
        val percentage: Double? = null,
        val device: String? = null,
        val device_id: String? = null,
        val timestamp: Double? = null,
    )

    data class AutoSync(
        val decision: KoReaderAutoSyncDecision,
        val remote: RemoteProgress,
        val progress: BookProgress,
        val localPercentage: Double,
        val remotePercentage: Double,
    )

    val isConfigured: Boolean
        get() = AppConfig.koReaderSyncEnabled && hasCredentials

    val hasCredentials: Boolean
        get() =
            AppConfig.koReaderServer.isNotBlank() &&
            AppConfig.koReaderUsername.isNotBlank() &&
            AppConfig.koReaderPassword.isNotBlank()

    val normalizedServerUrl: String
        get() = KoReaderProgressMapper.normalizeServerUrl(
            AppConfig.koReaderServer,
            DEFAULT_SERVER,
        )

    suspend fun verifyAuthentication() {
        checkCredentials()
        request("/users/auth", method = "GET")
            ?: throw IOException("KOReader 服务未返回账号验证结果")
    }

    suspend fun getBookProgress(book: Book): RemoteProgress? {
        checkCredentials()
        if (!book.isLocal) return null
        val digest = documentDigest(book)
        val json = request("/syncs/progress/$digest", method = "GET", allowNotFound = true)
            ?: return null
        val body = JSONObject(json)
        return RemoteProgress(
            document = body.stringOrNull("document"),
            progress = body.stringOrNull("progress"),
            percentage = body.doubleOrNull("percentage")
                ?.let(KoReaderProgressMapper::normalizePercentage),
            device = body.stringOrNull("device"),
            device_id = body.stringOrNull("device_id"),
            timestamp = body.doubleOrNull("timestamp"),
        ).takeIf { it.percentage != null }
    }

    suspend fun uploadBookProgress(
        book: Book,
        interactive: Boolean = false,
    ): RemoteProgress? {
        checkCredentials()
        if (!book.isLocal) return null
        val now = SystemClock.elapsedRealtime()
        if (!shouldCall(lastPushElapsedMillis, now, interactive)) return null
        var progress: KoReaderQueuedProgress? = null
        var queued = false
        try {
            progress = buildLocalProgress(book)
            if (!NetworkUtils.isAvailable()) {
                enqueueProgress(progress)
                queued = true
                throw IOException("网络不可用")
            }
            sendQueuedProgress(progress)
            return RemoteProgress(
                document = progress.document,
                progress = progress.progress,
                percentage = progress.percentage,
                device = progress.device,
                device_id = progress.deviceId,
                timestamp = System.currentTimeMillis() / 1000.0,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (!queued && progress != null &&
                error !is KoReaderAuthenticationException &&
                error !is KoReaderConfigurationException
            ) {
                enqueueProgress(progress)
            }
            throw error
        } finally {
            lastPushElapsedMillis = now
        }
    }

    suspend fun drainUploadQueue(): Int {
        if (!hasCredentials) return 0
        val account = accountKey()
        synchronized(queueLock) {
            val retained = KoReaderSyncQueue.retainAccount(readQueue(), account)
            writeQueue(retained)
        }
        var sent = 0
        while (true) {
            val item = synchronized(queueLock) {
                KoReaderSyncQueue.firstForAccount(readQueue(), account)
            } ?: break
            try {
                sendQueuedProgress(item)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                break
            }
            synchronized(queueLock) {
                writeQueue(KoReaderSyncQueue.remove(readQueue(), item.id))
            }
            sent++
        }
        return sent
    }

    fun markPageTurn(book: Book, timestampMillis: Long = System.currentTimeMillis()) {
        if (book.bookUrl !in pageTurnTrackingBooks) return
        if (pageTurnTimes.size > 64) pageTurnTimes.clear()
        pageTurnTimes[book.bookUrl] = timestampMillis / 1000
    }

    fun resetBookSession(book: Book) {
        pageTurnTrackingBooks.remove(book.bookUrl)
        pageTurnTimes.remove(book.bookUrl)
    }

    fun beginBookSession(book: Book) {
        pageTurnTimes.remove(book.bookUrl)
        pageTurnTrackingBooks.add(book.bookUrl)
    }

    fun clearPageTurn(book: Book) {
        pageTurnTrackingBooks.remove(book.bookUrl)
        pageTurnTimes.remove(book.bookUrl)
    }

    private suspend fun buildLocalProgress(book: Book): KoReaderQueuedProgress {
        val percentage = currentPercentage(book, currentPageFraction(book))
        val document = documentDigest(book)
        val progress = currentProgressToken(book)
        val device = AppConfig.koReaderDeviceName
        val sendMetadata = AppConfig.koReaderSendMetadata
        return KoReaderQueuedProgress(
            account = accountKey(),
            document = document,
            progress = progress,
            percentage = percentage,
            device = device,
            deviceId = AppConst.androidId,
            filename = book.originName.ifBlank { book.name }.takeIf { sendMetadata },
            title = book.name.takeIf { sendMetadata },
            authors = book.author.takeIf { sendMetadata },
        )
    }

    private suspend fun sendQueuedProgress(progress: KoReaderQueuedProgress) {
        val payload = JSONObject()
            .put("document", progress.document)
            .put("progress", progress.progress)
            .put("percentage", progress.percentage)
            .put("device", progress.device)
            .put("device_id", progress.deviceId)
        if (progress.filename != null || progress.title != null || progress.authors != null) {
            payload.put(
                "metadata",
                JSONObject()
                    .put("filename", progress.filename ?: "")
                    .put("title", progress.title ?: "")
                    .put("authors", progress.authors ?: ""),
            )
        }
        request("/syncs/progress", method = "PUT", body = payload.toString())
            ?: throw IOException("KOReader 服务未确认进度上传")
    }

    suspend fun getAutoSync(
        book: Book,
        interactive: Boolean = false,
    ): AutoSync? {
        if (!isConfigured) return null
        val now = SystemClock.elapsedRealtime()
        if (!shouldCall(lastPullElapsedMillis, now, interactive)) {
            pageTurnTrackingBooks.add(book.bookUrl)
            return null
        }
        try {
            val remote = getBookProgress(book) ?: return null
            val device = AppConfig.koReaderDeviceName
            if (remote.device == device && remote.device_id == AppConst.androidId) return null
            val remotePercentage = remote.percentage ?: return null
            val localPercentage = currentPercentage(book, currentPageFraction(book))
            val localProgressToken = currentProgressToken(book)
            if (remote.progress == localProgressToken) return null
            val decision = KoReaderSyncPolicy.decide(
                localPercentage = localPercentage,
                localTimestampSeconds = pageTurnTimes[book.bookUrl] ?: 0L,
                remotePercentage = remotePercentage,
                remoteTimestampSeconds = remote.timestamp,
                forwardStrategy = AppConfig.koReaderSyncForward,
                backwardStrategy = AppConfig.koReaderSyncBackward,
                percentageEpsilon = PERCENTAGE_EPSILON,
            ) ?: return null
            val progress = toBookProgress(book, remote) ?: return null
            return AutoSync(
                decision = decision,
                remote = remote,
                progress = progress,
                localPercentage = localPercentage,
                remotePercentage = remotePercentage,
            )
        } finally {
            lastPullElapsedMillis = now
            pageTurnTrackingBooks.add(book.bookUrl)
        }
    }

    suspend fun toBookProgress(book: Book, remote: RemoteProgress): BookProgress? = withContext(Dispatchers.IO) {
        val percentage = remote.percentage
            ?.let(KoReaderProgressMapper::normalizePercentage)
            ?: return@withContext null
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapters.isEmpty()) return@withContext null
        val lengths = EpubFile.getChapterLengths(book, chapters)
            ?: chapters.map { KoReaderProgressMapper.parseLeadingInt(it.wordCount) }
        val mapped = mapEpubXPointer(book, chapters, remote.progress, lengths)
            ?: KoReaderProgressMapper.mapPercentage(percentage, lengths)
            ?: return@withContext null
        val chapter = chapters[mapped.first]
        BookProgress(
            name = book.name,
            author = book.author,
            durChapterIndex = mapped.first,
            durChapterPos = mapped.second,
            durChapterTime = remote.timestamp?.times(1000)?.toLong() ?: System.currentTimeMillis(),
            durChapterTitle = chapter.title,
        )
    }

    suspend fun isNewer(book: Book, remote: RemoteProgress): Boolean = withContext(Dispatchers.IO) {
        val remotePercentage = remote.percentage
            ?.let(KoReaderProgressMapper::normalizePercentage)
            ?: return@withContext false
        KoReaderProgressMapper.isRemoteNewer(
            localPercentage = currentPercentage(book, currentPageFraction(book)),
            localTimestampSeconds = pageTurnTimes[book.bookUrl] ?: 0L,
            remotePercentage = remotePercentage,
            remoteTimestampSeconds = remote.timestamp,
            percentageEpsilon = PERCENTAGE_EPSILON,
        )
    }

    suspend fun documentDigest(book: Book): String {
        if (AppConfig.koReaderMatchingMode == AppConfig.KO_READER_MATCH_FILENAME) {
            return MD5Utils.md5Encode(book.originName.ifBlank { book.name })
        }
        LocalBook.getBookInputStream(book).use {
            return KoReaderProgressMapper.partialMd5(it)
        }
    }

    private fun currentPageFraction(book: Book): Double? =
        ReadBook.takeIf { it.book?.bookUrl == book.bookUrl }?.koReaderPageFraction

    private suspend fun currentPercentage(
        book: Book,
        chapterFractionOverride: Double? = null,
    ): Double {
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        val lengths = withContext(Dispatchers.IO) {
            EpubFile.getChapterLengths(book, chapters)
        } ?: chapters.map { KoReaderProgressMapper.parseLeadingInt(it.wordCount) }
        return KoReaderProgressMapper.percentage(
            chapterIndex = book.durChapterIndex,
            chapterPosition = book.durChapterPos,
            chapterLengths = lengths,
            chapterFractionOverride = chapterFractionOverride,
        )
    }

    private fun currentProgressToken(book: Book): String {
        if (book.originName.endsWith(".pdf", ignoreCase = true)) {
            return (book.durChapterIndex + 1).toString()
        }
        if (book.originName.endsWith(".epub", ignoreCase = true)) {
            appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)?.let { chapter ->
                runCatching {
                    EpubFile.getChapterProgressToken(book, chapter, book.durChapterPos)
                }.getOrNull()
            }?.let { return it }
            val chapterUrl = appDb.bookChapterDao
                .getChapter(book.bookUrl, book.durChapterIndex)
                ?.url
                ?.substringBefore('#')
            val spineHrefs = runCatching { EpubFile.getSpineHrefs(book) }
                .getOrDefault(emptyList())
            val fragmentIndex = spineHrefs.indexOfFirst {
                normalizeHref(it) == normalizeHref(chapterUrl)
            }.takeIf { it >= 0 }?.plus(1) ?: 1
            return "/body/DocFragment[$fragmentIndex]"
        }
        return "/body/DocFragment[1]"
    }

    private suspend fun request(
        path: String,
        method: String,
        body: String? = null,
        allowNotFound: Boolean = false,
    ): String? {
        val server = KoReaderProgressMapper.normalizeServerUrl(AppConfig.koReaderServer, DEFAULT_SERVER)
        if (!server.startsWith("http://", true) && !server.startsWith("https://", true)) {
            throw KoReaderConfigurationException("KOSync 服务器地址仅支持 http:// 或 https://")
        }
        val response = try {
            httpClient.newCallResponse {
                url(server + path)
                header("Accept", ACCEPT)
                header("x-auth-user", AppConfig.koReaderUsername)
                header("x-auth-key", MD5Utils.md5Encode(AppConfig.koReaderPassword))
                if (method == "PUT") {
                    put(requireNotNull(body).toRequestBody(JSON))
                } else {
                    get()
                }
            }
        } catch (error: IllegalArgumentException) {
            throw KoReaderConfigurationException("KOSync 服务器地址格式无效：$server", error)
        }
        response.use { result ->
            val responseBody = result.body.string()
            if (result.code == 401) throw KoReaderAuthenticationException("KOReader 用户名或密码错误")
            if (allowNotFound && result.code == 404) return null
            if (!result.isSuccessful) {
                throw KoReaderHttpException(
                    statusCode = result.code,
                    message = "KOReader 服务返回 HTTP ${result.code}: $responseBody",
                )
            }
            return responseBody
        }
    }

    private fun mapEpubXPointer(
        book: Book,
        chapters: List<io.legado.app.data.entities.BookChapter>,
        progressPointer: String?,
        chapterLengths: List<Int>,
    ): Pair<Int, Int>? {
        if (!book.originName.endsWith(".epub", ignoreCase = true)) return null
        val spineHrefs = runCatching { EpubFile.getSpineHrefs(book) }.getOrDefault(emptyList())
        val pointer = KoReaderProgressMapper.parseEpubXPointer(progressPointer)
        if (pointer == null) {
            val fragmentOrdinal = KoReaderProgressMapper.parseEpubFragmentNumber(progressPointer)
                ?: return null
            val shallowHref = spineHrefs.getOrNull(fragmentOrdinal - 1) ?: return null
            val chapter = chapters.withIndex().singleOrNull {
                normalizeHref(it.value.url.substringBefore('#')) == normalizeHref(shallowHref)
            } ?: return null
            return chapter.index to 0
        }
        val spineHref = spineHrefs.getOrNull(pointer.fragmentOrdinal - 1) ?: return null
        val location = runCatching { EpubFile.getSpineTextLocation(book, pointer) }
            .getOrNull() ?: return null
        val sameResource = chapters.withIndex().filter {
            normalizeHref(it.value.url.substringBefore('#')) == normalizeHref(spineHref)
        }
        if (sameResource.isEmpty()) return null
        val windows = sameResource.map { (index, chapter) ->
            val start = chapter.startFragmentId
                ?.let(location.anchorFractions::get)
                ?: 0.0
            index to start.coerceIn(0.0, 1.0)
        }.sortedBy { it.second }
        val distinctStarts = windows.distinctBy { it.second }
        val selectedWindow = if (windows.size == 1) {
            windows.first()
        } else {
            if (distinctStarts.size < windows.size) return null
            distinctStarts.lastOrNull { it.second <= location.positionFraction + PERCENTAGE_EPSILON }
                ?: distinctStarts.first()
        }
        val selectedPosition = distinctStarts.indexOf(selectedWindow).coerceAtLeast(0)
        val startFraction = selectedWindow.second
        val endFraction = distinctStarts.getOrNull(selectedPosition + 1)?.second ?: 1.0
        val span = (endFraction - startFraction).takeIf { it > PERCENTAGE_EPSILON } ?: 1.0
        val chapterFraction = ((location.positionFraction - startFraction) / span).coerceIn(0.0, 1.0)
        val chapterLength = chapterLengths.getOrNull(selectedWindow.first)
            ?.takeIf { it > 0 }
            ?: 0
        return selectedWindow.first to (chapterLength * chapterFraction).toInt().coerceAtLeast(0)
    }

    private fun checkCredentials() {
        if (AppConfig.koReaderServer.isBlank() ||
            AppConfig.koReaderUsername.isBlank() ||
            AppConfig.koReaderPassword.isBlank()
        ) {
            throw KoReaderConfigurationException("请填写 KOReader 同步服务器、用户名和密码")
        }
    }

    private fun normalizeHref(href: String?): String = href.orEmpty().trimStart('/').substringBefore('#')

    private fun shouldCall(lastCallElapsedMillis: Long, nowElapsedMillis: Long, interactive: Boolean): Boolean =
        interactive || lastCallElapsedMillis == 0L ||
            nowElapsedMillis - lastCallElapsedMillis > API_CALL_DEBOUNCE_MILLIS

    private fun accountKey(): String = MD5Utils.md5Encode(
        "${normalizedServerUrl}\n${AppConfig.koReaderUsername}"
    )

    private fun readQueue(): String? = appCtx.getPrefString(PreferKey.koReaderUploadQueue)

    private fun writeQueue(raw: String) {
        appCtx.putPrefString(PreferKey.koReaderUploadQueue, raw)
    }

    private fun enqueueProgress(progress: KoReaderQueuedProgress) {
        synchronized(queueLock) {
            writeQueue(KoReaderSyncQueue.enqueue(readQueue(), progress))
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    class KoReaderAuthenticationException(message: String) : IOException(message)
    class KoReaderConfigurationException(message: String, cause: Throwable? = null) : IOException(message, cause)
    class KoReaderHttpException(
        val statusCode: Int,
        message: String,
    ) : IOException(message)
}
