package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.koReader.KoReaderProgressMapper
import io.legado.app.utils.FileUtils
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.encodeURI
import io.legado.app.utils.isXml
import io.legado.app.utils.printOnDebug
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.domain.TOCReference
import me.ag2s.epublib.epub.EpubReader
import me.ag2s.epublib.util.zip.AndroidZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.Elements
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset

class EpubFile(var book: Book) : AutoCloseable {

    companion object : BaseLocalBookParse {
        data class SpineTextLocation(
            val positionFraction: Double,
            val anchorFractions: Map<String, Double>,
        )

        private val cache = CloseableCache<EpubFile>()

        @Synchronized
        private fun getEFile(book: Book): EpubFile {
            return cache.getOrCreate(
                matches = { it.openedBookUrl == book.bookUrl },
                create = { EpubFile(book) },
            ).also { it.book = book }
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getEFile(book).getChapterList()
        }

        @Synchronized
        fun getToc(book: Book): List<EpubTocNode> =
            epubTocNodes(getEFile(book).epubBook?.tableOfContents?.tocReferences.orEmpty())

        @Synchronized
        fun getSpineHrefs(book: Book): List<String> =
            getEFile(book).epubBook?.spine?.spineReferences?.map { it.resource.href }.orEmpty()

        @Synchronized
        fun getSpineTextLocation(
            book: Book,
            pointer: KoReaderProgressMapper.EpubXPointer,
        ): SpineTextLocation? = getEFile(book).getSpineTextLocation(pointer)

        @Synchronized
        fun getChapterLengths(book: Book, chapters: List<BookChapter>): List<Int>? =
            getEFile(book).getChapterLengths(chapters)

        @Synchronized
        fun getChapterProgressToken(
            book: Book,
            chapter: BookChapter,
            chapterPosition: Int,
        ): String? = getEFile(book).getChapterProgressToken(chapter, chapterPosition)

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getEFile(book).getContent(chapter)
        }

        @Synchronized
        fun repairCachedContent(book: Book, chapter: BookChapter, content: String): String =
            getEFile(book).repairCachedContent(chapter, content)

        @Synchronized
        override fun getImage(
            book: Book,
            href: String
        ): InputStream? {
            return getEFile(book).getImage(href)
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            return getEFile(book).upBookInfo()
        }

        @Synchronized
        fun clear() {
            cache.clear()
        }

        @Synchronized
        fun clear(bookUrl: String) {
            cache.clearIf { it.openedBookUrl == bookUrl }
        }
    }

    private val openedBookUrl = book.bookUrl

    private var mCharset: Charset = Charset.defaultCharset()

    /**
     *持有引用，避免被回收
     */
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var zipFile: AndroidZipFile? = null
    private var readingBoundaries: Map<String, BookChapter>? = null
    private var chapterLengthsCacheKey: List<Pair<String, String?>>? = null
    private var chapterLengthsCache: List<Int>? = null
    private val checkedContentCaches = HashSet<String>()
    private var epubBook: EpubBook? = null
        get() {
            if (field == null || fileDescriptor == null) {
                field = readEpub()
            }
            return field
        }
    private var epubBookContents: List<Resource>? = null
        get() {
            if (field == null || fileDescriptor == null) {
                field = epubBook?.contents
            }
            return field
        }

    init {
        upBookCover(true)
    }

    /**
     * 重写epub文件解析代码，直接读出压缩包文件生成Resources给epublib，这样的好处是可以逐一修改某些文件的格式错误
     */
    private fun readEpub(): EpubBook? {
        return kotlin.runCatching {
            close()
            BookHelp.getBookPFD(book)?.let { descriptor ->
                val openedZip = try {
                    AndroidZipFile(descriptor, book.originName)
                } catch (error: Throwable) {
                    descriptor.close()
                    throw error
                }
                fileDescriptor = descriptor
                zipFile = openedZip
                EpubReader().readEpubLazy(openedZip, "utf-8")
            }
        }.onFailure {
            close()
            AppLog.put("读取Epub文件失败\n${it.localizedMessage}", it)
            it.printOnDebug()
        }.getOrThrow()
    }

    private fun readingBoundary(chapter: BookChapter): BookChapter {
        if (readingBoundaries == null) getChapterList()
        return readingBoundaries?.get(chapter.url) ?: chapter
    }

    private fun getSpineTextLocation(
        pointer: KoReaderProgressMapper.EpubXPointer,
    ): Companion.SpineTextLocation? {
        val resource = epubBook?.spine?.spineReferences
            ?.getOrNull(pointer.fragmentOrdinal - 1)?.resource ?: return null
        val body = Jsoup.parse(String(resource.data, mCharset)).body()
        body.select("script, style, title").remove()
        val totalLength = textLength(body).coerceAtLeast(1)
        val anchorFractions = linkedMapOf<String, Double>()
        collectAnchors(body, 0, anchorFractions, totalLength)
        val targetLength = findTextTarget(body, pointer.nodePath)
            ?.let { (start, textNode) ->
                start + pointer.characterOffset.coerceIn(0, textNode.text().length)
            } ?: return null
        return Companion.SpineTextLocation(
            positionFraction = (targetLength.toDouble() / totalLength).coerceIn(0.0, 1.0),
            anchorFractions = anchorFractions,
        )
    }

    private fun getChapterLengths(chapters: List<BookChapter>): List<Int>? {
        if (chapters.isEmpty()) return null
        val cacheKey = chapters.map { it.url to it.startFragmentId }
        if (chapterLengthsCacheKey == cacheKey) return chapterLengthsCache
        val spine = epubBook?.spine?.spineReferences ?: return null
        if (spine.isEmpty()) return null
        val resourceIndexes = spine.mapIndexed { index, reference ->
            normalizeHref(reference.resource.href) to index
        }.toMap()
        val resourceLengths = IntArray(spine.size)
        val resourceAnchors = arrayOfNulls<Map<String, Double>>(spine.size)
        spine.forEachIndexed { index, reference ->
            val body = Jsoup.parse(String(reference.resource.data, mCharset)).body()
            body.select("script, style, title").remove()
            val length = textLength(body)
            val anchors = linkedMapOf<String, Double>()
            if (length > 0) collectAnchors(body, 0, anchors, length)
            resourceLengths[index] = length
            resourceAnchors[index] = anchors
        }
        val resourceStarts = LongArray(spine.size)
        for (index in 1 until spine.size) {
            resourceStarts[index] = resourceStarts[index - 1] + resourceLengths[index - 1]
        }
        val starts = chapters.map { chapter ->
            val resourceIndex = resourceIndexes[normalizeHref(chapter.url)] ?: return null
            val fragmentFraction = chapter.startFragmentId
                ?.takeIf { it.isNotBlank() }
                ?.let { resourceAnchors[resourceIndex]?.get(it) }
                ?: 0.0
            resourceStarts[resourceIndex] +
                (resourceLengths[resourceIndex] * fragmentFraction).toLong()
        }
        if (starts.zipWithNext().any { (left, right) -> right < left }) return null
        val totalLength = resourceStarts[resourceStarts.lastIndex] +
            resourceLengths[resourceLengths.lastIndex]
        val lengths = starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: totalLength
            (end - start).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
        if (lengths.sumOf { it.toLong() } == 0L) return null
        chapterLengthsCacheKey = cacheKey
        chapterLengthsCache = lengths
        return lengths
    }

    private fun getChapterProgressToken(
        chapter: BookChapter,
        chapterPosition: Int,
    ): String? {
        val spine = epubBook?.spine?.spineReferences ?: return null
        val href = normalizeHref(chapter.url)
        val fragmentOrdinal = spine.indexOfFirst {
            normalizeHref(it.resource.href) == href
        }.takeIf { it >= 0 }?.plus(1) ?: return null
        val anchorId = chapter.startFragmentId
            ?.takeIf { it.isNotBlank() }
            ?: chapter.url.substringAfter('#', "").takeIf { it.isNotBlank() }
        val body = Jsoup.parse(
            String(spine[fragmentOrdinal - 1].resource.data, mCharset)
        ).body()
        val anchor = anchorId?.let { id ->
            body.getElementById(id)
                ?: runCatching {
                    body.getElementById(URLDecoder.decode(id, "UTF-8"))
                }.getOrNull()
                ?: findChapterTitleAnchor(body, chapter.title)
        }
        if (anchorId != null && anchor == null) return null
        if (anchor == null && !hasSingleChapterForResource(href)) {
            return "/body/DocFragment[$fragmentOrdinal]"
        }
        val anchorOffset = anchor?.let { element ->
            firstTextNode(element)?.let { textStart(body, it) }
        } ?: 0
        val targetOffset = anchorOffset + chapterPosition.coerceAtLeast(0)
        findTextNodeAt(body, targetOffset)?.let { (textNode, characterOffset) ->
            val parent = textNode.parent() ?: return@let
            val nodePath = elementPath(body, parent) ?: return@let
            return KoReaderProgressMapper.formatEpubTextXPointer(
                fragmentOrdinal = fragmentOrdinal,
                nodePath = "$nodePath/text()",
                characterOffset = characterOffset,
            )
        }
        if (anchor == null) return "/body/DocFragment[$fragmentOrdinal]"
        val nodePath = elementPath(body, anchor) ?: return null
        return KoReaderProgressMapper.formatEpubElementXPointer(fragmentOrdinal, nodePath)
    }

    private fun hasSingleChapterForResource(href: String): Boolean {
        if (readingBoundaries == null) getChapterList()
        return readingBoundaries?.values?.count { normalizeHref(it.url) == href } == 1
    }

    private fun findTextNodeAt(body: Element, targetOffset: Int): Pair<TextNode, Int>? {
        var offset = 0
        var result: Pair<TextNode, Int>? = null
        fun visit(node: Node) {
            if (result != null) return
            if (node is TextNode) {
                val textLength = node.text().length
                if (textLength > 0 && targetOffset <= offset + textLength) {
                    result = node to (targetOffset - offset).coerceIn(0, textLength)
                } else {
                    offset += textLength
                }
            } else {
                node.childNodes().forEach(::visit)
            }
        }
        visit(body)
        return result
    }

    private fun elementPath(body: Element, target: Element): String? {
        val segments = mutableListOf<String>()
        var current: Element = target
        while (current !== body) {
            val parent = current.parent() ?: return null
            val tagName = current.tagName()
            val index = parent.children()
                .filter { it.tagName().equals(tagName, true) }
                .indexOfFirst { it === current }
                .takeIf { it >= 0 }?.plus(1) ?: return null
            segments.add(0, "$tagName[$index]")
            current = parent
        }
        if (segments.isEmpty()) return null
        return "body/${segments.joinToString("/")}"
    }

    private fun findChapterTitleAnchor(body: Element, title: String): Element? {
        val expected = normalizeChapterTitle(title)
        if (expected.length < 2) return null
        return body.getAllElements().asSequence()
            .filter { it !== body }
            .mapNotNull { element ->
                val text = normalizeChapterTitle(element.text())
                element.takeIf { text.isNotEmpty() && text.contains(expected) }
            }
            .minByOrNull { normalizeChapterTitle(it.text()).length }
    }

    private fun normalizeChapterTitle(value: String): String =
        value.filterNot { it.isWhitespace() || it == '\u00A0' }

    private fun textLength(node: Node): Int = when (node) {
        is TextNode -> node.text().length
        is Element -> node.childNodes().sumOf(::textLength)
        else -> node.childNodes().sumOf(::textLength)
    }

    private fun collectAnchors(
        node: Node,
        start: Int,
        anchors: MutableMap<String, Double>,
        totalLength: Int,
    ): Int {
        var offset = start
        if (node is Element && node.id().isNotBlank()) {
            anchors.putIfAbsent(node.id(), (offset.toDouble() / totalLength).coerceIn(0.0, 1.0))
        }
        when (node) {
            is TextNode -> offset += node.text().length
            else -> node.childNodes().forEach { child ->
                offset = collectAnchors(child, offset, anchors, totalLength)
            }
        }
        return offset
    }

    private fun findTextTarget(body: Element, nodePath: String): Pair<Int, TextNode>? {
        var current: Node = body
        val segments = nodePath.split('/').filter { it.isNotBlank() }
        for ((segmentIndex, segment) in segments.withIndex()) {
            if (segment == "body" && current === body) continue
            val element = current as? Element ?: return null
            val textSegment = TEXT_SEGMENT.matchEntire(segment)
            if (textSegment != null) {
                val textIndex = (textSegment.groupValues[1].toIntOrNull()?.minus(1) ?: 0)
                    .coerceAtLeast(0)
                val textNodes = element.childNodes().filterIsInstance<TextNode>()
                when {
                    textNodes.isNotEmpty() -> {
                        current = textNodes.getOrNull(textIndex) ?: return null
                    }

                    segmentIndex < segments.lastIndex -> return null
                    else -> {
                        val firstText = firstTextNode(element) ?: return null
                        return textStart(body, firstText)?.let { it to firstText }
                    }
                }
                continue
            }
            val elementSegment = ELEMENT_SEGMENT.matchEntire(segment) ?: return null
            val tagName = elementSegment.groupValues[1]
            val elementIndex = (elementSegment.groupValues[2].toIntOrNull()?.minus(1) ?: 0)
                .coerceAtLeast(0)
            current = element.children().filter { it.tagName().equals(tagName, true) }
                .getOrNull(elementIndex) ?: return null
        }
        return when (val target = current) {
            is TextNode -> textStart(body, target)?.let { it to target }
            is Element -> firstTextNode(target)?.let { textStart(body, it)?.let { start -> start to it } }
            else -> null
        }
    }

    private fun firstTextNode(element: Element): TextNode? {
        element.childNodes().forEach { child ->
            when (child) {
                is TextNode -> if (child.text().isNotEmpty()) return child
                is Element -> firstTextNode(child)?.let { return it }
            }
        }
        return null
    }

    private fun textStart(root: Node, target: TextNode): Int? {
        var offset = 0
        var found = false
        fun visit(node: Node) {
            if (found) return
            if (node === target) {
                found = true
                return
            }
            if (node is TextNode) {
                offset += node.text().length
            } else {
                node.childNodes().forEach(::visit)
            }
        }
        visit(root)
        return offset.takeIf { found }
    }

    private fun repairCachedContent(chapter: BookChapter, content: String): String {
        val boundary = readingBoundary(chapter)
        if (!checkedContentCaches.add(chapter.url)) return content
        val changedBoundary = boundary.startFragmentId != chapter.startFragmentId ||
            boundary.endFragmentId != chapter.endFragmentId ||
            boundary.getVariable("nextUrl") != chapter.getVariable("nextUrl")
        val crossResourceFragment = !chapter.endFragmentId.isNullOrBlank() &&
            chapter.url.substringBeforeLast("#") != chapter.getVariable("nextUrl").substringBeforeLast("#")
        if (!changedBoundary && !crossResourceFragment) return content
        // Only replace an unchanged output of the old reader. A user's edited text wins.
        if (content != getContent(chapter, legacyBoundary = true)) return content
        return getContent(chapter) ?: content
    }

    private fun getContent(chapter: BookChapter, legacyBoundary: Boolean = false): String? {
        /*获取当前章节文本*/
        val contents = epubBookContents ?: return null
        val boundary = if (legacyBoundary) chapter else readingBoundary(chapter)
        val nextChapterFirstResourceHref = boundary.getVariable("nextUrl").substringBeforeLast("#")
        val currentChapterFirstResourceHref = chapter.url.substringBeforeLast("#")
        val isLastChapter = nextChapterFirstResourceHref.isBlank()
        val startFragmentId = boundary.startFragmentId
        val endFragmentId = boundary.endFragmentId
        val elements = Elements()
        var findChapterFirstSource = false
        val includeNextChapterResource = !endFragmentId.isNullOrBlank()
        /*一些书籍依靠href索引的resource会包含多个章节，需要依靠fragmentId来截取到当前章节的内容*/
        /*注:这里较大增加了内容加载的时间，所以首次获取内容后可存储到本地cache，减少重复加载*/
        for (res in contents) {
            if (!findChapterFirstSource) {
                if (currentChapterFirstResourceHref != res.href) continue
                findChapterFirstSource = true
                // 第一个xhtml文件
                elements.add(
                    getBody(res, startFragmentId,
                        endFragmentId.takeIf { legacyBoundary || currentChapterFirstResourceHref == nextChapterFirstResourceHref })
                )
                // 不是最后章节 且 已经遍历到下一章节的内容时停止
                if (!isLastChapter && res.href == nextChapterFirstResourceHref) break
                continue
            }
            if (nextChapterFirstResourceHref != res.href) {
                // 其余部分
                elements.add(getBody(res, null, null))
            } else {
                // 下一章节的第一个xhtml
                if (includeNextChapterResource) {
                    //有Fragment 则添加到上一章节
                    elements.add(getBody(res, null, endFragmentId))
                }
                break
            }
        }
        //title标签中的内容不需要显示在正文中，去除
        elements.select("title").remove()
        elements.select("[style*=display:none]").remove()
        elements.select("img[src=\"cover.jpeg\"]").forEachIndexed { i, it ->
            if (i > 0) it.remove()
        }
        elements.select("img").forEach {
            if (it.attributesSize() <= 1) {
                return@forEach
            }
            val src = it.attr("src")
            it.clearAttributes()
            it.attr("src", src)
        }
        val tag = Book.rubyTag
        if (book.getDelTag(tag)) {
            elements.select("rp, rt").remove()
        }
        val html = elements.outerHtml()
        return HtmlFormatter.formatKeepImg(html)
    }

    private fun getBody(res: Resource, startFragmentId: String?, endFragmentId: String?): Element {
        /**
         * <image width="1038" height="670" xlink:href="..."/>
         * ...titlepage.xhtml
         * 大多数epub文件的封面页都会带有cover，可以一定程度上解决封面读取问题
         */
        if (res.href.contains("titlepage.xhtml") ||
            res.href.contains("cover")
        ) {
            return Jsoup.parseBodyFragment("<img src=\"cover.jpeg\" />")
        }

        // Jsoup可能会修复不规范的xhtml文件 解析处理后再获取
        var bodyElement = Jsoup.parse(String(res.data, mCharset)).body()
        bodyElement.children().run {
            select("script").remove()
            select("style").remove()
        }
        // 获取body对应的文本
        var bodyString = bodyElement.outerHtml()
        val originBodyString = bodyString
        /**
         * 某些xhtml文件 章节标题和内容不在一个节点或者不是兄弟节点
         * <div>
         *    <a class="mulu1>目录1</a>
         * </div>
         * <p>....</p>
         * <div>
         *    <a class="mulu2>目录2</a>
         * </div>
         * <p>....</p>
         * 先找到FragmentId对应的Element 然后直接截取之间的html
         */
        if (!startFragmentId.isNullOrBlank()) {
            bodyElement.getElementById(startFragmentId)?.outerHtml()?.let {
                val tagStart = it.substringBefore("\n")
                bodyString = tagStart + bodyString.substringAfter(tagStart)
            }
        }
        if (!endFragmentId.isNullOrBlank() && endFragmentId != startFragmentId) {
            bodyElement.getElementById(endFragmentId)?.outerHtml()?.let {
                val tagStart = it.substringBefore("\n")
                bodyString = bodyString.substringBefore(tagStart)
            }
        }
        //截取过再重新解析
        if (bodyString != originBodyString) {
            bodyElement = Jsoup.parse(bodyString).body()
        }
        /*选择去除正文中的H标签，部分书籍标题与阅读标题重复待优化*/
        val tag = Book.hTag
        if (book.getDelTag(tag)) {
            bodyElement.run {
                select("h1, h2, h3, h4, h5, h6").remove()
                //getElementsMatchingOwnText(chapter.title)?.remove()
            }
        }
        bodyElement.select("image").forEach {
            it.tagName("img", Parser.NamespaceHtml)
            it.attr("src", it.attr("xlink:href"))
        }
        bodyElement.select("img").forEach {
            val src = it.attr("src").trim().encodeURI()
            val href = res.href.encodeURI()
            val resolvedHref = URLDecoder.decode(URI(href).resolve(src).toString(), "UTF-8")
            it.attr("src", resolvedHref)
        }
        return bodyElement
    }

    private fun getImage(href: String): InputStream? {
        if (href == "cover.jpeg") return epubBook?.coverImage?.inputStream
        val abHref = URLDecoder.decode(href, "UTF-8")
        return epubBook?.resources?.getByHref(abHref)?.inputStream
    }

    private fun upBookCover(fastCheck: Boolean = false) {
        try {
            epubBook?.let {
                if (book.coverUrl.isNullOrEmpty()) {
                    book.coverUrl = LocalBook.getCoverPath(book)
                }
                if (fastCheck && File(book.coverUrl!!).exists()) {
                    return
                }
                /*部分书籍DRM处理后，封面获取异常，待优化*/
                it.coverImage?.inputStream?.use { input ->
                    val cover = BitmapFactory.decodeStream(input)
                    val out = FileOutputStream(FileUtils.createFileIfNotExist(book.coverUrl!!))
                    cover.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                    out.close()
                } ?: AppLog.putDebug("Epub: 封面获取为空. path: ${book.bookUrl}")
            }
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.localizedMessage}", e)
            e.printOnDebug()
        }
    }

    private fun upBookInfo() {
        if (epubBook == null) {
            cache.clearIf { it === this }
            book.intro = "书籍导入异常"
        } else {
            upBookCover()
            val metadata = epubBook!!.metadata
            book.name = metadata.firstTitle
            if (book.name.isEmpty()) {
                book.name = book.originName.replace(".epub", "")
            }

            if (metadata.authors.isNotEmpty()) {
                val author =
                    metadata.authors[0].toString().replace("^, |, $".toRegex(), "")
                book.author = author
            }
            if (metadata.descriptions.isNotEmpty()) {
                val desc = metadata.descriptions[0]
                book.intro = if (desc.isXml()) {
                    Jsoup.parse(metadata.descriptions[0]).text()
                } else {
                    desc
                }
            }
        }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()
        epubBook?.let { eBook ->
            val refs = eBook.tableOfContents.tocReferences
            if (refs == null || refs.isEmpty()) {
                AppLog.putDebug("Epub: NCX file parse error, check the file: ${book.bookUrl}")
                val spineReferences = eBook.spine.spineReferences
                var i = 0
                val size = spineReferences.size
                while (i < size) {
                    val resource = spineReferences[i].resource
                    var title = resource.title
                    if (TextUtils.isEmpty(title)) {
                        try {
                            val doc =
                                Jsoup.parse(String(resource.data, mCharset))
                            val elements = doc.getElementsByTag("title")
                            if (elements.isNotEmpty()) {
                                title = elements[0].text()
                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                    val chapter = BookChapter()
                    chapter.index = i
                    chapter.bookUrl = book.bookUrl
                    chapter.url = resource.href
                    if (i == 0 && title.isEmpty()) {
                        chapter.title = "封面"
                    } else {
                        chapter.title = title
                    }
                    chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
                    chapterList.add(chapter)
                    i++
                }
            } else {
                parseFirstPage(chapterList, refs)
                parseMenu(chapterList, refs)
            }
        }
        // Multiple navigation labels may point to the same content. Keep the established
        // reading identities, and link boundaries between actual, distinct resources only.
        val readingChapters = ArrayList(chapterList.distinctBy { it.url })
        readingChapters.forEachIndexed { index, chapter ->
            chapter.index = index
            val next = readingChapters.getOrNull(index + 1)
            chapter.endFragmentId = next?.startFragmentId
            chapter.putVariable("nextUrl", next?.url)
        }
        getWordCount(readingChapters, book)
        readingBoundaries = readingChapters.associate { it.url to it.copy() }
        return readingChapters
    }

    /*获取书籍起始页内容。部分书籍第一章之前存在封面，引言，扉页等内容*/
    /*tile获取不同书籍风格杂乱，格式化处理待优化*/
    private fun parseFirstPage(
        chapterList: ArrayList<BookChapter>,
        refs: List<TOCReference>?
    ) {
        val contents = epubBook?.contents
        if (epubBook == null || contents == null || refs == null) return
        val firstHref = epubTocNodes(refs).firstOrNull { it.href != null }?.href ?: return
        var i = 0
        while (i < contents.size) {
            val content = contents[i]
            if (!content.mediaType.toString().contains("htm")) {
                i++
                continue
            }
            /**
             * 检索到第一章href停止
             * completeHref可能有fragment(#id) 必须去除
             * fix https://github.com/gedoor/legado/issues/1932
             */
            if (firstHref.substringBeforeLast("#") == content.href) break
            val chapter = BookChapter()
            var title = content.title
            if (TextUtils.isEmpty(title)) {
                val elements = Jsoup.parse(
                    String(epubBook!!.resources.getByHref(content.href).data, mCharset)
                ).getElementsByTag("title")
                title =
                    if (elements.isNotEmpty() && elements[0].text().isNotBlank())
                        elements[0].text()
                    else
                        "--卷首--"
            }
            chapter.bookUrl = book.bookUrl
            chapter.title = title
            chapter.url = content.href
            chapter.startFragmentId =
                if (content.href.substringAfter("#") == content.href) null
                else content.href.substringAfter("#")

            chapterList.lastOrNull()?.endFragmentId = chapter.startFragmentId
            chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
            chapterList.add(chapter)
            i++
        }
    }

    private fun parseMenu(
        chapterList: ArrayList<BookChapter>,
        refs: List<TOCReference>?,
    ) {
        refs?.forEach { ref ->
            if (ref.resource != null) {
                val chapter = BookChapter()
                chapter.bookUrl = book.bookUrl
                chapter.title = ref.title
                chapter.url = ref.completeHref
                chapter.startFragmentId = ref.fragmentId
                chapter.isVolume = !ref.children.isNullOrEmpty()
                chapterList.add(chapter)
            }
            if (ref.children != null && ref.children.isNotEmpty()) {
                parseMenu(chapterList, ref.children)
            }
        }
    }


    override fun close() {
        readingBoundaries = null
        chapterLengthsCacheKey = null
        chapterLengthsCache = null
        checkedContentCaches.clear()
        epubBookContents = null
        epubBook = null
        val openedZip = zipFile
        val descriptor = fileDescriptor
        zipFile = null
        fileDescriptor = null
        kotlin.runCatching {
            if (openedZip != null) {
                openedZip.close()
            } else {
                descriptor?.close()
            }
        }.onFailure {
            kotlin.runCatching { descriptor?.close() }
            it.printOnDebug()
        }
    }

    private fun getWordCount(list: ArrayList<BookChapter>, book: Book) {
        if (!AppConfig.tocCountWords) {
            return
        }
        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapterList.isNotEmpty()) {
            val map = chapterList.associateBy({ it.getFileName() }, { it.wordCount })
            for (bookChapter in list) {
                val wordCount = map[bookChapter.getFileName()]
                if (wordCount != null) {
                    bookChapter.wordCount = wordCount
                }
            }
        }
    }

    private fun normalizeHref(href: String?): String =
        href.orEmpty().trimStart('/').substringBefore('#')

    private val TEXT_SEGMENT = Regex("""text\((?:\[(\d+)])?\)""")
    private val ELEMENT_SEGMENT = Regex("""([A-Za-z][\w:.-]*)(?:\[(\d+)])?""")

}
