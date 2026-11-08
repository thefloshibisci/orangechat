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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
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
        val Light = GongduPaper(Color(0xFFF5EEE9), Color(0xFFFCF8F4), Color(0xFF554B43), Color(0xFF74695F),
            Color(0xFF756B61), Color(0xFF8C847A), Color(0xFFD8D2C8), Color(0xFFC9C2B7))
        val Dark = GongduPaper(Color(0xFF1C1B19), Color(0xFF2A2825), Color(0xFFE4D9C8), Color(0xFFBDB4A6),
            Color(0xFFB6AB9B), Color(0xFFCFC6B8), Color(0xFF4A463F), Color(0xFF4F4A43))
    }
}

private const val HUB_PREFS = "gongdu_hub"
private const val LAST_ROOM_KEY = "last_room"

// Bundled title/Latin subset: Android's generic serif can fall back to sans for Chinese.
// See docs/design/gongdu-typography.md before adding or renaming room titles.
internal val GongduBook = FontFamily(Font(R.font.gongdu_book_regular, FontWeight.Normal))

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
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageScale = (maxWidth.value / 360f).coerceIn(.9f, 1.18f)
            HubPaperDecoration(paper, Modifier.matchParentSize())
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(top = 16.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack) { Icon(HugeIcons.ArrowLeft01, contentDescription = "返回", tint = paper.soft) }
                    Spacer(Modifier.weight(1f))
                    Text("%02d / %02d".format(pager.currentPage + 1, rooms.size), color = paper.muted,
                        fontFamily = GongduBook, fontWeight = FontWeight.Normal,
                        fontSize = (11 * pageScale).sp, lineHeight = (15 * pageScale).sp, letterSpacing = 1.sp, modifier = Modifier.padding(end = 16.dp))
                }
                Column(Modifier.padding(horizontal = 28.dp)) {
                    Text("GONGDU INDEX · OURS", color = paper.muted, fontFamily = GongduBook, fontWeight = FontWeight.Normal,
                        fontSize = (9.5f * pageScale).sp, lineHeight = (13 * pageScale).sp, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("共度", color = paper.ink, fontFamily = GongduBook, fontWeight = FontWeight.Normal,
                        fontSize = (31 * pageScale).sp, lineHeight = (39 * pageScale).sp, letterSpacing = 1.4.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("每一张牌，通往一件一起做的事", color = paper.soft, fontFamily = GongduBook,
                        fontWeight = FontWeight.Normal, fontSize = (12 * pageScale).sp, lineHeight = (17 * pageScale).sp)
                    Spacer(Modifier.height(12.dp))
                    GongduCelestialRule(paper.line)
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // Use the available viewport, not a small fixed dp cap on tall phones.
                    val cardHeight = minOf(500.dp, maxHeight * 0.96f, (maxWidth * 0.70f) * (392f / 236f))
                    val cardWidth = cardHeight * (236f / 392f)
                    HorizontalPager(
                        state = pager,
                        contentPadding = PaddingValues(horizontal = (maxWidth - cardWidth) / 2),
                        pageSpacing = (-cardWidth * 0.2f),
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
                Text("左右滑动挑一间 · 轻点进入", color = paper.muted, fontSize = (10 * pageScale).sp, lineHeight = (14 * pageScale).sp, letterSpacing = .8.sp,
                    fontFamily = GongduBook, fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp))
            }
        }
    }
}

/** A quiet hairline with a small crescent and star, echoing the paper frame. */
@Composable
internal fun GongduCelestialRule(color: Color) {
    Canvas(Modifier.width(52.dp).height(8.dp)) {
        val ink = color.copy(alpha = .65f)
        val cy = size.height / 2
        drawLine(ink, Offset(0f, cy), Offset(28.dp.toPx(), cy), .5.dp.toPx())
        val cx = 37.dp.toPx()
        val r = 3.5.dp.toPx()
        val moon = Path().apply {
            moveTo(cx + r * .45f, cy - r)
            cubicTo(cx - r * 1.35f, cy - r, cx - r * 1.35f, cy + r, cx + r * .45f, cy + r)
            cubicTo(cx - r * .55f, cy + r * .45f, cx - r * .55f, cy - r * .45f, cx + r * .45f, cy - r)
            close()
        }
        drawPath(moon, ink)
        val sx = 47.dp.toPx()
        val sr = 2.dp.toPx()
        val star = Path().apply {
            moveTo(sx, cy - sr)
            quadraticTo(sx + sr * .2f, cy - sr * .2f, sx + sr, cy)
            quadraticTo(sx + sr * .2f, cy + sr * .2f, sx, cy + sr)
            quadraticTo(sx - sr * .2f, cy + sr * .2f, sx - sr, cy)
            quadraticTo(sx - sr * .2f, cy - sr * .2f, sx, cy - sr)
            close()
        }
        drawPath(star, ink)
    }
}

/** Pixel-faithful artwork from the user's original-design ZIP; never flatten the alpha. */
@Composable
private fun LaceImage(@DrawableRes resource: Int, paper: GongduPaper, modifier: Modifier,
    alpha: Float = 1f, contentScale: ContentScale = ContentScale.Fit) {
    Image(painterResource(resource), null, modifier, contentScale = contentScale, alpha = alpha,
        colorFilter = if (LocalDarkMode.current) ColorFilter.tint(paper.line) else null)
}

@Composable
private fun HubPaperDecoration(paper: GongduPaper, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(0.dp))) {
        Image(painterResource(R.drawable.gongdu_lace_paper), null, Modifier.matchParentSize(),
            contentScale = ContentScale.Crop, alpha = if (LocalDarkMode.current) .045f else .75f)
        Box(Modifier.matchParentSize().systemBarsPadding().padding(horizontal = 5.dp, vertical = 4.dp)) {
            LaceImage(R.drawable.gongdu_lace_page_top, paper, Modifier.align(Alignment.TopCenter).fillMaxWidth().aspectRatio(829f / 88f))
            // Restored lower halves of the original moon/stars, with a little transparent edge clearance.
            LaceImage(R.drawable.gongdu_lace_page_bottom, paper, Modifier.align(Alignment.BottomCenter).fillMaxWidth().aspectRatio(832f / 110f))
            LaceImage(R.drawable.gongdu_lace_cloud_moon, paper, Modifier.align(Alignment.TopEnd).offset(y = 65.dp).width(166.dp).aspectRatio(369f / 460f), .85f)
            LaceImage(R.drawable.gongdu_lace_cloud_left, paper, Modifier.align(Alignment.CenterStart).offset(y = (-115).dp).width(130.dp).aspectRatio(249f / 152f), .7f)
            LaceImage(R.drawable.gongdu_lace_flower_left, paper, Modifier.align(Alignment.BottomStart).offset(y = (-22).dp).width(121.dp).aspectRatio(265f / 310f), .9f)
            LaceImage(R.drawable.gongdu_lace_flower_right, paper, Modifier.align(Alignment.BottomEnd).offset(y = (-22).dp).width(121.dp).aspectRatio(266f / 322f), .9f)
            Canvas(Modifier.matchParentSize().padding(horizontal = 2.dp, vertical = 33.dp)) {
                val ink = paper.line.copy(alpha = .42f)
                for (x in listOf(0f, size.width)) {
                    drawLine(ink, Offset(x, 0f), Offset(x, size.height), .45.dp.toPx())
                    for (fraction in listOf(.23f, .84f)) {
                        val y = size.height * fraction
                        drawLine(ink, Offset(x - 2.dp.toPx(), y), Offset(x + 2.dp.toPx(), y), 1.dp.toPx())
                    }
                }
            }
        }
    }
}

@Composable
private fun CardLaceFrame(paper: GongduPaper, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val unit = maxWidth / 511f
        val top = unit * 180f
        val bottom = unit * 153f
        LaceImage(R.drawable.gongdu_lace_card_top, paper, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(top))
        LaceImage(R.drawable.gongdu_lace_card_bottom, paper, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom))
        LaceImage(R.drawable.gongdu_lace_card_left, paper, Modifier.align(Alignment.CenterStart)
            .padding(top = top, bottom = bottom).width(unit * 24f).fillMaxHeight(), contentScale = ContentScale.FillBounds)
        LaceImage(R.drawable.gongdu_lace_card_right, paper, Modifier.align(Alignment.CenterEnd)
            .padding(top = top, bottom = bottom).width(unit * 24f).fillMaxHeight(), contentScale = ContentScale.FillBounds)
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
            .testTag("gongdu-card-${room.id}"),
    ) {
        Image(painterResource(R.drawable.gongdu_lace_paper), null, Modifier.matchParentSize(),
            contentScale = ContentScale.Crop, alpha = if (LocalDarkMode.current) .03f else .28f)
        CardLaceFrame(paper, Modifier.matchParentSize().padding(9.dp))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cardScale = (maxWidth.value / 236f).coerceIn(.86f, 1.35f)
            val dividerHeight = 12.dp
            val illustrationHeight = maxHeight * (2f / 3f) - dividerHeight / 2
            val footerInset = maxHeight * .12f
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp * cardScale)) {
                // Give the art the upper two thirds; measure from the whole card, not its padded body.
                Column(Modifier.fillMaxWidth().height(illustrationHeight).padding(top = 28.dp * cardScale, bottom = 6.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("No.${room.numeral}", color = paper.muted, fontFamily = GongduBook, fontWeight = FontWeight.Normal,
                            fontSize = (9 * cardScale).sp, lineHeight = (12 * cardScale).sp, letterSpacing = 1.2.sp)
                        Spacer(Modifier.weight(1f))
                        Text("SPECIMEN", color = paper.muted, fontFamily = GongduBook, fontWeight = FontWeight.Normal,
                            fontSize = (8.5f * cardScale).sp, lineHeight = (12 * cardScale).sp, letterSpacing = 1.4.sp)
                    }
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        if (room.illustration != null) {
                            Image(
                                painter = painterResource(room.illustration), contentDescription = null,
                                colorFilter = ColorFilter.tint(paper.line),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
                            )
                        } else ComingSoonMark(paper)
                    }
                }
                Row(Modifier.fillMaxWidth().height(dividerHeight).testTag("gongdu-divider-${room.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f).height(.5.dp).background(paper.line.copy(alpha = .6f)))
                    Text("✦", color = paper.muted, fontSize = 10.sp, lineHeight = 12.sp)
                    Box(Modifier.weight(1f).height(.5.dp).background(paper.line.copy(alpha = .6f)))
                }
                Column(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp, bottom = footerInset)) {
                    Text(room.name, color = if (dimmed) paper.soft else paper.ink, fontSize = (20 * cardScale).sp, lineHeight = (26 * cardScale).sp,
                        fontFamily = GongduBook, fontWeight = FontWeight.Normal, letterSpacing = .5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(room.subtitle, color = paper.muted, fontSize = (8.5f * cardScale).sp, lineHeight = (12 * cardScale).sp, letterSpacing = 1.sp,
                        fontFamily = GongduBook, fontWeight = FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    val hintStyle = TextStyle(fontFamily = GongduBook, fontWeight = FontWeight.Normal,
                        fontSize = (10 * cardScale).sp, lineHeight = (13 * cardScale).sp)
                    val hintLayout = rememberTextMeasurer().measure("轻点进入 →", hintStyle)
                    val density = LocalDensity.current
                    Box(Modifier.align(Alignment.End).width(with(density) { hintLayout.size.width.toDp() })) {
                        Text(if (room.enabled) "轻点进入 →" else "筹备中", color = paper.soft, style = hintStyle,
                            modifier = Modifier.padding(start = if (room.enabled) 0.dp else
                                with(density) { hintLayout.getBoundingBox(1).left.toDp() }))
                    }
                }
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
        Text("?", color = paper.line, fontSize = 22.sp, lineHeight = 28.sp)
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
