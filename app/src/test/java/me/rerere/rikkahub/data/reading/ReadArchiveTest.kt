package me.rerere.rikkahub.data.reading

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.nio.file.Files

class ReadArchiveTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6SAAAAABJRU5ErkJggg==")
    private val imageId = readSha256(png)
    private val book = ReadBook("a".repeat(64), "书 <script>& 标题", "作者", "epub",
        listOf(ReadChapter("章 <一>", listOf("原文 & <img src=x onerror=bad>", "插图"), mapOf(1 to imageId))),
        importedAt = 1, images = mapOf(imageId to ReadImage(imageId, "image/png", Base64.getEncoder().encodeToString(png), "插图")), coverImageId = imageId)
    private val annotation = ReadAnnotation("note", book.id, 0, 0, 0, 2, "原文", "<script>批注</script>", "chat", "assistant", 2,
        listOf(ReadReply("回应 & <iframe>", "assistant", 3)))
    private val journal = ReadRecord("journal", book.id, "chat", "assistant", "journal", 2, text = "札记 <svg onload=x>")
    private fun backup(value: ReadBook = book) = ReadBackup(createdAt = 3, books = listOf(value), catalog = listOf(value.entry()), annotations = listOf(annotation), records = listOf(journal), settings = ReadSettings())

    @Test fun zipRoundTripPreservesEveryReadingFieldAndRawImageWithoutConsent() {
        val source = backup().copy(catalog = listOf(book.entry(ReadProgress(0, 0, 2))))
        val archive = ReadArchive.encode(source)
        assertEquals(source, ReadArchive.decode(archive))
        val files = unzip(archive)
        assertArrayEquals(png, files["images/${book.id}/$imageId.png"])
        val metadata = files.getValue("backup.json").toString(Charsets.UTF_8)
        assertFalse(metadata.contains(book.images.getValue(imageId).data))
        assertFalse(metadata.contains("authorized"))
        assertFalse(metadata.contains("active"))
    }

    @Test fun legacyTextOnlyBooksRoundTripWithoutImageFields() {
        val old = book.copy(images = emptyMap(), coverImageId = null, chapters = listOf(ReadChapter("旧书", listOf("原文"))))
        val source = backup(old).copy(records = emptyList())
        assertEquals(source, ReadArchive.decode(ReadArchive.encode(source)))
    }

    @Test fun modifiedMissingExtraTruncatedAndUnsafeArchivesFailBeforeRestore() {
        val valid = ReadArchive.encode(backup())
        val files = unzip(valid)
        failure { ReadArchive.decode(zip(files + ("backup.json" to "{}".toByteArray()))) }
        failure { ReadArchive.decode(zip(files - "images/${book.id}/$imageId.png")) }
        failure { ReadArchive.decode(zip(files + ("unexpected.txt" to byteArrayOf(1)))) }
        failure { ReadArchive.decode(valid.copyOf(valid.size - 22)) }
        failure { ReadArchive.decode(zip(files + ("../outside" to byteArrayOf(1)))) }
        failure { ReadArchive.decode("no zip".toByteArray()) }
    }

    @Test fun hashValidButSourceInvalidBackupCannotPassSemanticValidation() {
        val bad = backup().copy(annotations = listOf(annotation.copy(quote = "伪造")))
        val raw = Json { encodeDefaults = true }.encodeToString(
            bad.copy(books = listOf(book.copy(images = book.images.mapValues { it.value.copy(data = "") })))
        ).toByteArray()
        val files = unzip(ReadArchive.encode(backup())).toMutableMap().apply {
            this["backup.json"] = raw
            val digests = (this - "manifest.json").mapValues { readSha256(it.value) }
            this["manifest.json"] = ("{\"version\":1,\"files\":" + Json.encodeToString(digests) + "}").toByteArray()
        }
        failure { ReadArchive.decode(zip(files)) }
    }

    @Test fun markdownEscapesSourceNotesAndJournalsWhileKeepingSourceImages() {
        val text = ReadArchive.markdown(book, listOf(annotation), listOf(journal))
        assertTrue(text.contains("data:image/png;base64," + book.images.getValue(imageId).data))
        assertTrue(text.contains("&lt;script&gt;批注&lt;/script&gt;"))
        assertTrue(text.contains("札记 &lt;svg onload=x&gt;"))
        assertFalse(text.contains("<img src=x"))
        assertFalse(text.contains("<iframe>"))
    }

    @Test fun epubIsReflowableHasFirstStoredMimetypeAndEscapedAnnotatedXhtml() {
        val bytes = ReadArchive.epub(book, listOf(annotation), listOf(journal))
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val first = zip.nextEntry
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertEquals("application/epub+zip", zip.readBytes().toString(Charsets.UTF_8))
        }
        val files = unzip(bytes)
        assertArrayEquals(png, files["EPUB/images/$imageId.png"])
        val chapter = files.getValue("EPUB/chapter-1.xhtml").toString(Charsets.UTF_8)
        val doc = Jsoup.parse(chapter, "", Parser.xmlParser())
        assertTrue(doc.select("script, iframe").isEmpty())
        assertEquals(2, doc.select("img").size)
        assertTrue(doc.text().contains("<script>批注</script>"))
        assertTrue(files.getValue("EPUB/reading-notes.xhtml").toString(Charsets.UTF_8).contains("札记 &lt;svg onload=x&gt;"))
        val opf = files.getValue("EPUB/package.opf").toString(Charsets.UTF_8)
        assertTrue(opf.contains("properties=\"cover-image\""))
        assertFalse(opf.contains("<script>"))
    }

    @Test fun repetitiveBookBackupsRoundTripAndNewReadingSettingsArePortable() {
        val repeated = book.copy(chapters = listOf(ReadChapter("长章", List(1000) { "月".repeat(2000) })), images = emptyMap(), coverImageId = null)
        val source = backup(repeated).copy(annotations = emptyList(), records = emptyList(), settings = ReadSettings(theme = "bluecard-night", mode = "spread"))
        assertEquals(source, ReadArchive.decode(ReadArchive.encode(source)))
    }

    @Test fun generatedEpubCanBeImportedWithOriginalImagesAndEscapedSource() {
        val bytes = ReadArchive.epub(book, listOf(annotation), listOf(journal))
        val path = Files.createTempFile("read-export-", ".epub")
        try {
            Files.write(path, bytes)
            val imported = ReadImporter.parse(path.toFile(), "export.epub")
            assertEquals(book.title, imported.title)
            assertEquals(book.coverImageId, imported.coverImageId)
            assertEquals(book.images.keys, imported.images.keys)
            assertTrue(imported.chapters.flatMap { it.paragraphs }.any { it == book.chapters[0].paragraphs[0] })
            assertTrue(imported.chapters.flatMap { it.paragraphs }.any { it.contains("<script>批注</script>") })
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun oversizedMarkdownIsRejectedWhileAppendingRatherThanAllocatingUnboundedOutput() {
        val long = book.copy(chapters = listOf(ReadChapter("大书", List(5000) { "<".repeat(2000) })), images = emptyMap(), coverImageId = null)
        val journal = this.journal.copy(text = "<".repeat(4000))
        val records = List(2000) { journal.copy(id = "j$it") }
        failure { ReadArchive.markdown(long, emptyList(), records) }
    }

    private fun unzip(bytes: ByteArray) = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(ByteArrayInputStream(bytes)).use { input -> while (true) { val entry = input.nextEntry ?: break; this[entry.name] = input.readBytes(); input.closeEntry() } }
    }
    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun failure(block: () -> Any?) { assertNotNull(runCatching(block).exceptionOrNull()) }
}
