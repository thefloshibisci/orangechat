package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleFrameSettingTest {
    @Test fun legacySettingsKeepStickerFullyOpaque() {
        val frame = Json.decodeFromString<BubbleFrameSetting>("""{"path":"sticker.png","offsetX":4.0}""")
        assertEquals(1f, frame.opacity, 0f)
        assertEquals(4f, frame.offsetX, 0f)
    }

    @Test fun independentSideOpacitySurvivesSettingsRoundTrip() {
        val settings = DisplaySetting(
            userBubbleFrame = BubbleFrameSetting(path = "user.png", opacity = 0.25f),
            assistantBubbleFrame = BubbleFrameSetting(path = "assistant.png", opacity = 0.8f),
        )
        assertEquals(settings, Json.decodeFromString<DisplaySetting>(Json.encodeToString(settings)))
    }

    @Test fun outOfRangeAndNonFiniteOpacityCannotReachTheRenderer() {
        assertEquals(0f, BubbleFrameSetting(opacity = -1f).normalized().opacity, 0f)
        assertEquals(1f, BubbleFrameSetting(opacity = 2f).normalized().opacity, 0f)
        for (opacity in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1f, BubbleFrameSetting(opacity = opacity).normalized().opacity, 0f)
        }
        assertEquals(0.4f, BubbleFrameSetting(opacity = 0.4f).normalized().opacity, 0f)
    }
}
