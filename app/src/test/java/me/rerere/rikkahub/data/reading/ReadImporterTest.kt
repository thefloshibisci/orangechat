package me.rerere.rikkahub.data.reading

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReadImporterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun utf8ImportKeepsShortOpeningAndUsesFileContentIdentity() {
        val raw = "这是一句很短的开篇。\n第一章 春夜\n她推开门，看见了月光。\n第二章 来信\n信上写着：你好。".toByteArray()
        val book = text(raw)
        assertEquals("测试书", book.title)
        assertEquals("txt", book.format)
        assertEquals(listOf("开篇", "第一章 春夜", "第二章 来信"), book.chapters.map { it.title })
        assertEquals(listOf("这是一句很短的开篇。"), book.chapters[0].paragraphs)
        assertEquals(listOf("信上写着：你好。"), book.chapters[2].paragraphs)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }, book.id)
        assertEquals(book.id, text(raw, "另一个名字.txt").id)
    }

    @Test fun utf16BomIsDecodedWithoutTreatingShortAsciiAsChinese() {
        val source = "Chapter 1 First\nHello, world!\nChapter 2 Next\nGood night."
        for ((charset, bom) in listOf("UTF-16LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            "UTF-16BE" to byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) {
            val book = text(bom + source.toByteArray(Charset.forName(charset)))
            assertEquals(listOf("Chapter 1 First", "Chapter 2 Next"), book.chapters.map { it.title })
            assertEquals("Good night.", book.chapters.last().paragraphs.single())
        }
    }

    @Test fun gb18030AndBig5ChineseRemainReadable() {
        val simplified = "第一章 春夜\n她推开门，看见了月光。我们一起读这本书。\n第二章 来信\n你说：今天的天气很好。"
        assertEquals("她推开门，看见了月光。我们一起读这本书。",
            text(simplified.toByteArray(Charset.forName("GB18030"))).chapters.first().paragraphs.single())
        val traditional = "第一章 春夜\n她推開門，看見了月光。我們一起讀這本書。\n第二章 來信\n你說：今天的天氣很好。"
        assertEquals("她推開門，看見了月光。我們一起讀這本書。",
            text(traditional.toByteArray(Charset.forName("Big5"))).chapters.first().paragraphs.single())
    }

    @Test fun noHeadingFallbackAndLongParagraphsNeverLoseTextOrSplitEmoji() {
        val long = "月光🌙".repeat(2800)
        val book = text(long.toByteArray())
        val paragraphs = book.chapters.flatMap { it.paragraphs }
        assertTrue(book.chapters.size >= 2)
        assertTrue(paragraphs.all { it.length <= 2000 })
        assertEquals(long, paragraphs.joinToString(""))
        assertTrue(paragraphs.none { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) })
    }

    @Test fun emptyBodyChapterTitlesArePreserved() {
        val book = text("第一章 前门\n第二章 后门\n她在门后等。".toByteArray())
        assertEquals("第一章 前门", book.chapters.first().paragraphs.single())
        assertEquals("她在门后等。", book.chapters.last().paragraphs.single())
    }

    @Test fun emptyBinaryAndMalformedBomFilesAreRejected() {
        assertFailure { text(" \r\n ".toByteArray()) }
        assertFailure { text(byteArrayOf(0, 1, 2, 3, 0, 0)) }
        assertFailure { text(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x01)) }
        assertFailure { text("hello".toByteArray(), "book.pdf") }
    }

    @Test fun rawFileSizeLimitIsEnforcedBeforeReading() {
        val file = temporary.newFile("large.txt")
        java.io.RandomAccessFile(file, "rw").use { it.setLength(ReadImporter.MAX_BYTES + 1L) }
        assertFailure { ReadImporter.parse(file, file.name) }
    }

    @Test fun epubUsesSpineOrderRelativePathsAndMetadataRatherThanZipOrder() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata><dc:title>月光书</dc:title><dc:creator>合成作者</dc:creator></metadata>
              <manifest><item id="a" href="../Text/first%20chapter.xhtml" media-type="application/xhtml+xml"/>
                <item id="b" href="../Text/next.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="b"/><itemref idref="a"/></spine>
            </package>""".trimIndent()
        val book = epub(opf, mapOf(
            "Text/first chapter.xhtml" to "<html><head><title>第一章</title></head><body><p>先来的文字。</p></body></html>",
            "Text/next.xhtml" to "<html><body><h1>第二章</h1><p>后来的文字。</p></body></html>",
        ))
        assertEquals("月光书", book.title)
        assertEquals("合成作者", book.author)
        assertEquals("epub", book.format)
        assertEquals(listOf("第二章", "第一章"), book.chapters.map { it.title })
        assertEquals("后来的文字。", book.chapters[0].paragraphs.last())
        assertEquals("先来的文字。", book.chapters[1].paragraphs.single())
    }

    @Test fun epubTextDecodesEntitiesAndDropsActiveAndRemoteElements() {
        val content = """<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://127.0.0.1:9/never.dtd">
            <html><head><title>正文</title><style>STYLES_SECRET</style></head><body>
            <nav><h1>目录标题</h1>NAV_SECRET</nav><h1>第一章</h1>
            <p>你 &amp; 我<br/>在这里。</p><script>SCRIPT_SECRET</script>
            <iframe src="http://127.0.0.1:9/never">IFRAME_SECRET</iframe>
            <img src="https://127.0.0.1:9/never" alt="插画说明"/>
            </body></html>""".trimIndent()
        val book = oneChapterEpub(content)
        val body = book.chapters.single().paragraphs.joinToString("\n")
        assertEquals("第一章", book.chapters.single().title)
        assertTrue(body.contains("你 & 我"))
        assertTrue(body.contains("在这里。"))
        assertTrue(body.contains("插画说明"))
        assertFalse(body.contains("SECRET"))
        assertFalse(body.contains("127.0.0.1"))
    }

    @Test fun epubWithoutSpineOrWithMissingChapterCannotBecomeEmptySuccess() {
        assertFailure { epub("<package><manifest/></package>", emptyMap()) }
        assertFailure { epub(packageXml("missing.xhtml"), emptyMap()) }
        val file = temporary.newFile("bad.epub").apply { writeText("not a ZIP") }
        assertFailure { ReadImporter.parse(file, file.name) }
    }

    @Test fun epubRejectsExternalAndEscapingChapterPaths() {
        for (href in listOf("https://example.com/book.xhtml", "../../../secret.xhtml", "file:///secret.xhtml", "%2Fsecret.xhtml")) {
            assertFailure { epub(packageXml(href), emptyMap()) }
        }
        assertFailure { epub(packageXml("a.xhtml"), mapOf("../escape.xhtml" to "secret", "OEBPS/a.xhtml" to "<p>text</p>")) }
    }

    @Test fun epubRejectsDrmAndXmlEntityDeclarations() {
        assertFailure { epub(packageXml("a.xhtml"), mapOf(
            "OEBPS/a.xhtml" to "<p>visible</p>", "META-INF/rights.xml" to "<rights/>",
        )) }
        assertFailure { epub("<!DOCTYPE package [<!ENTITY steal SYSTEM 'file:///etc/passwd'>]>" + packageXml("a.xhtml"),
            mapOf("OEBPS/a.xhtml" to "<p>&steal;</p>")) }
        assertFailure { epub(packageXml("a.xhtml"), mapOf(
            "OEBPS/a.xhtml" to "<p>visible</p>",
            "META-INF/encryption.xml" to "<encryption><EncryptionMethod Algorithm='aes'/><CipherReference URI='OEBPS/a.xhtml'/></encryption>",
        )) }
    }

    @Test fun epubRejectsBombLikeCompressionAndTooManyArchiveEntries() {
        assertFailure { oneChapterEpub("<html><body><p>" + "月".repeat(800_000) + "</p></body></html>") }
        val extras = (0..4000).associate { "f$it.txt" to "x" }
        assertFailure { epub(packageXml("a.xhtml"), extras) }
    }

    @Test fun imageOnlyEpubExplainsThatNoTextWasFound() {
        assertFailure { oneChapterEpub("<html><body><img src='scan.jpg'/></body></html>") }
    }

    private fun text(bytes: ByteArray, filename: String = "测试书.txt"): ReadBook {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        return ReadImporter.parse(file, filename)
    }

    private fun packageXml(href: String) = """<package xmlns="http://www.idpf.org/2007/opf">
        <metadata/><manifest><item id="a" href="$href" media-type="application/xhtml+xml"/></manifest>
        <spine><itemref idref="a"/></spine></package>""".trimIndent()

    private fun oneChapterEpub(content: String) = epub(packageXml("a.xhtml"), mapOf("OEBPS/a.xhtml" to content))

    private fun epub(opf: String, extras: Map<String, String>): ReadBook {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            val files = linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "OEBPS/package.opf" to opf,
            ) + extras
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        return ReadImporter.parse(file, "合成测试.epub")
    }

    private fun assertFailure(block: () -> Any?) {
        assertTrue("Invalid input must fail with an actionable message", runCatching(block).exceptionOrNull()?.message?.isNotBlank() == true)
    }
}
