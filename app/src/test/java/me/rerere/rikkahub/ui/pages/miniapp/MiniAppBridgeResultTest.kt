package me.rerere.rikkahub.ui.pages.miniapp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniAppBridgeResultTest {
    @Test fun `popup identifiers round trip as data rather than javascript syntax`() {
        val callback = "cb');throw Error('oops');//\n\\\u0000"
        val button = "it's a \"button\"\n\\path\t"
        val script = miniAppBridgeResult(callback, buildJsonObject { put("buttonId", button) })
        val arguments = Json.parseToJsonElement("[" + script.removePrefix("window.__bridgeResult(")
            .removeSuffix(");") + "]").jsonArray
        assertEquals(callback, arguments[0].jsonPrimitive.content)
        assertEquals(button, arguments[1].jsonObject.getValue("buttonId").jsonPrimitive.content)
    }

    @Test fun `confirmation keeps boolean result type`() {
        assertEquals("window.__bridgeResult(\"cb1\", true);", miniAppBridgeResult("cb1", JsonPrimitive(true)))
        assertEquals("window.__bridgeResult(\"cb1\", false);", miniAppBridgeResult("cb1", JsonPrimitive(false)))
    }
}
