package me.rerere.rikkahub.data.reading

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReadImagesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6SAAAAABJRU5ErkJggg==")

    @Test fun epubCoverAndInlineImagesUseLocalBytesAndStableParagraphPositions() {
        val book = epub("<p>前文。</p><img src='../Images/one.png' alt='月光'/><p>后文。</p>")
        val chapter = book.chapters.single()
        assertEquals(listOf("前文。", "月光", "后文。"), chapter.paragraphs)
        assertEquals(readSha256(png), chapter.images[1])
        assertEquals(book.coverImageId, chapter.images[1])
        assertEquals(1, book.images.size)
        assertArrayEquals(png, readImageBytes(book.images.values.single()))
    }

    @Test fun imageOnlyChapterIsKeptAndUsesAnOrdinaryTextCoordinate() {
        val book = epub("<img src='../Images/one.png'/>")
        assertEquals(listOf("插图"), book.chapters.single().paragraphs)
        assertEquals(mapOf(0 to readSha256(png)), book.chapters.single().images)
    }

    @Test fun externalDataAndEscapedPseudoImagesNeverCreateResources() {
        val book = epub("<p>&lt;img src='../Images/one.png'&gt; [[image:one]]</p><img src='https://127.0.0.1:9/never' alt='远图'/><img src='data:image/png;base64,AAAA' alt='内嵌'/>" , cover = false)
        assertTrue(book.images.isEmpty())
        assertTrue(book.chapters.single().images.isEmpty())
        assertTrue(book.chapters.single().paragraphs.joinToString().contains("<img src="))
        assertTrue(book.chapters.single().paragraphs.joinToString().contains("远图"))
    }

    @Test fun unsafeLocalImagePathAndFalseMimeOrExcessiveResolutionAreRejected() {
        failure { epub("<img src='../../../outside.png'/>", cover = false) }
        failure { epub("<img src='../Images/one.png'/>", image = "<script>bad</script>".toByteArray()) }
        val huge = png.copyOf().also { it[16] = 0x7f; it[17] = 0xff.toByte(); it[18] = 0xff.toByte(); it[19] = 0xff.toByte() }
        failure { epub("<img src='../Images/one.png'/>", image = huge) }
    }

    @Test fun validationRejectsDigestMimeMissingResourcesAndInvalidCoordinates() {
        val book = epub("<img src='../Images/one.png'/>")
        val id = book.images.keys.single()
        failure { validateReadImages(book.copy(images = mapOf(id to book.images.getValue(id).copy(data = "AAAA")))) }
        failure { validateReadImages(book.copy(images = mapOf(id to book.images.getValue(id).copy(mimeType = "image/svg+xml")))) }
        failure { validateReadImages(book.copy(coverImageId = "missing")) }
        failure { validateReadImages(book.copy(chapters = listOf(book.chapters.single().copy(images = mapOf(8 to id))))) }
    }

    @Test fun recognizedRasterHeadersHaveBoundedDimensionsWithoutAndroidDecoder() {
        val gif = "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, 0, 0, 0)
        validateReadImageBytes(gif, "image/gif")
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0, 8, 8, 0, 1, 0, 1, 0)
        validateReadImageBytes(jpeg, "image/jpeg")
        val webp = ByteArray(30).apply {
            "RIFF".toByteArray().copyInto(this); "WEBP".toByteArray().copyInto(this, 8); "VP8X".toByteArray().copyInto(this, 12)
        }
        validateReadImageBytes(webp, "image/webp")
        failure { validateReadImageBytes(png.copyOf(10), "image/png") }
        failure { validateReadImageBytes(ByteArray(READ_IMAGE_MAX_BYTES + 1), "image/png") }
    }

    private fun epub(body: String, image: ByteArray = png, cover: Boolean = true): ReadBook {
        val files = linkedMapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OEBPS/package.opf'/></rootfiles></container>".toByteArray(),
            "OEBPS/package.opf" to ("<package><metadata/><manifest><item id='a' href='Text/a.xhtml' media-type='application/xhtml+xml'/><item id='cover' href='Images/one.png' media-type='image/png' ${if (cover) "properties='cover-image'" else ""}/></manifest><spine><itemref idref='a'/></spine></package>").toByteArray(),
            "OEBPS/Text/a.xhtml" to "<html><body>$body</body></html>".toByteArray(),
            "OEBPS/Images/one.png" to image,
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        return ReadImporter.parse(temporary.newFile().apply { writeBytes(out.toByteArray()) }, "图片.epub")
    }
    private fun failure(block: () -> Any?) { assertNotNull(runCatching(block).exceptionOrNull()) }
}
