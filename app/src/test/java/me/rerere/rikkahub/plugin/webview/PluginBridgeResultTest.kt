package me.rerere.rikkahub.plugin.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBridgeResultTest {
    @Test fun callbackIdAndNestedValuesAreEncodedAsJsonData() {
        val callback = "cb'); alert(1); //\n"
        val json = """{"path":"C:\\Users\\me\\quote'\nfile.json","flags":[true,7]}"""

        val script = pluginBridgeJsonJavascript(callback, json)

        assertTrue(script.startsWith("window.__bridgeResult(\"cb'); alert(1); //\\n\", JSON.parse("))
        assertFalse(script.contains("window.__bridgeResult('$callback'"))
        assertFalse(script.contains("C:\\Users\\me\\quote'"))
        assertTrue(script.contains("\\\"path\\\""))
        assertTrue(script.contains("\\\"flags\\\":[true,7]"))
    }

    @Test fun jsonResultsKeepTheirOriginalTypesAndEscapeLineSeparators() {
        val script = pluginBridgeJsonJavascript("cb", "[true,7,\"line\u2028separator\"]")

        assertTrue(script.contains("JSON.parse("))
        assertTrue(script.contains("\\u2028"))
        assertFalse(script.contains("\u2028"))
    }

    @Test fun hookNamesAreValidatedAndContextIsPassedAsJsonData() {
        val context = "\" ); alert(1); //"

        val script = pluginHookJavascript("onPageTurn", context)

        assertTrue(script!!.contains("window[\"onPageTurn\"]"))
        assertTrue(script.contains("hook(\"\\\" ); alert(1); //\")"))
        assertFalse(script.contains("window.onPageTurn"))
        assertFalse(script.contains("window[\"onPageTurn\"] ="))
        assertFalse(pluginHookJavascript("onPageTurn; alert(1)", context) != null)
    }
}
