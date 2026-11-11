package me.rerere.rikkahub.data.reading

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Local text import. The TXT heading patterns are adapted from MIT reading-nook; see docs/reading-nook-NOTICE.md. */
object ReadImporter {
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 6_000_000
    private const val MAX_PARAGRAPH_CHARS = 2000
    private const val MAX_ZIP_ENTRIES = 4000
    private const val MAX_EXPANDED_BYTES = 40 * 1024 * 1024
    private const val MAX_CHAPTER_BYTES = 4 * 1024 * 1024
    private const val MAX_CHAPTERS = 5000

    fun parse(file: File, filename: String): ReadBook {
        require(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "请选择不超过 20 MB 的 TXT 或 EPUB 文件" }
        val format = filename.substringAfterLast('.', "").lowercase()
        require(format in setOf("txt", "text", "epub")) { "暂时支持 TXT 和 EPUB；PDF、扫描书和加密书尚不支持" }
        val raw = file.inputStream().use { readBounded(it, MAX_BYTES) }
        val id = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        val fallbackTitle = filename.substringAfterLast('/').substringAfterLast('\\')
            .substringBeforeLast('.').trim().take(200).ifBlank { "未命名的书" }
        return if (format == "epub") {
            parseEpub(file, id, fallbackTitle)
        } else {
            val text = decodeText(raw).replace("\r\n", "\n").replace('\r', '\n').trim('\uFEFF')
            require(text.length <= MAX_TEXT_CHARS) { "正文超过 600 万字，请分卷导入" }
            require(text.isNotBlank()) { "这份文件没有可阅读的文字" }
            ReadBook(id, fallbackTitle, format = "txt", chapters = splitChapters(text))
        }
    }

    private fun decodeText(raw: ByteArray): String {
        fun strict(name: String, skip: Int = 0): String? = try {
            Charset.forName(name).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw, skip, raw.size - skip)).toString()
        } catch (_: CharacterCodingException) { null }

        when {
            raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte() ->
                return checkedText(requireNotNull(strict("UTF-8", 3)) { "UTF-8 文件损坏，请重新导出" })
            raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte() ->
                return checkedText(requireNotNull(strict("UTF-16LE", 2)) { "UTF-16 文件损坏，请重新导出" })
            raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte() ->
                return checkedText(requireNotNull(strict("UTF-16BE", 2)) { "UTF-16 文件损坏，请重新导出" })
        }
        strict("UTF-8")?.let { if ('\u0000' !in it) return checkedText(it) }
        // BOM-less UTF-16 commonly has ASCII newlines or punctuation with alternating NULs.
        if (raw.size % 2 == 0 && raw.take(2000).count { it == 0.toByte() } > 2) {
            val options = listOfNotNull(strict("UTF-16LE"), strict("UTF-16BE"))
            options.maxByOrNull(::textScore)?.let { if (textScore(it) > 0) return checkedText(it) }
        }
        val candidates = listOfNotNull(strict("GB18030"), strict("Big5"))
        require(candidates.isNotEmpty()) { "文字编码无法识别，请另存为 UTF-8 TXT 后导入" }
        return checkedText(candidates.maxBy(::textScore))
    }

    private fun checkedText(text: String): String {
        val controls = text.count { it == '\u0000' || (it.code < 32 && it !in "\n\r\t") }
        require(controls == 0 && '\uFFFD' !in text) { "文件含二进制或损坏文字，请另存为 UTF-8 TXT 后导入" }
        return text
    }

    private fun textScore(text: String): Int {
        val common = "的一是在不了有和人这中大为上个国我以要他时来用们生到作地于出就分对成会可主发年动同工也能下过子说产种面而方后多定行学法所民得经之进着等部度家电力里如水化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它合还因由其些然前外天政四日那社义事平形相全表间样与关各重新线内数正心反你明看原又么利比或但质气第向道命变条只没结解问意建月公无系军很情者最立代想已通并提直题程展果料象员位入常文总次品式活设及管特件长求老头基资边流路级少图山统接知较将组见计别她手角期根论运农指几九区强放决西被干做必战先回则任取据处理世车信北更东南书章春夜花風風愛讀書與時來為這會說個學們國體發裡無開頭後關聲頁從讓過種點親樣時長該實現還應對處於歡聽見輕聲媽媽門燈間".toSet()
        return text.take(8000).sumOf { c -> when {
            c == '\u0000' || (c.code < 32 && c !in "\n\r\t") || c in '\uE000'..'\uF8FF' -> -30
            c in common -> 4
            c.isLetterOrDigit() || c.isWhitespace() || c in "，。！？、：；“”‘’《》()（）.,!?;:\"'-" -> 1
            else -> -1
        } }
    }

    private val headingPatterns = listOf(
        Regex("^[\\t \\u3000]*((?:第[\\t ]*[0-9一二三四五六七八九十百千两〇零]+[\\t ]*[章节回卷部集])[^\\n]{0,40}|(?:序章|序幕|楔子|引子|尾声|终章|后记|番外)[^\\n]{0,40}|(?:Chapter|CHAPTER)[\\t ]+\\d+[^\\n]{0,40})[\\t ]*$", RegexOption.MULTILINE),
        Regex("^[\\t \\u3000]*(\\d{3,4}[\\t ]+[^\\n]{1,40}?)[\\t ]*$", RegexOption.MULTILINE),
        Regex("^[\\t \\u3000]*(\\d{1,4}[\\t ]*[、.．·:： ][^\\n]{1,35}?)[\\t ]*$", RegexOption.MULTILINE),
        Regex("^[\\t \\u3000]*([一二三四五六七八九十百]+[\\t ]*[、.．·:：][^\\n]{1,35}?)[\\t ]*$", RegexOption.MULTILINE),
    )

    private fun splitChapters(text: String): List<ReadChapter> {
        var matches = emptyList<MatchResult>()
        headingPatterns.forEachIndexed { index, regex ->
            val candidate = regex.findAll(text).toList()
            val enough = if (index == 0) candidate.isNotEmpty() else candidate.size >= 3
            if (enough && (matches.isEmpty() || candidate.size > matches.size * 1.15)) matches = candidate
        }
        require(matches.size <= MAX_CHAPTERS) { "章节过多，请分卷导入" }
        if (matches.isEmpty()) {
            val all = paragraphs(text)
            require(all.isNotEmpty()) { "这份文件没有可阅读的文字" }
            val result = mutableListOf<ReadChapter>()
            var current = mutableListOf<String>()
            var size = 0
            for (paragraph in all) {
                if (size >= 8000) {
                    result += ReadChapter("第 ${result.size + 1} 部分", current)
                    current = mutableListOf(); size = 0
                }
                current += paragraph; size += paragraph.length
            }
            if (current.isNotEmpty()) result += ReadChapter("第 ${result.size + 1} 部分", current)
            return result
        }
        val result = mutableListOf<ReadChapter>()
        val opening = paragraphs(text.substring(0, matches.first().range.first))
        if (opening.isNotEmpty()) result += ReadChapter("开篇", opening)
        matches.forEachIndexed { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val title = match.groupValues[1].trim()
            val body = paragraphs(text.substring(match.range.last + 1, end))
            result += ReadChapter(title, body.ifEmpty { listOf(title) })
        }
        return result
    }

    private fun paragraphs(text: String): List<String> = text.split('\n').flatMap { line ->
        val paragraph = line.trim()
        if (paragraph.isEmpty()) emptyList() else {
            val result = mutableListOf<String>()
            var start = 0
            while (start < paragraph.length) {
                var end = minOf(start + MAX_PARAGRAPH_CHARS, paragraph.length)
                if (end < paragraph.length && Character.isHighSurrogate(paragraph[end - 1])) end--
                result += paragraph.substring(start, end)
                start = end
            }
            result
        }
    }

    private fun parseEpub(file: File, id: String, fallbackTitle: String): ReadBook = try {
        ZipFile(file).use { zip ->
            val entries = linkedMapOf<String, ZipEntry>()
            var expanded = 0L
            var entryCount = 0
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                entryCount++
                require(entryCount <= MAX_ZIP_ENTRIES) { "EPUB 内文件过多，请分卷导入" }
                if (entry.isDirectory) continue
                val name = entry.name
                require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name && name.none { it.code < 32 } &&
                    name.split('/').none { it.isEmpty() || it == "." || it == ".." || ':' in it } &&
                    !entries.containsKey(name)) { "EPUB 含重复或不规范的内部路径" }
                require(entry.size in 0..MAX_EXPANDED_BYTES.toLong()) { "EPUB 解压后的内容过大或长度未知" }
                expanded += entry.size
                require(expanded <= MAX_EXPANDED_BYTES) { "EPUB 解压后的内容超过 40 MB，请分卷导入" }
                require(entry.size <= 1024 * 1024 || entry.compressedSize > 0 && entry.size / entry.compressedSize <= 250) {
                    "EPUB 压缩比异常，无法安全读取"
                }
                entries[name] = entry
            }
            var bytesRead = 0
            fun read(name: String, limit: Int = MAX_CHAPTER_BYTES): ByteArray {
                val entry = entries[name] ?: error("EPUB 缺少文件：${name.take(160)}")
                require(entry.size <= limit) { "EPUB 单章或元数据过大，请分卷导入" }
                val bytes = zip.getInputStream(entry).use { readBounded(it, limit) }
                bytesRead += bytes.size
                require(bytesRead <= MAX_EXPANDED_BYTES) { "EPUB 正文过大，请分卷导入" }
                return bytes
            }
            fun xml(name: String, limit: Int = 1024 * 1024): Element {
                val bytes = read(name, limit)
                val text = if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
                    bytes.toString(Charsets.UTF_16LE)
                else if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
                    bytes.toString(Charsets.UTF_16BE) else bytes.toString(Charsets.UTF_8)
                require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "EPUB 元数据含不支持的实体声明" }
                return Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())
            }
            require(!entries.containsKey("META-INF/rights.xml")) { "这本 EPUB 有版权加密，暂时无法读取" }
            if (entries.containsKey("META-INF/encryption.xml")) {
                val encryption = xml("META-INF/encryption.xml")
                val algorithms = encryption.getAllElements().filter { it.localName() == "EncryptionMethod" }
                    .map { it.attr("Algorithm") }
                val resources = encryption.getAllElements().filter { it.localName() == "CipherReference" }
                    .map { it.attr("URI").substringBefore('#').lowercase() }
                require(algorithms.isNotEmpty() && resources.isNotEmpty() && algorithms.all {
                    it == "http://www.idpf.org/2008/embedding" || it == "http://ns.adobe.com/pdf/enc#RC"
                } && resources.all { it.endsWith(".otf") || it.endsWith(".ttf") || it.endsWith(".woff") || it.endsWith(".woff2") }) {
                    "这本 EPUB 有加密正文或不支持的加密资源，暂时无法读取"
                }
            }
            val container = xml("META-INF/container.xml")
            val rootfiles = container.getAllElements().filter { it.localName() == "rootfile" }
            val rootfile = rootfiles.firstOrNull { it.attr("media-type") == "application/oebps-package+xml" }
                ?: rootfiles.firstOrNull() ?: error("EPUB 缺少书籍目录定义")
            val opfPath = resolveArchivePath("", rootfile.attr("full-path"))
            val opf = xml(opfPath)
            val opfDir = opfPath.substringBeforeLast('/', "")
            val manifestItems = opf.getAllElements().filter { it.localName() == "item" }
            val manifest = manifestItems.associateBy { it.attr("id") }
            require(manifest.size == manifestItems.size && manifest.keys.none { it.isEmpty() }) { "EPUB 目录含重复或缺失的章节标识" }
            val spine = opf.getAllElements().firstOrNull { it.localName() == "spine" }
                ?: error("EPUB 缺少阅读顺序，无法读取")
            val itemrefs = spine.children().filter { it.localName() == "itemref" && it.attr("linear") != "no" }
            require(itemrefs.isNotEmpty() && itemrefs.size <= MAX_CHAPTERS) { "EPUB 没有正文或章节过多" }
            val images = linkedMapOf<String, ReadImage>()
            var imageBytes = 0
            val imagePaths = mutableMapOf<String, String>()
            val declaredImageMimes = manifestItems.filter { it.attr("media-type") in READ_IMAGE_MIMES }.associate {
                resolveArchivePath(opfDir, it.attr("href")) to it.attr("media-type")
            }
            fun image(path: String, alt: String): String? {
                imagePaths[path]?.let { return it }
                val entry = entries[path] ?: return null
                require(entry.size <= READ_IMAGE_MAX_BYTES) { "EPUB 单张插图超过 4 MB，请压缩后重试" }
                val raw = read(path, READ_IMAGE_MAX_BYTES)
                val detected = readImageMime(raw)
                val declared = declaredImageMimes[path]
                require(declared == null || declared == detected) { "EPUB 插图内容与声明的格式不一致" }
                val mime = detected ?: return null
                validateReadImageBytes(raw, mime)
                val imageId = readSha256(raw)
                if (imageId !in images) {
                    imageBytes += raw.size
                    require(imageBytes <= READ_IMAGES_MAX_BYTES && images.size < READ_IMAGES_MAX_COUNT) { "EPUB 插图总量超过 12 MB 或数量过多，请分卷导入" }
                    images[imageId] = ReadImage(imageId, mime, Base64.getEncoder().encodeToString(raw), alt.take(2000))
                }
                imagePaths[path] = imageId
                return imageId
            }
            val metadata = opf.getAllElements().firstOrNull { it.localName() == "metadata" }
            val coverItemId = metadata?.getAllElements()?.firstOrNull {
                it.localName() == "meta" && it.attr("name").equals("cover", true)
            }?.attr("content")
            val coverItem = manifestItems.firstOrNull { "cover-image" in it.attr("properties").split(Regex("\\s+")) }
                ?: coverItemId?.let(manifest::get)
            val coverImageId = coverItem?.takeIf { it.attr("media-type") in READ_IMAGE_MIMES }
                ?.let { image(resolveArchivePath(opfDir, it.attr("href")), "封面") }
            var chars = 0
            val chapters = itemrefs.mapIndexedNotNull { index, itemref ->
                val item = manifest[itemref.attr("idref")] ?: error("EPUB 阅读顺序引用了缺失的章节")
                require(item.attr("media-type") in setOf("application/xhtml+xml", "text/html")) { "暂不支持含非文字正文的 EPUB" }
                val chapterPath = resolveArchivePath(opfDir, item.attr("href"))
                val doc = Jsoup.parse(read(chapterPath).inputStream(), null, "")
                doc.select("script, style, noscript, iframe, object, embed, svg, math, nav, form, audio, video, template").remove()
                val heading = doc.selectFirst("h1, h2, h3")?.text()?.trim().orEmpty()
                val title = heading.ifBlank { doc.title().trim() }.take(200).ifBlank { "第 ${index + 1} 章" }
                val body = StringBuilder()
                val content = mutableListOf<String>()
                val chapterImages = linkedMapOf<Int, String>()
                fun flush() {
                    content += paragraphs(body.toString())
                    body.setLength(0)
                }
                // Iterative traversal also handles maliciously deep nesting without overflowing the JVM stack.
                val pending = ArrayDeque<Pair<Node, Boolean>>()
                pending.addLast(doc.body() to false)
                var nodes = 0
                while (pending.isNotEmpty()) {
                    val (node, ending) = pending.removeLast()
                    if (ending) { body.append('\n'); continue }
                    nodes++
                    require(nodes <= 150_000) { "EPUB 单章结构过于复杂，请重新导出纯文字版" }
                    if (node is TextNode) body.append(node.text().replace(Regex("[\\t\\r\\n ]+"), " "))
                    else if (node is Element) {
                        if (node.normalName() == "img") {
                            val alt = node.attr("alt").trim().take(2000)
                            val src = node.attr("src").trim()
                            val uri = runCatching { URI(src) }.getOrNull()
                            // Only actual DOM image nodes can create image coordinates. External/data URLs are never read.
                            val imageId = if (src.isNotBlank() && uri != null && !uri.isAbsolute && uri.authority == null && uri.query == null) {
                                image(resolveArchivePath(chapterPath.substringBeforeLast('/', ""), src), alt)
                            } else null
                            if (imageId != null) {
                                flush()
                                chapterImages[content.size] = imageId
                                content += alt.ifBlank { "插图" }
                            } else body.append(alt)
                            continue
                        }
                        val block = node.normalName() in setOf("p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "br", "tr", "hr")
                        if (block) { body.append('\n'); pending.addLast(node to true) }
                        node.childNodes().asReversed().forEach { pending.addLast(it to false) }
                    }
                }
                flush()
                chars += content.sumOf { it.length }
                require(chars <= MAX_TEXT_CHARS) { "EPUB 正文超过 600 万字，请分卷导入" }
                if (content.isEmpty()) null else ReadChapter(title, content, chapterImages)
            }
            require(chapters.isNotEmpty()) { "EPUB 中没有可读取的文字或支持的本地插图" }
            val title = metadata?.getAllElements()?.firstOrNull { it.localName() == "title" }?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }?.take(200) ?: fallbackTitle
            val author = metadata?.getAllElements()?.firstOrNull { it.localName() == "creator" }?.text()?.trim().orEmpty().take(200)
            ReadBook(id, title, author, "epub", chapters, images = images, coverImageId = coverImageId).also(::validateReadImages)
        }
    } catch (error: ZipException) {
        throw IllegalArgumentException("EPUB 压缩包损坏或加密，请重新导出未加密文件", error)
    }

    private fun Element.localName(): String = tagName().substringAfter(':')

    private fun resolveArchivePath(directory: String, raw: String): String {
        require(raw.isNotBlank() && '\\' !in raw) { "EPUB 内部路径无效" }
        val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("EPUB 内部路径无法识别") }
        require(!uri.isAbsolute && uri.authority == null && uri.query == null) { "EPUB 正文不能引用外部资源" }
        val decoded = uri.path.orEmpty()
        require(decoded.isNotEmpty() && !decoded.startsWith('/') && '\\' !in decoded && decoded.none { it.code < 32 }) { "EPUB 内部路径无效" }
        val parts = directory.split('/').filter { it.isNotEmpty() }.toMutableList()
        decoded.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> { require(parts.isNotEmpty()) { "EPUB 内部路径越过书籍目录" }; parts.removeAt(parts.lastIndex) }
                else -> { require(':' !in part) { "EPUB 内部路径无效" }; parts += part }
            }
        }
        require(parts.isNotEmpty()) { "EPUB 内部路径无效" }
        return parts.joinToString("/")
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "文件或 EPUB 内容过大，请分卷导入" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

internal const val READ_IMAGE_MAX_BYTES = 4 * 1024 * 1024
internal const val READ_IMAGES_MAX_BYTES = 12 * 1024 * 1024
internal const val READ_IMAGES_MAX_COUNT = 512
internal val READ_IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

internal fun readSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal fun readImageMime(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte) -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    else -> null
}

/** Header-only dimension checks work on both the JVM and Android; source image bytes never execute. */
internal fun validateReadImageBytes(bytes: ByteArray, mime: String) {
    require(bytes.size in 1..READ_IMAGE_MAX_BYTES && readImageMime(bytes) == mime) { "插图格式或大小无效" }
    fun u(index: Int) = bytes[index].toInt() and 255
    fun le16(index: Int) = u(index) or (u(index + 1) shl 8)
    fun be16(index: Int) = (u(index) shl 8) or u(index + 1)
    fun be32(index: Int): Long = (u(index).toLong() shl 24) or (u(index + 1).toLong() shl 16) or
        (u(index + 2).toLong() shl 8) or u(index + 3).toLong()
    fun le24(index: Int) = u(index).toLong() or (u(index + 1).toLong() shl 8) or (u(index + 2).toLong() shl 16)
    val size: Pair<Long, Long> = when (mime) {
        "image/png" -> {
            require(bytes.size >= 33 && String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR" && be32(8) == 13L) { "PNG 插图头损坏" }
            be32(16) to be32(20)
        }
        "image/gif" -> {
            require(bytes.size >= 13) { "GIF 插图头损坏" }
            le16(6).toLong() to le16(8).toLong()
        }
        "image/webp" -> {
            require(bytes.size >= 30) { "WebP 插图头损坏" }
            when (String(bytes, 12, 4, Charsets.US_ASCII)) {
                "VP8X" -> (le24(24) + 1) to (le24(27) + 1)
                "VP8L" -> {
                    require(u(20) == 0x2f) { "WebP 插图头损坏" }
                    (1L + ((u(21) or (u(22) shl 8)) and 0x3fff)) to
                        (1L + (((u(22) shr 6) or (u(23) shl 2) or (u(24) shl 10)) and 0x3fff))
                }
                "VP8 " -> {
                    require(u(23) == 0x9d && u(24) == 1 && u(25) == 0x2a) { "WebP 插图头损坏" }
                    (le16(26) and 0x3fff).toLong() to (le16(28) and 0x3fff).toLong()
                }
                else -> error("不支持的 WebP 插图编码")
            }
        }
        "image/jpeg" -> {
            var cursor = 2
            var dimensions: Pair<Long, Long>? = null
            while (cursor < bytes.size && dimensions == null) {
                require(u(cursor++) == 0xff) { "JPEG 插图头损坏" }
                while (cursor < bytes.size && u(cursor) == 0xff) cursor++
                require(cursor < bytes.size) { "JPEG 插图头损坏" }
                val marker = u(cursor++)
                if (marker in setOf(0xd8, 0x01) || marker in 0xd0..0xd7) continue
                require(marker != 0xda && marker != 0xd9 && cursor + 2 <= bytes.size) { "JPEG 插图缺少分辨率" }
                val length = be16(cursor)
                require(length >= 2 && cursor + length <= bytes.size) { "JPEG 插图头损坏" }
                if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
                    require(length >= 8) { "JPEG 插图头损坏" }
                    dimensions = be16(cursor + 5).toLong() to be16(cursor + 3).toLong()
                }
                cursor += length
            }
            requireNotNull(dimensions) { "JPEG 插图缺少分辨率" }
        }
        else -> error("暂时只支持 PNG、JPEG、GIF、WebP 插图")
    }
    require(size.first in 1..10_000 && size.second in 1..10_000 && size.first * size.second <= 32_000_000) { "插图分辨率过大，请压缩到 3200 万像素以内" }
}

internal fun readImageBytes(image: ReadImage): ByteArray {
    require(image.data.length <= (READ_IMAGE_MAX_BYTES + 2) / 3 * 4 && image.alt.length <= 2000 && image.mimeType in READ_IMAGE_MIMES) { "插图信息无效" }
    val raw = try { Base64.getDecoder().decode(image.data) } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("插图编码损坏", error)
    }
    require(image.id.matches(Regex("[a-f0-9]{64}")) && readSha256(raw) == image.id) { "插图校验不一致" }
    validateReadImageBytes(raw, image.mimeType)
    return raw
}

fun validateReadImages(book: ReadBook) {
    require(book.images.size <= READ_IMAGES_MAX_COUNT) { "书籍插图数量过多" }
    var bytes = 0L
    book.images.forEach { (id, image) ->
        require(id == image.id) { "插图编号与内容不一致" }
        bytes += readImageBytes(image).size
        require(bytes <= READ_IMAGES_MAX_BYTES) { "书籍插图总量超过 12 MB" }
    }
    require(book.coverImageId == null || book.coverImageId in book.images) { "书籍封面缺失" }
    book.chapters.forEach { chapter -> chapter.images.forEach { (paragraph, id) ->
        require(paragraph in chapter.paragraphs.indices && id in book.images) { "书籍插图位置或资源无效" }
    } }
}
