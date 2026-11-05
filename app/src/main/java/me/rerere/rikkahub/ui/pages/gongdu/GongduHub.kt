package me.rerere.rikkahub.ui.pages.gongdu

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.content.edit
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.math.absoluteValue
import kotlin.math.sign

/** One door in the hub. Add an entry here to add a room; [enabled] = false shows it as "筹备中". */
data class GongduRoom(
    val id: String,
    val numeral: String,
    val name: String,
    val subtitle: String,
    @DrawableRes val illustration: Int?,
    val enabled: Boolean,
)

val GongduRooms = listOf(
    GongduRoom("look", "I", "一起逛", "LOOK · CHAT · SHARE", R.drawable.gongdu_room_octopus, enabled = true),
    GongduRoom("read", "II", "一起读", "READ · NOTE · TALK", R.drawable.gongdu_room_fish, enabled = false),
    GongduRoom("listen", "III", "一起听", "LISTEN · LYRICS · MOOD", R.drawable.gongdu_room_jellyfish, enabled = false),
    GongduRoom("next", "IV", "下一间", "COMING SOON", null, enabled = false),
)

/** Fixed paper palette; the hub intentionally ignores dynamic color. */
internal data class GongduPaper(
    val background: Color, val card: Color, val ink: Color, val soft: Color, val muted: Color,
    val line: Color, val hair: Color, val dot: Color,
) {
    companion object {
        val Light = GongduPaper(Color(0xFFEFECE6), Color(0xFFFBFAF8), Color(0xFF2B2927), Color(0xFF6E675F),
            Color(0xFF9A938A), Color(0xFF8C847A), Color(0xFFD8D2C8), Color(0xFFC9C2B7))
        val Dark = GongduPaper(Color(0xFF1C1B19), Color(0xFF2A2825), Color(0xFFEDE6DA), Color(0xFFBDB4A6),
            Color(0xFF8E867B), Color(0xFFCFC6B8), Color(0xFF4A463F), Color(0xFF4F4A43))
    }
}

private const val HUB_PREFS = "gongdu_hub"
private const val LAST_ROOM_KEY = "last_room"

internal fun Context.lastGongduRoom(): String? = getSharedPreferences(HUB_PREFS, Context.MODE_PRIVATE).getString(LAST_ROOM_KEY, null)
internal fun Context.saveLastGongduRoom(id: String) =
    getSharedPreferences(HUB_PREFS, Context.MODE_PRIVATE).edit { putString(LAST_ROOM_KEY, id) }

/** Coverflow transform for a page at signed distance [offset] from the centre (0 = current card). */
internal data class CoverflowTransform(val scale: Float, val rotation: Float, val alpha: Float)

internal fun coverflowTransform(offset: Float): CoverflowTransform {
    val d = offset.absoluteValue.coerceAtMost(2f)
    val (scale, rotation, alpha) = if (d <= 1f) Triple(lerp(1f, 0.86f, d), lerp(0f, 5f, d), lerp(1f, 0.6f, d))
    else Triple(lerp(0.86f, 0.8f, d - 1f), lerp(5f, 7f, d - 1f), lerp(0.6f, 0.35f, d - 1f))
    return CoverflowTransform(scale, rotation * offset.sign, alpha)
}

@Composable
fun GongduHub(
    onNavigateBack: () -> Unit,
    onEnterRoom: (GongduRoom) -> Unit,
    onUnavailableRoom: (GongduRoom) -> Unit,
    rooms: List<GongduRoom> = GongduRooms,
    pager: PagerState = rememberHubPagerState(rooms),
) {
    val context = LocalContext.current
    val paper = if (LocalDarkMode.current) GongduPaper.Dark else GongduPaper.Light
    LaunchedEffect(pager.settledPage) { rooms.getOrNull(pager.settledPage)?.let { context.saveLastGongduRoom(it.id) } }

    Surface(color = paper.background, contentColor = paper.ink, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateBack) { Icon(HugeIcons.ArrowLeft01, contentDescription = "返回", tint = paper.soft) }
                Spacer(Modifier.weight(1f))
                Text("%02d / %02d".format(pager.currentPage + 1, rooms.size), color = paper.muted,
                    fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(end = 16.dp))
            }
            Column(Modifier.padding(horizontal = 28.dp)) {
                Text("// GONGDU INDEX · OURS", color = paper.muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
                Text("共度", color = paper.ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("每一张牌，通往一件一起做的事", color = paper.soft, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.width(28.dp).height(1.5.dp).background(paper.ink))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Card keeps the 236x392 proportion and shrinks on short or narrow screens.
                val cardHeight = minOf(392.dp, maxHeight * 0.88f, (maxWidth * 0.62f) * (392f / 236f))
                val cardWidth = cardHeight * (236f / 392f)
                HorizontalPager(
                    state = pager,
                    contentPadding = PaddingValues(horizontal = (maxWidth - cardWidth) / 2),
                    pageSpacing = (-cardWidth * 0.12f),
                    beyondViewportPageCount = 2,
                    modifier = Modifier.fillMaxWidth(),
                ) { page ->
                    val room = rooms[page]
                    val offset = pager.pageOffsetOf(page)
                    val t = coverflowTransform(offset)
                    RoomCard(
                        room = room, paper = paper,
                        modifier = Modifier
                            .size(cardWidth, cardHeight)
                            .zIndex(-offset.absoluteValue)
                            .graphicsLayer {
                                scaleX = t.scale; scaleY = t.scale; rotationZ = t.rotation; alpha = t.alpha
                            },
                        onClick = {
                            if (page != pager.currentPage) return@RoomCard
                            if (room.enabled) onEnterRoom(room) else onUnavailableRoom(room)
                        },
                    )
                }
            }
            PageDots(pager, paper)
            Text("左右滑动挑一间 · 轻点进入", color = paper.muted, fontSize = 11.sp, letterSpacing = 1.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp))
        }
    }
}

/** Signed distance from the centre card: pages to the right are positive, so they tilt clockwise (fan outward). */
internal fun hubPageOffset(page: Int, currentPage: Int, fraction: Float): Float = (page - currentPage) - fraction

private fun PagerState.pageOffsetOf(page: Int): Float = hubPageOffset(page, currentPage, currentPageOffsetFraction)

/** Opens on the room the person last stopped at. */
@Composable
internal fun rememberHubPagerState(rooms: List<GongduRoom>): PagerState {
    val context = LocalContext.current
    val initialPage = remember(rooms) { rooms.indexOfFirst { it.id == context.lastGongduRoom() }.coerceAtLeast(0) }
    return rememberPagerState(initialPage = initialPage) { rooms.size }
}

@Composable
private fun RoomCard(room: GongduRoom, paper: GongduPaper, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    // The placeholder "next room" card is greyed; rooms with art keep the paper face even before launch.
    val dimmed = !room.enabled && room.illustration == null
    val cardColor = if (dimmed) lerpColor(paper.card, paper.hair, 0.35f) else paper.card
    Box(
        modifier
            .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(shape)
            .background(cardColor)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${room.name}，${if (room.enabled) "轻点进入" else "筹备中"}" }
            .padding(10.dp)
            .border(0.8.dp, paper.hair, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth()) {
                Text("No.${room.numeral}", color = paper.muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                Text("SPECIMEN", color = paper.muted, fontSize = 9.sp, letterSpacing = 1.8.sp)
            }
            Box(Modifier.fillMaxWidth().weight(0.56f), contentAlignment = Alignment.Center) {
                if (room.illustration != null) {
                    // Transparent white line art (drawable-nodpi); the tint sets the paper ink colour.
                    Image(
                        painter = painterResource(room.illustration), contentDescription = null,
                        colorFilter = ColorFilter.tint(paper.line),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(vertical = 6.dp),
                    )
                } else ComingSoonMark(paper)
            }
            Box(Modifier.fillMaxWidth().height(0.8.dp).background(paper.hair))
            Column(Modifier.fillMaxWidth().weight(0.44f).padding(top = 14.dp)) {
                Text(room.name, color = if (dimmed) paper.soft else paper.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(room.subtitle, color = paper.muted, fontSize = 9.5.sp, letterSpacing = 1.6.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text(if (room.enabled) "轻点进入 →" else "筹备中", color = paper.soft, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun ComingSoonMark(paper: GongduPaper) {
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(paper.line, radius = size.minDimension / 2 - 2f, center = Offset(size.width / 2, size.height / 2),
                style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        }
        Text("?", color = paper.line, fontSize = 26.sp)
    }
}

@Composable
private fun PageDots(pager: PagerState, paper: GongduPaper) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(pager.pageCount) { index ->
            val selected = index == pager.currentPage
            Box(
                Modifier.padding(horizontal = 3.dp).animateContentSize()
                    .height(6.dp).width(if (selected) 22.dp else 6.dp)
                    .clip(CircleShape).background(if (selected) paper.ink else paper.dot)
            )
        }
    }
}
