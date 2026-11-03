package me.rerere.rikkahub.data.gongdu

import android.app.Application
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class GongduDetailResultTest {
    private val server = Uuid.random()
    private val assistant = Uuid.random()
    private fun fixture() = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
        "/gongdu/detail-with-images-comments.json")).readText()).jsonObject
    private fun compact(raw: JsonObject): JsonObject {
        val text = (GongduToolResultPolicy.compact(server, assistant, "get_feed_detail",
            listOf(UIMessagePart.Text(raw.toString()))).single() as UIMessagePart.Text).text
        assertTrue(text.length <= GongduToolResultPolicy.RESULT_LIMIT)
        return Json.parseToJsonElement(text).jsonObject
    }
    private fun readAll(ref: String, images: Boolean = false): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var offset = 0
        do {
            val parts = GongduToolResultPolicy.pageParts(server, assistant, ref, offset, 4, images)
            val page = Json.parseToJsonElement((parts.first() as UIMessagePart.Text).text).jsonObject
            assertTrue(page.toString().length <= GongduToolResultPolicy.RESULT_LIMIT)
            val rows = page["items"]!!.jsonArray.map { it.jsonObject }
            assertTrue(rows.isNotEmpty())
            if (images) {
                assertEquals(rows.map { it.str("url") }, parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
                assertTrue(parts.filterIsInstance<UIMessagePart.Image>().size <= 4)
            }
            all += rows
            offset = page["next_offset"]?.jsonPrimitive?.int ?: -1
        } while (offset >= 0)
        return all
    }
    @Test fun realDetailShapeRetainsAll18ImagesInOrderAndNestedComments() {
        val raw = fixture()
        val summary = compact(raw)
        assertEquals(18, summary.obj("images")!!["source_total"]!!.jsonPrimitive.int)
        val images = readAll(summary.obj("images")!!.str("result_ref"), true)
        assertEquals((1..18).toList(), images.map { it["image_index"]!!.jsonPrimitive.int })
        assertTrue(images.all { it.str("url").startsWith("https://sns-webpic-qc.xhscdn.com/") })
        val comments = readAll(summary.obj("comments")!!.str("result_ref"))
        val expected = mutableListOf<Pair<String, String>>()
        fun visit(rows: JsonArray, parent: String = "") { rows.forEach { row ->
            val obj = row.jsonObject
            expected += obj.str("id") to parent
            (obj["subComments"] as? JsonArray)?.let { visit(it, obj.str("id")) }
        } }
        visit(raw.obj("comments")!!["list"]!!.jsonArray)
        assertEquals(expected, comments.map { it.str("comment_id") to it.str("parent_comment_id") })
        assertTrue(comments.size >= 20)
        assertTrue(comments.all { it.str("content").isNotEmpty() && it.obj("user") != null })
        assertEquals(JsonPrimitive(true), summary.obj("comments")!!["platform_has_more"])
    }
    @Test fun shareLinkKeepsSiblingCommentsAndFullAccessReference() {
        val raw = fixture()
        val note = GongduCodec.note(raw, GongduFeed("fixture-note", "教程", "full-access-token"))
        val linked = gongduLinkDetail(note)
        assertEquals(raw["comments"], linked["comments"])
        val summary = compact(linked)
        assertEquals("full-access-token", summary.obj("data")!!.str("xsec_token"))
        assertEquals(18, readAll(summary.obj("images")!!.str("result_ref")).size)
        assertTrue(readAll(summary.obj("comments")!!.str("result_ref")).isNotEmpty())
    }
    @Test fun imageAttachmentsAreBoundedAndCannotCrossAssistantOrServer() {
        val ref = compact(fixture()).obj("images")!!.str("result_ref")
        assertThrows(IllegalArgumentException::class.java) { GongduToolResultPolicy.pageParts(server, assistant, ref, 0, 5, true) }
        for (scope in listOf(server to Uuid.random(), Uuid.random() to assistant)) {
            val parts = GongduToolResultPolicy.pageParts(scope.first, scope.second, ref, 0, 4, true)
            assertTrue(parts.filterIsInstance<UIMessagePart.Image>().isEmpty())
            assertTrue((parts.first() as UIMessagePart.Text).text.contains("无权限"))
        }
        for (url in listOf("https://xhscdn.com.evil.test/a", "https://user:pass@xhscdn.com/a",
            "https://127.0.0.1/a", "http://xhscdn.com/a", "https://xhscdn.com:8080/a")) {
            assertFalse(GongduToolResultPolicy.readableImageUrl(url))
        }
    }
    @Test fun oldFollowSuccessIsOnlyAnUnverifiedObservation() {
        val parts = GongduToolResultPolicy.compact(server, assistant, "follow_user", listOf(UIMessagePart.Text(
            """{"changed":true,"following":true,"mutual":true,"verified_by":"profile_reload"}""")))
        val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("cross_account_unverified", result.str("status"))
        assertEquals(JsonPrimitive(false), result["retry_safe"])
        assertEquals(parts, GongduToolResultPolicy.compact(server, assistant, "follow_user", parts))
    }
}
