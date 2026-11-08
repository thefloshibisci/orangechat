package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.serialization.json.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.data.gongdu.*
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog

/** A reader for the actual service response, not an embedded or simulated official app. */
@Composable
internal fun GongduNoteDetail(
    note: GongduNote, busy: Boolean, message: String?, onBack: () -> Unit,
    onOriginal: () -> Unit, onShare: () -> Unit,
    videoActions: @Composable ColumnScope.() -> Unit = {},
) {
    val images = remember(note) { note.images }
    var preview by remember(note.feed.id) { mutableStateOf<String?>(null) }
    val author = note.feed.author.ifBlank { "作者未提供" }
    GongduWaterBackdrop {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .gongduGlass(RoundedCornerShape(16.dp)).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = !busy) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = "关闭笔记")
                }
                val avatar = note.body.obj("user")?.str("avatar", "image", "avatarUrl")
                    ?.let(GongduToolResultPolicy::normalizedImageUrl)
                if (avatar != null) AsyncImage(avatar, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(32.dp).clip(CircleShape))
                else Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center) { Text(author.take(1), fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onPrimaryContainer) }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(author, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("小红书 · 一起逛", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onOriginal) { Text("打开原文", maxLines = 1) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("gongdu-note-body"),
                contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    if (images.isEmpty()) Box(Modifier.fillMaxWidth().height(160.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
                        Text("服务未提供可显示的配图，可打开原文查看", style = MaterialTheme.typography.bodySmall)
                    } else GongduNoteGallery(images, onImageClick = { preview = it })
                }
                item {
                    // The reading surface is nearly opaque so feed text/wallpaper cannot bleed through the body.
                    Column(Modifier.padding(horizontal = 14.dp).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = .96f)).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(note.feed.title.replace("[话题]", ""), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Normal, lineHeight = 30.sp)
                        Text(note.content.ifBlank { "这篇笔记没有文字正文" }.replace("[话题]", ""),
                            style = MaterialTheme.typography.bodyLarge, lineHeight = 27.sp)
                        val stats = note.body.obj("interactInfo") ?: note.body.obj("interact_info")
                        val counts = listOf("赞" to stats?.str("likedCount", "liked_count"),
                            "收藏" to stats?.str("collectedCount", "collected_count"),
                            "评论" to stats?.str("commentCount", "comment_count"))
                            .filter { !it.second.isNullOrBlank() }.joinToString("   ·   ") { "${it.second} ${it.first}" }
                        if (counts.isNotBlank()) Text(counts, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        videoActions()
                    }
                }
                val comments = note.raw["comments"] ?: note.body["comments"]
                val rows = ((comments as? JsonObject)?.get("list") ?: comments) as? JsonArray
                if (!rows.isNullOrEmpty()) item {
                    Column(Modifier.padding(horizontal = 14.dp).gongduGlass(RoundedCornerShape(14.dp)).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("评论预览", style = MaterialTheme.typography.titleSmall)
                        rows.take(3).forEach { row ->
                            val comment = row as? JsonObject
                            val text = comment?.str("content", "text").orEmpty()
                            if (text.isNotBlank()) {
                                val user = (comment?.obj("userInfo") ?: comment?.obj("user"))?.str("nickname", "nickName").orEmpty()
                                Text(listOf(user, text).filter(String::isNotBlank).joinToString("："),
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TextButton(onClick = onOriginal) { Text("去原文查看完整互动") }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                GongduButton(onClick = onShare, enabled = !busy, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (busy) "正在送到聊天…" else "和机聊这篇")
                }
                Text("可附上前 6 张配图，一起聊聊", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
    preview?.let { url -> ImagePreviewDialog(listOf(url), onDismissRequest = { preview = null }) }
}

@Composable
internal fun GongduNoteGallery(images: List<GongduImage>, onImageClick: (String) -> Unit) {
    val pager = rememberPagerState { images.size }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box {
            HorizontalPager(pager, modifier = Modifier.fillMaxWidth().testTag("gongdu-note-gallery")) { page ->
                val picture = images[page]
                val ratio = if (picture.width > 0 && picture.height > 0)
                    (picture.width.toFloat() / picture.height).coerceIn(.8f, 1.5f) else .8f
                var failed by remember(picture.url) { mutableStateOf(false) }
                var loaded by remember(picture.url) { mutableStateOf(false) }
                var retry by remember(picture.url) { mutableIntStateOf(0) }
                Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center) {
                    key(picture.url, retry) {
                        AsyncImage(picture.url, contentDescription = "笔记配图 ${picture.index}", contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().clickable(enabled = loaded) { onImageClick(picture.url) },
                            onSuccess = { loaded = true; failed = false }, onError = { failed = true })
                    }
                    if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("配图暂未加载", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { failed = false; loaded = false; retry++ }) { Text("重试这张") }
                    } else if (!loaded) Text("正在加载配图…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("${pager.currentPage + 1} / ${images.size}", style = MaterialTheme.typography.labelMedium,
                color = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(14.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = .48f)).padding(horizontal = 10.dp, vertical = 5.dp))
        }
        if (images.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
            val start = (pager.currentPage - 3).coerceIn(0, (images.size - 7).coerceAtLeast(0))
            for (index in start until (start + 7).coerceAtMost(images.size)) {
                Box(Modifier.size(if (index == pager.currentPage) 7.dp else 5.dp).clip(CircleShape)
                    .background(if (index == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}
