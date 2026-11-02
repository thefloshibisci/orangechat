package me.rerere.rikkahub.data.gongdu

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.readAssistantMessageIds
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GongduCodecTest {
    @Test fun automaticMcpDiscoveryRequiresApprovalBeforeExposingNewPublicActions() {
        val managed = Uuid.random()
        assertTrue(GongduRepository.initialApproval(managed, managed, "publish_content"))
        assertTrue(GongduRepository.initialApproval(managed, managed, "reply_notification"))
        assertFalse(GongduRepository.initialApproval(managed, managed, "get_feed_detail"))
        assertFalse(GongduRepository.initialApproval(managed, Uuid.random(), "publish_content"))
    }
    @Test fun feedResultRetainsTheTokenAndCoverRequiredForTheNextRealCall() {
        val result = GongduCodec.feeds(GongduCodec.payload("搜索成功\n" + """{"data":{"feeds":[{"id":"note-1","xsecToken":"session-token","noteCard":{"displayTitle":"一只猫","user":{"nickname":"作者"},"cover":{"urlDefault":"https://example.test/cat.jpg"}}}]}}"""))
        assertEquals(GongduFeed("note-1", "一只猫", "session-token", "作者", "https://example.test/cat.jpg"), result.single())
    }
    @Test fun malformedOrPlainSuccessMustNotPretendToBeAFeedList() {
        assertTrue(runCatching { GongduCodec.payload("发布成功") }.isFailure)
        assertTrue(runCatching { GongduCodec.feeds(buildJsonObject { put("success", true) }) }.isFailure)
    }
    @Test fun detailRetainsRawVideoDataAndFallbackAccessToken() {
        val raw = Json.parseToJsonElement("""{"note":{"noteId":"n","title":"视频","desc":"正文","video":{"media":{"stream":{"h264":[{"masterUrl":"https://v.xhscdn.com/v.mp4","size":5}]}}}}} """)
        val note = GongduCodec.note(raw, GongduFeed("n", "原题", "token"))
        assertEquals("token", note.feed.token)
        assertEquals("正文", note.content)
        assertEquals("https://v.xhscdn.com/v.mp4", GongduVideoReader.source(note.raw).url)
    }
    @Test fun firstNotificationSyncSeedsHistoryAndSameCountNewIdStillWakes() {
        val baseline = GongduCodec.mergeInbox(GongduInbox(), listOf(GongduEvent("old", "旧评论")), 1)
        assertTrue(baseline.events.single().acknowledged)
        val next = GongduCodec.mergeInbox(baseline, listOf(GongduEvent("new", "新评论")), 2)
        assertEquals(listOf("new"), next.events.filterNot { it.acknowledged }.map { it.id })
        val repeated = GongduCodec.mergeInbox(next, listOf(GongduEvent("new", "新评论")), 3)
        assertEquals(2, repeated.events.size)
    }
    @Test fun pendingNotificationsSurvivePersistenceAndHistoryPruning() {
        val events = (0..255).map { GongduEvent("$it", "$it", acknowledged = it != 0) }
        val before = GongduSetting(inbox = GongduInbox(true, events))
        val persisted = Json.decodeFromString<GongduSetting>(Json.encodeToString(before))
        val after = GongduCodec.mergeInbox(persisted.inbox, listOf(GongduEvent("fresh", "新消息")), 2)
        assertTrue(after.events.any { it.id == "0" && !it.acknowledged })
        assertTrue(after.events.any { it.id == "fresh" && !it.acknowledged })
        assertEquals(256, after.events.size)
    }
    @Test fun tooManyPendingEventsFailWithoutDiscardingUnacknowledgedEntries() {
        val before = GongduInbox(true, (0..255).map { GongduEvent("$it", "$it") })
        assertTrue(runCatching { GongduCodec.mergeInbox(before, listOf(GongduEvent("new", "new")), 1) }.isFailure)
        assertEquals(256, before.events.size)
    }
    @Test fun syntheticDoorbellIsNotEvidenceThatThePersonReadAnAssistantReply() {
        val marker = GongduDoorbell.marker("notification")
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("等你看")))
        val event = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("新评论", metadata = buildJsonObject { put("gongdu_event_id", marker) })))
        assertTrue(readAssistantMessageIds(listOf(reply, event)).isEmpty())
        assertTrue(GongduDoorbell.hasReceipt(event.parts, marker))
        assertFalse(GongduDoorbell.hasReceipt(event.parts, GongduDoorbell.marker("another")))
    }
    @Test fun endpointRejectsEmbeddedCredentialsAndDoesNotPutTokensInDiagnostics() {
        assertTrue(runCatching { GongduRepository.validateEndpoint("file:///etc/passwd") }.isFailure)
        assertTrue(runCatching { GongduRepository.validateEndpoint("https://user:pass@example.test/mcp") }.isFailure)
        assertTrue(runCatching { GongduRepository.validateEndpoint("https://example.test/mcp?token=secret") }.isFailure)
        assertEquals("http://127.0.0.1:18060/mcp", GongduRepository.validateEndpoint("http://127.0.0.1:18060/mcp"))
        assertFalse(GongduConfiguration("https://example.test/mcp", "secret").toString().contains("secret"))
    }
    @Test fun sampledFramesCoverTheActualStartAndEndWithoutDuplicateTimestamps() {
        assertEquals(listOf(0L, 199L, 399L, 599L, 799L, 999L), GongduVideoReader.sampleTimes(1000))
        assertEquals(listOf(0L), GongduVideoReader.sampleTimes(1))
    }
    @Test fun mediaFetchIsRestrictedToTheVideoCdnAndNeverTreatsADocumentAsVideo() {
        assertTrue(runCatching { GongduVideoReader.checkedUrl("https://evil.test/video.mp4") }.isFailure)
        assertTrue(runCatching { GongduVideoReader.checkedUrl("http://127.0.0.1/video.mp4") }.isFailure)
        assertTrue(runCatching { GongduVideoReader.source(buildJsonObject { put("note", buildJsonObject { put("title", "图文") }) }) }.isFailure)
        assertEquals("https", GongduVideoReader.checkedUrl("http://v.xhscdn.com/v.mp4?sign=signed").scheme)
    }
    @Test fun videoSelectionPrefersSmallCompatibleStreamAndKeepsAvailableSubtitles() {
        val raw = Json.parseToJsonElement("""{"note":{"video":{"media":{"stream":{"h264":[{"masterUrl":"https://v.xhscdn.com/large.mp4","size":99},{"masterUrl":"https://v.xhscdn.com/small.mp4","size":9}],"h265":[{"masterUrl":"https://v.xhscdn.com/hevc.mp4","size":1}]}},"subtitles":{"zh":[{"text":"你好"},{"text":"世界"}]}}}}""").jsonObject
        val source = GongduVideoReader.source(raw)
        assertTrue(source.url.endsWith("small.mp4"))
        assertEquals("你好\n世界", source.subtitles)
    }
    @Test fun upstreamSubtitleFileIsResolvedAndTimestampLinesAreNotReadAsDialogue() {
        val raw = Json.parseToJsonElement("""{"note":{"video":{"media":{"stream":{"h264":[{"masterUrl":"https://v.xhscdn.com/v.mp4"}]}},"subtitles":{"zh":[{"url":"https://v.xhscdn.com/zh.srt","language":"zh"}]}}}}""").jsonObject
        assertEquals("https://v.xhscdn.com/zh.srt", GongduVideoReader.source(raw).subtitleUrl)
        assertEquals("你好\n世界", GongduVideoReader.subtitleText("1\n00:00:01,000 --> 00:00:02,000\n<b>你好</b>\n\n2\n00:00:02,000 --> 00:00:03,000\n世界"))
    }
}
