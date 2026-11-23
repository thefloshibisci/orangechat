package me.rerere.rikkahub.ui.components.message.listen

import android.graphics.Bitmap
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** V11 share card. The repository owns playback; rendering never resolves audio or lyrics. */
@Composable
internal fun MusicGiftCard(
    cover: Bitmap?,
    palette: MusicPalette,
    lines: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    dark: Boolean = false,
    modifier: Modifier = Modifier,
    songTitle: String,
    artist: String,
    message: String,
    playing: Boolean,
    enabled: Boolean,
    busy: Boolean,
) {
    var allLyrics by remember(lines, expanded) { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    val ink = if (dark) Color(0xFFF7F3F2) else Color(0xFF312B32)
    val muted = if (dark) Color(0xFFD5CED2) else Color(0xFF6F626C)
    val accent = if (dark) lerp(palette.accent, Color.White, .62f)
        else lerp(palette.accent, Color(0xFF352633), .45f)
    val base = if (dark) Color(0xEE302F38) else Color(0xF5FFF9F6)
    val paper = if (dark) Color.White.copy(alpha = .055f) else palette.recordTint.copy(alpha = .10f)

    Column(
        modifier.widthIn(max = 340.dp).fillMaxWidth().clip(shape).background(base)
            .background(Brush.horizontalGradient(listOf(palette.glow.copy(alpha = .15f), Color.Transparent)))
            .border(1.dp, Color.White.copy(alpha = if (dark) .19f else .78f), shape)
            .animateContentSize(animationSpec = tween(200)),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = if (lines.isEmpty() && message.isBlank()) 14.dp else 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("共度", color = accent, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                Text("/", color = muted.copy(alpha = .7f), fontSize = 13.sp, lineHeight = 18.sp)
                Text("一首给你的歌", color = muted, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = .35.sp)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShareDisc(cover, palette, Modifier.size(70.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(songTitle, color = ink, fontSize = 16.sp, lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(artist, color = muted, fontSize = 12.sp, lineHeight = 17.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onPlay, enabled = enabled && !busy,
                    modifier = Modifier.size(48.dp).testTag("card-play").semantics {
                        contentDescription = if (busy) "正在准备播放" else if (playing) "暂停播放" else "播放这首歌"
                    }) {
                    Box(Modifier.size(29.dp).clip(CircleShape)
                        .background(accent.copy(alpha = if (dark) .20f else .11f))
                        .border(1.dp, accent.copy(alpha = .22f), CircleShape), contentAlignment = Alignment.Center) {
                        if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(15.dp), color = accent, strokeWidth = 2.dp)
                        else PlayGlyph(playing, if (enabled) accent else muted.copy(alpha = .45f), Modifier.size(15.dp))
                    }
                }
            }
        }
        if (lines.isNotEmpty()) {
            // One piece of tinted paper, with stable margins whether it is folded or open.
            Box(Modifier.fillMaxWidth().background(paper)) {
                if (!expanded) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("card-expand")
                        .clickable(role = Role.Button, onClickLabel = "展开所选歌词", onClick = onToggle)
                        .padding(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        LyricQuote(accent)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(lines.first(), color = ink, fontFamily = FontFamily.Serif, fontSize = 13.sp,
                                lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (message.isNotBlank()) Text(message, color = muted, fontSize = 12.sp,
                                lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(6.dp))
                        LyricFoldMark(lines.size, false, accent)
                    }
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp),
                            verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 12.dp)) { LyricQuote(accent) }
                            val lyricScroll = rememberScrollState()
                            val visible = if (allLyrics) lines else lines.take(3)
                            Column(Modifier.weight(1f).padding(top = 12.dp, bottom = 11.dp)
                                .heightIn(max = 152.dp).verticalScroll(lyricScroll),
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                visible.forEachIndexed { index, line ->
                                    Text(line, color = if (index == 0) ink else muted,
                                        fontFamily = FontFamily.Serif, fontSize = 13.sp,
                                        lineHeight = 19.sp)
                                }
                                if (message.isNotBlank()) Text(message, Modifier.padding(top = 6.dp),
                                    color = muted, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                            Box(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).testTag("card-expand")
                                .clickable(role = Role.Button, onClickLabel = "收起歌词", onClick = onToggle)
                                .padding(end = 4.dp), contentAlignment = Alignment.CenterEnd) {
                                LyricFoldMark(lines.size, true, accent)
                            }
                        }
                        if (lines.size > 3 && !allLyrics) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("all-lyrics")
                                .clickable(role = Role.Button) { allLyrics = true }.padding(start = 16.dp + quoteColumnWidth),
                                contentAlignment = Alignment.CenterStart) {
                                Text("查看全部 ${lines.size} 句", color = accent, fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        } else if (message.isNotBlank()) {
            Text(message, Modifier.fillMaxWidth().background(paper).padding(horizontal = 16.dp, vertical = 10.dp)
                .heightIn(max = 152.dp).verticalScroll(rememberScrollState()),
                color = ink, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

// Keep folded lyrics, open lyrics and the all-lyrics action on a common left edge.
private val quoteColumnWidth = 26.dp

/** A drawn pair of opening quotation marks, silent for TalkBack. */
@Composable
private fun LyricQuote(color: Color) {
    Canvas(Modifier.width(quoteColumnWidth).height(17.dp)) {
        for (x in listOf(3f, 9f)) {
            val mark = androidx.compose.ui.graphics.Path().apply {
                moveTo((x + 2.6f).dp.toPx(), 3.2.dp.toPx())
                quadraticTo((x - 2.9f).dp.toPx(), 6.7.dp.toPx(), (x - 1.1f).dp.toPx(), 10.5.dp.toPx())
                quadraticTo((x + 2.0f).dp.toPx(), 14.2.dp.toPx(), (x + 3.2f).dp.toPx(), 10.1.dp.toPx())
                quadraticTo((x + 3.6f).dp.toPx(), 8.0.dp.toPx(), x.dp.toPx(), 8.0.dp.toPx())
                quadraticTo(x.dp.toPx(), 6.6.dp.toPx(), (x + 2.6f).dp.toPx(), 4.1.dp.toPx()); close()
            }
            drawPath(mark, color.copy(alpha = .60f))
        }
    }
}

@Composable
private fun LyricFoldMark(count: Int, open: Boolean, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("${count}句", color = color, fontSize = 11.sp, lineHeight = 16.sp)
        Canvas(Modifier.size(10.dp)) {
            val start = size.height * if (open) .62f else .38f
            val end = size.height - start
            val m = Offset(center.x, end)
            drawLine(color, Offset(size.width * .14f, start), m, 1.3.dp.toPx(), StrokeCap.Round)
            drawLine(color, m, Offset(size.width * .86f, start), 1.3.dp.toPx(), StrokeCap.Round)
        }
    }
}
