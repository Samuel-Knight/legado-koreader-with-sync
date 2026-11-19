package io.legado.app.help.koReader

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class KoReaderQueuedProgress(
    val id: String = UUID.randomUUID().toString(),
    val account: String,
    val document: String,
    val progress: String,
    val percentage: Double,
    val device: String,
    val deviceId: String,
    val filename: String? = null,
    val title: String? = null,
    val authors: String? = null,
    val queuedAtSeconds: Long = 0,
)

/** Persisted retry queue following KOReader's KOSyncQueue behavior. */
object KoReaderSyncQueue {
    const val MAX_AGE_SECONDS = 28 * 24 * 60 * 60L
    const val MAX_ENTRIES = 200

    private val gson = Gson()
    private val entryListType = object : TypeToken<List<KoReaderQueuedProgress>>() {}.type

    fun decode(raw: String?): List<KoReaderQueuedProgress> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<KoReaderQueuedProgress>>(raw, entryListType).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun encode(entries: List<KoReaderQueuedProgress>): String = gson.toJson(entries)

    fun enqueue(
        raw: String?,
        item: KoReaderQueuedProgress,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): String {
        val queued = item.copy(queuedAtSeconds = nowSeconds)
        val today = nowSeconds / 86_400
        val entries = decode(raw)
            .filter { nowSeconds - it.queuedAtSeconds < MAX_AGE_SECONDS }
            .filterNot {
                it.account == queued.account &&
                    it.document == queued.document &&
                    it.queuedAtSeconds / 86_400 == today
            }
            .toMutableList()
            .apply { add(queued) }
        val capped = entries.takeLast(MAX_ENTRIES)
        return encode(capped)
    }

    fun firstForAccount(
        raw: String?,
        account: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): KoReaderQueuedProgress? = decode(raw).firstOrNull {
        it.account == account && nowSeconds - it.queuedAtSeconds < MAX_AGE_SECONDS
    }

    fun retainAccount(
        raw: String?,
        account: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): String = encode(
        decode(raw).filter {
            it.account == account && nowSeconds - it.queuedAtSeconds < MAX_AGE_SECONDS
        }
    )

    fun remove(raw: String?, id: String): String =
        encode(decode(raw).filterNot { it.id == id })
}
