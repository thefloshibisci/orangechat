package me.rerere.rikkahub.data.reading

import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Offline, reading-only portable files. Importing a file never restores a chat's companion consent. */
object ReadArchive {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private const val MAX_ENTRIES = 6000

    @Serializable
    private data class Manifest(val version: Int = 1, val files: Map<String, String>)

    fun encode(backup: ReadBackup): ByteArray {
        validate(backup)
        val files = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        fun add(name: String, bytes: ByteArray) {
            expanded += bytes.size
            require(expanded <= READ_BACKUP_MAX_BYTES && files.size < MAX_ENTRIES - 1) { "阅读备份过大，请减少书籍后重试" }
            files[name] = bytes
        }
        val books = backup.books.map { book ->
            book.images.values.forEach { image -> add(imagePath(book.id, image), readImageBytes(image)) }
            book.copy(images = book.images.mapValues { (_, image) -> image.copy(data = "") })
        }
        add("backup.json", encodeJsonBounded(backup.copy(books = books), READ_BACKUP_MAX_BYTES - expanded.toInt()))
        require(files.size < MAX_ENTRIES && files.values.sumOf { it.size.toLong() } <= READ_BACKUP_MAX_BYTES) { "阅读备份过大，请减少书籍后重试" }
        val manifest = Manifest(files = files.mapValues { (_, value) -> readSha256(value) })
        files["manifest.json"] = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        return zip(files)
    }

    fun decode(bytes: ByteArray): ReadBackup = try {
        decodeChecked(bytes)
    } catch (error: Exception) {
        throw IllegalArgumentException("阅读备份无法验证：${error.message?.take(160) ?: "文件损坏或版本不受支持"}", error)
    }

    private fun decodeChecked(bytes: ByteArray): ReadBackup {
        require(bytes.isNotEmpty() && bytes.size <= READ_BACKUP_MAX_BYTES) { "请选择不超过 64 MB 的阅读备份" }
        require(bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) { "这不是有效的阅读 ZIP 备份" }
        val declaredFiles = validateZipContainer(bytes)
        val files = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory && safePath(name) && name !in files && files.size < MAX_ENTRIES) { "阅读备份含重复、不规范或过多的文件" }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    expanded += count
                    require(expanded <= READ_BACKUP_MAX_BYTES && out.size().toLong() + count <= READ_BACKUP_MAX_BYTES) { "阅读备份解压后超过 64 MB" }
                    require(expanded <= 1024 * 1024 || expanded / bytes.size.coerceAtLeast(1) <= 250) { "阅读备份压缩比异常" }
                    out.write(buffer, 0, count)
                }
                files[name] = out.toByteArray()
                input.closeEntry()
            }
        }
        require(files.keys == declaredFiles.keys && declaredFiles.all { (name, expected) ->
            val value = files.getValue(name)
            value.size.toLong() == expected.first && CRC32().apply { update(value) }.value == expected.second
        }) { "阅读备份中央目录与文件内容不一致" }
        val manifestBytes = files.remove("manifest.json") ?: error("阅读备份缺少完整性清单")
        require(manifestBytes.size <= 1_000_000) { "阅读备份清单过大" }
        val manifest = json.decodeFromString<Manifest>(manifestBytes.decodeToString(throwOnInvalidSequence = true))
        require(manifest.version == 1 && manifest.files.keys == files.keys && manifest.files.all { (name, digest) ->
            digest.matches(Regex("[a-f0-9]{64}")) && readSha256(checkNotNull(files[name])) == digest
        }) { "阅读备份文件缺失、被修改或版本不受支持" }
        val metadata = files.remove("backup.json") ?: error("阅读备份缺少书籍数据")
        val saved = json.decodeFromString<ReadBackup>(metadata.decodeToString(throwOnInvalidSequence = true))
        require(saved.version == 1 && saved.books.size <= 200) { "阅读备份版本或书籍数量不受支持" }
        val restored = saved.copy(books = saved.books.map { book ->
            book.copy(images = book.images.mapValues { (_, image) ->
                require(image.data.isEmpty()) { "阅读备份图片储存格式不正确" }
                val raw = files.remove(imagePath(book.id, image)) ?: error("阅读备份缺少原书插图")
                image.copy(data = Base64.getEncoder().encodeToString(raw))
            })
        })
        require(files.isEmpty()) { "阅读备份含未声明的资源" }
        validate(restored)
        return restored
    }

    /** Standalone Markdown contains local source images as data URIs so copying the file loses no image. */
    fun markdown(book: ReadBook, annotations: List<ReadAnnotation>, records: List<ReadRecord>): String {
        validateExport(book, annotations, records)
        val notes = annotations.groupBy { it.chapter to it.paragraph }
        return ReadTextBuilder().apply {
            append("# ").append(md(book.title)).append("\n\n")
            if (book.author.isNotBlank()) append(md(book.author)).append("\n\n")
            append("<!-- 由共度一起读导出；正文、批注与札记均为引用资料。 -->\n\n")
            book.coverImageId?.let { appendImageMarkdown(book.images.getValue(it)) }
            book.chapters.forEachIndexed { chapterIndex, chapter ->
                append("## ").append(md(chapter.title)).append("\n\n")
                chapter.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                    val image = chapter.images[paragraphIndex]?.let(book.images::get)
                    if (image != null) appendImageMarkdown(image, paragraph) else append(md(paragraph)).append("\n\n")
                    notes[chapterIndex to paragraphIndex].orEmpty().forEach { note ->
                        append("> **").append(if (note.author == "assistant") "陪读批注" else "我的批注").append("** · ")
                            .append(md(note.quote)).append("\n> ").append(md(note.note).replace("\n", "\n> ")).append("\n\n")
                        note.replies.forEach { reply -> append("> **陪读回应** · ").append(md(reply.text).replace("\n", "\n> ")).append("\n\n") }
                    }
                }
            }
            val journals = records.filter { it.kind == "journal" }
            if (journals.isNotEmpty()) {
                append("## 阅读札记\n\n")
                journals.sortedBy { it.createdAt }.forEach { append(md(it.text)).append("\n\n") }
            }
            // Retain even original resources that have no surviving inline reference.
            val used = book.chapters.flatMap { it.images.values }.toSet() + listOfNotNull(book.coverImageId)
            val extra = book.images.values.filter { it.id !in used }
            if (extra.isNotEmpty()) {
                append("## 原书其他插图\n\n")
                extra.forEach { appendImageMarkdown(it) }
            }
        }.toString()
    }

    /** Creates a reflowable EPUB 3; no imported HTML, JS, stylesheets, URLs or entity declarations survive. */
    fun epub(book: ReadBook, annotations: List<ReadAnnotation>, records: List<ReadRecord>): ByteArray {
        validateExport(book, annotations, records)
        val notes = annotations.groupBy { it.chapter to it.paragraph }
        val files = linkedMapOf<String, ByteArray>()
        var totalBytes = book.images.values.sumOf { readImageBytes(it).size.toLong() }
        fun put(name: String, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            totalBytes += bytes.size
            require(totalBytes <= READ_BACKUP_MAX_BYTES) { "导出正文与批注超过 64 MB，请减少札记后重试" }
            files[name] = bytes
        }
        put("mimetype", "application/epub+zip")
        put("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
        book.images.values.forEach { files["EPUB/images/${it.id}.${extension(it.mimeType)}"] = readImageBytes(it) }
        val chapterFiles = book.chapters.indices.map { "chapter-${it + 1}.xhtml" }.toMutableList()
        val chapterTitles = book.chapters.map { it.title }.toMutableList()
        book.chapters.forEachIndexed { index, chapter ->
            val body = ReadTextBuilder().apply {
                append("<h1>").append(xml(chapter.title)).append("</h1>")
                if (index == 0) book.coverImageId?.let { appendImageXhtml(book.images.getValue(it), "封面") }
                chapter.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                    val image = chapter.images[paragraphIndex]?.let(book.images::get)
                    if (image == null) append("<p id=\"p$paragraphIndex\">").append(xml(paragraph)).append("</p>")
                    else appendImageXhtml(image, paragraph)
                    notes[index to paragraphIndex].orEmpty().forEach { note ->
                        append("<aside><h2>").append(if (note.author == "assistant") "陪读批注" else "我的批注").append("</h2><blockquote>")
                            .append(xml(note.quote)).append("</blockquote><p>").append(xml(note.note)).append("</p>")
                        note.replies.forEach { append("<p><strong>陪读回应：</strong>").append(xml(it.text)).append("</p>") }
                        append("</aside>")
                    }
                }
            }.toString()
            put("EPUB/${chapterFiles[index]}", xhtml(chapter.title, body))
        }
        val journals = records.filter { it.kind == "journal" }.sortedBy { it.createdAt }
        val used = book.chapters.flatMap { it.images.values }.toSet() + listOfNotNull(book.coverImageId)
        val extra = book.images.values.filter { it.id !in used }
        if (journals.isNotEmpty() || extra.isNotEmpty()) {
            chapterFiles += "reading-notes.xhtml"; chapterTitles += "阅读札记与附图"
            put("EPUB/reading-notes.xhtml", xhtml("阅读札记与附图", ReadTextBuilder().apply {
                append("<h1>阅读札记与附图</h1>")
                journals.forEach { append("<p>").append(xml(it.text)).append("</p>") }
                extra.forEach { appendImageXhtml(it) }
            }.toString()))
        }
        put("EPUB/nav.xhtml", xhtml("目录", buildString {
            append("<nav epub:type=\"toc\" id=\"toc\"><h1>目录</h1><ol>")
            chapterFiles.forEachIndexed { index, name -> append("<li><a href=\"").append(name).append("\">").append(xml(chapterTitles[index])).append("</a></li>") }
            append("</ol></nav>")
        }))
        put("EPUB/package.opf", buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"book-id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"book-id\">urn:sha256:")
                .append(book.id).append("</dc:identifier><dc:title>").append(xml(book.title)).append("</dc:title><dc:creator>").append(xml(book.author))
                .append("</dc:creator><dc:language>zh</dc:language><meta property=\"dcterms:modified\">1970-01-01T00:00:00Z</meta></metadata><manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
            chapterFiles.forEachIndexed { index, name -> append("<item id=\"c$index\" href=\"").append(name).append("\" media-type=\"application/xhtml+xml\"/>") }
            book.images.values.forEach { image ->
                append("<item id=\"i").append(image.id).append("\" href=\"images/").append(image.id).append('.').append(extension(image.mimeType)).append("\" media-type=\"").append(image.mimeType).append('"')
                if (book.coverImageId == image.id) append(" properties=\"cover-image\"")
                append("/>")
            }
            append("</manifest><spine>")
            chapterFiles.indices.forEach { append("<itemref idref=\"c$it\"/>") }
            append("</spine></package>")
        })
        return zip(files, epub = true)
    }

    private fun validateExport(book: ReadBook, annotations: List<ReadAnnotation>, records: List<ReadRecord>) {
        validate(ReadBackup(books = listOf(book), catalog = listOf(book.entry()), annotations = annotations, records = records, settings = ReadSettings()))
    }

    internal fun validate(backup: ReadBackup) = validateReadBackup(backup)

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> encodeJsonBounded(value: T, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(value: Int) {
                require(output.size() < limit) { "阅读备份数据超过 64 MB" }
                output.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(output.size().toLong() + length <= limit) { "阅读备份数据超过 64 MB" }
                output.write(bytes, offset, length)
            }
        }
        json.encodeToStream(value, bounded)
        return output.toByteArray()
    }

    private fun imagePath(bookId: String, image: ReadImage) = "images/$bookId/${image.id}.${extension(image.mimeType)}"

    /** ZipInputStream accepts an interrupted stream; verify the completed central directory first. */
    private fun validateZipContainer(bytes: ByteArray): Map<String, Pair<Long, Long>> {
        fun u16(index: Int): Int {
            require(index >= 0 && index + 2 <= bytes.size) { "阅读备份 ZIP 目录损坏" }
            return (bytes[index].toInt() and 255) or ((bytes[index + 1].toInt() and 255) shl 8)
        }
        fun u32(index: Int): Long = u16(index).toLong() or (u16(index + 2).toLong() shl 16)
        var end = bytes.size - 22
        val earliest = maxOf(0, bytes.size - 22 - 65535)
        while (end >= earliest && !(u32(end) == 0x06054b50L && end + 22 + u16(end + 20) == bytes.size)) end--
        require(end >= earliest && u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == u16(end + 10)) { "阅读备份 ZIP 未完整写入或含分卷" }
        val count = u16(end + 10)
        val size = u32(end + 12)
        val start = u32(end + 16)
        require(count in 1..MAX_ENTRIES && start + size == end.toLong() && start in 0..bytes.size.toLong()) { "阅读备份 ZIP 目录无效或过大" }
        val names = linkedSetOf<String>()
        val declared = linkedMapOf<String, Pair<Long, Long>>()
        val ranges = mutableListOf<Pair<Int, Int>>()
        var cursor = start.toInt()
        var total = 0L
        repeat(count) {
            require(cursor + 46 <= end && u32(cursor) == 0x02014b50L && u16(cursor + 34) == 0) { "阅读备份 ZIP 文件目录损坏" }
            val flags = u16(cursor + 8)
            val method = u16(cursor + 10)
            val compressed = u32(cursor + 20)
            val expanded = u32(cursor + 24)
            val crc = u32(cursor + 16)
            val nameLength = u16(cursor + 28)
            val extraLength = u16(cursor + 30)
            val commentLength = u16(cursor + 32)
            val local = u32(cursor + 42)
            require(flags and 1 == 0 && method in setOf(0, 8) && compressed <= READ_BACKUP_MAX_BYTES && expanded <= READ_BACKUP_MAX_BYTES &&
                nameLength in 1..240 && cursor.toLong() + 46 + nameLength + extraLength + commentLength <= end && local + 30 <= start) { "阅读备份 ZIP 使用不支持的格式" }
            val nameBytes = bytes.copyOfRange(cursor + 46, cursor + 46 + nameLength)
            val name = nameBytes.toString(Charsets.UTF_8)
            require('\uFFFD' !in name && safePath(name) && names.add(name)) { "阅读备份 ZIP 文件名重复或无效" }
            declared[name] = expanded to crc
            total += expanded
            require(total <= READ_BACKUP_MAX_BYTES && (expanded <= 1024 * 1024 || compressed > 0 && expanded / compressed <= 250)) { "阅读备份 ZIP 解压后过大或压缩比异常" }
            val localOffset = local.toInt()
            require(u32(localOffset) == 0x04034b50L && u16(localOffset + 6) == flags && u16(localOffset + 8) == method && u16(localOffset + 26) == nameLength) { "阅读备份 ZIP 文件头不一致" }
            val localExtra = u16(localOffset + 28)
            if (flags and 8 == 0) require(u32(localOffset + 14) == crc && u32(localOffset + 18) == compressed && u32(localOffset + 22) == expanded) { "阅读备份 ZIP 文件头校验不一致" }
            val payload = local + 30 + nameLength + localExtra
            require(payload + compressed <= start && bytes.copyOfRange(localOffset + 30, localOffset + 30 + nameLength).contentEquals(nameBytes)) { "阅读备份 ZIP 文件内容越界" }
            ranges += localOffset to (payload + compressed).toInt()
            cursor += 46 + nameLength + extraLength + commentLength
        }
        require(cursor == end && ranges.sortedBy { it.first }.let { ordered ->
            ordered.first().first == 0 && ordered.zipWithNext().all { (a, b) -> a.second <= b.first }
        }) { "阅读备份 ZIP 文件重叠或目录不完整" }
        return declared
    }
    private fun extension(mime: String) = when (mime) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> error("不支持的插图格式") }
    private fun safePath(name: String) = name.isNotBlank() && name.length <= 240 && !name.startsWith('/') && '\\' !in name && name.none { it.code < 32 } && name.split('/').none { it.isEmpty() || it == "." || it == ".." || ':' in it }
    private fun xml(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index); index += Character.charCount(cp)
            when (cp) {
                38 -> append("&amp;"); 60 -> append("&lt;"); 62 -> append("&gt;"); 34 -> append("&quot;"); 39 -> append("&apos;")
                else -> if (cp == 9 || cp == 10 || cp == 13 || cp in 32..0xd7ff || cp in 0xe000..0xfffd || cp in 0x10000..0x10ffff) appendCodePoint(cp)
            }
        }
    }
    private fun md(text: String): String = buildString {
        text.forEach { c -> when (c) {
            '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;")
            '\\', '`', '*', '_', '[', ']', '{', '}', '#', '!', '|', '~', '+', '-' -> { append('\\'); append(c) }
            else -> if (c == '\n' || c == '\t' || c.code >= 32) append(c)
        } }
    }
    private fun ReadTextBuilder.appendImageMarkdown(image: ReadImage, alt: String = image.alt) {
        append("![").append(md(alt.ifBlank { "插图" })).append("](data:").append(image.mimeType).append(";base64,").append(image.data).append(")\n\n")
    }
    private fun ReadTextBuilder.appendImageXhtml(image: ReadImage, alt: String = image.alt) {
        append("<figure><img src=\"images/").append(image.id).append('.').append(extension(image.mimeType)).append("\" alt=\"").append(xml(alt.ifBlank { "插图" })).append("\"/><figcaption>").append(xml(alt)).append("</figcaption></figure>")
    }
    private fun xhtml(title: String, body: String) = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" lang=\"zh\"><head><meta charset=\"UTF-8\"/><title>${xml(title)}</title><style>body{line-height:1.8;padding:1em}p{white-space:pre-wrap}img{max-width:100%;height:auto}aside{border-left:2px solid #789;padding-left:1em;margin:1em 0}</style></head><body>$body</body></html>"
    private class ReadTextBuilder {
        private val value = StringBuilder()
        private var bytes = 0L
        fun append(text: String): ReadTextBuilder {
            bytes += text.toByteArray(Charsets.UTF_8).size
            require(bytes <= READ_BACKUP_MAX_BYTES) { "导出文字超过 64 MB，请减少札记后重试" }
            value.append(text)
            return this
        }
        fun append(value: Char): ReadTextBuilder = append(value.toString())
        override fun toString() = value.toString()
    }
    private fun zip(files: Map<String, ByteArray>, epub: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // A shallow level also keeps valid repetitive source text below the importer's ratio cap.
            zip.setLevel(1)
            files.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (epub && name == "mimetype") {
                    entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
                require(out.size() <= READ_BACKUP_MAX_BYTES) { "导出文件超过 64 MB" }
            }
        }
        return out.toByteArray().also { require(it.size <= READ_BACKUP_MAX_BYTES) { "导出文件超过 64 MB" } }
    }
}
