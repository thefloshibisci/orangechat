package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ImageAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageGenerationSchemaTest {
    @Test
    fun `prompt is a JSON schema object rather than a string`() {
        val schema = imageGenerationInputSchema()
        assertEquals(listOf("prompt"), schema.required)
        assertEquals("string", schema.properties["prompt"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `aspect ratio is optional and offers the three supported ratios`() {
        val ratio = imageGenerationInputSchema().properties["aspect_ratio"]!!.jsonObject
        assertEquals(listOf("1:1", "16:9", "9:16"), ratio["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `model ratio wins and missing or unknown values use the saved default`() {
        val default = ImageAspectRatio.PORTRAIT
        assertEquals(ImageAspectRatio.LANDSCAPE, resolveImageAspectRatio("16:9", default))
        assertEquals(ImageAspectRatio.SQUARE, resolveImageAspectRatio(" Square ", default))
        assertEquals(default, resolveImageAspectRatio(null, default))
        assertEquals(default, resolveImageAspectRatio("", default))
        assertEquals(default, resolveImageAspectRatio("4:3", default))
    }
}
