package me.rerere.rikkahub.data.gongdu

import me.rerere.ai.ui.UIMessagePart
import okhttp3.HttpUrl

/** Platform URL, encoded from note identifiers; never accept a service-supplied URI scheme. */
fun gongduNoteUrl(note: GongduNote): String = HttpUrl.Builder()
    .scheme("https").host("www.xiaohongshu.com").addPathSegment("explore").addPathSegment(note.feed.id)
    .apply { if (note.feed.token.isNotBlank()) {
        addQueryParameter("xsec_token", note.feed.token)
        addQueryParameter("xsec_source", "pc_feed")
    } }.build().toString()

fun gongduNoteShareParts(note: GongduNote, vision: Boolean): List<UIMessagePart> {
    val images = note.images
    val included = if (vision) images.take(GongduToolResultPolicy.MAX_IMAGES_PER_REQUEST) else emptyList()
    return buildList {
        add(UIMessagePart.Text(buildString {
            append("[共度·小红书]\n${note.feed.title}\n${note.content}\n作者：${note.feed.author}\n笔记ID：${note.feed.id}\n")
            append("原文：${gongduNoteUrl(note)}\n")
            when {
                !vision && images.isNotEmpty() -> append("当前模型未启用识图，本次仅分享文字，未提供图片。\n")
                images.isNotEmpty() -> append("本次附带${included.size}张图片（本篇可读取${images.size}张）；未附带的图片尚未看过。\n")
            }
            append("我们一起聊聊这篇吧。")
        }))
        included.forEach { add(UIMessagePart.Image(it.url)) }
    }
}
