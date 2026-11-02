package me.rerere.rikkahub.data.gongdu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class GongduSubtitlesTest {
    private fun select(json: String) = GongduSubtitles.select(Json.parseToJsonElement(json).jsonObject)
    private val overview = gongduVideoPlan(60_000)
    private val window = gongduVideoPlan(60_000, GongduVideoRequest(10.0, 20.0))

    @Test fun prefersChineseAndKeepsOneLanguageInsteadOfConcatenatingTranslations() {
        val track = select("""{"subtitles":{"en":[{"text":"Hello"}],"zh-CN":[{"text":"你好"},{"text":"世界"}]}}""")
        assertEquals("zh", track.language)
        assertEquals("你好\n世界", track.text)
        assertFalse(track.text.contains("Hello"))
    }

    @Test fun chineseMetadataCanOverrideGenericGroupNameAndSelectItsOwnUrl() {
        val track = select("""{"subtitles":{"tracks":[{"language":"en","url":"https://v.xhscdn.com/en.srt"},{"language":"zh_CN","url":"https://v.xhscdn.com/zh.srt"}]}}""")
        assertEquals("zh", track.language)
        assertEquals("https://v.xhscdn.com/zh.srt", track.url)
    }

    @Test fun onlyEnglishIsLabelledAsPlatformEnglishAndNeverFabricatesChineseOrASR() {
        val track = select("""{"subtitles":{"en":[{"text":"Welcome to my day"}]}}""")
        val message = GongduSubtitles.message(track, track.text, overview)
        assertTrue(message.contains("英文；平台未提供可用中文字幕"))
        assertTrue(message.contains("不代表已听过原声"))
        assertTrue(message.contains("Welcome to my day"))
        assertFalse(message.contains("音轨对白转写"))
    }

    @Test fun declaredOriginalLanguageIsPreferredWhenChineseIsAbsent() {
        val track = select("""{"original_language":"ja","subtitles":{"en":[{"text":"Hello"}],"ja":[{"text":"こんにちは"}]}}""")
        assertEquals("ja", track.language)
        assertTrue(track.original)
        assertEquals("こんにちは", track.text)
    }

    @Test fun unknownLanguageDoesNotClaimChineseOrEnglish() {
        val track = select("""{"subtitles":{"tracks":[{"text":"Hello"}]}}""")
        val message = GongduSubtitles.message(track, track.text, overview)
        assertTrue(message.contains("语言未标注"))
        assertFalse(message.contains("中文字幕"))
    }

    @Test fun srtWindowIncludesOnlyOverlappingCuesAndDoesNotRepeatWholeVideo() {
        val captions = """
            1
            00:00:01,000 --> 00:00:02,000
            Before

            2
            00:00:09,000 --> 00:00:11,000
            Overlaps start

            3
            00:00:15,000 --> 00:00:16,000
            In window

            4
            00:00:20,000 --> 00:00:22,000
            After
        """.trimIndent()
        val message = GongduSubtitles.message(GongduSubtitleTrack(language = "en"), captions, window)
        assertTrue(message.contains("10.0～20.0秒片段"))
        assertTrue(message.contains("Overlaps start"))
        assertTrue(message.contains("In window"))
        assertFalse(message.contains("Before"))
        assertFalse(message.contains("After"))
        assertFalse(message.contains("-->"))
    }

    @Test fun vttSupportsMinuteTimestampsCueSettingsAndNumericDialogue() {
        val captions = "WEBVTT\n\ncue-id\n00:12.000 --> 00:14.000 align:start position:0%\n<b>121.7</b>\nsecond line\n\n00:30.000 --> 00:32.000\nLater"
        val message = GongduSubtitles.message(GongduSubtitleTrack(language = "en"), captions, window)
        assertTrue(message.contains("121.7\nsecond line"))
        assertFalse(message.contains("cue-id"))
        assertFalse(message.contains("Later"))
        assertFalse(message.contains("align:start"))
    }

    @Test fun untimedWholeVideoCaptionsAreNotRepeatedForEveryWindow() {
        val message = GongduSubtitles.message(GongduSubtitleTrack(language = "en"), "long unrelated full-video text".repeat(500), window)
        assertTrue(message.contains("没有可用时间戳"))
        assertFalse(message.contains("long unrelated"))
        assertTrue(message.length < 200)
    }

    @Test fun overviewAndWindowHaveSeparateBudgetsAndExplicitTruncationNotices() {
        val longText = "甲".repeat(10_000)
        val overviewMessage = GongduSubtitles.message(GongduSubtitleTrack(language = "zh"), longText, overview)
        val windowMessage = GongduSubtitles.message(
            GongduSubtitleTrack(language = "zh"),
            "1\n00:00:11,000 --> 00:00:12,000\n$longText", window,
        )
        assertEquals(GongduSubtitles.OVERVIEW_LIMIT, overviewMessage.count { it == '甲' })
        assertEquals(GongduSubtitles.WINDOW_LIMIT, windowMessage.count { it == '甲' })
        assertTrue(overviewMessage.contains("未包含全部对白"))
        assertTrue(windowMessage.contains("未包含全部对白"))
    }

    @Test fun noMatchingWindowCueAndMissingCaptionDoNotClaimNoAudio() {
        val message = GongduSubtitles.message(
            GongduSubtitleTrack(language = "zh"),
            "1\n00:00:01,000 --> 00:00:02,000\n开头", window,
        )
        assertTrue(message.contains("该范围没有可用字幕条目；不代表没有声音"))
        assertTrue(GongduSubtitles.message(GongduSubtitleTrack(), "", overview).contains("后续音轨状态"))
    }
}
