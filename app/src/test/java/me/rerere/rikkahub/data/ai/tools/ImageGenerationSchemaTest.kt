package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageGenerationSchemaTest {
    @Test
    fun `prompt is a JSON schema object rather than a string`() {
        val schema = imageGenerationInputSchema()
        assertEquals(listOf("prompt"), schema.required)
        assertEquals("string", schema.properties["prompt"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
}
