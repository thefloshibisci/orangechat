package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.interaction.ChatInteraction
import me.rerere.rikkahub.data.interaction.formatChatAmount
import java.text.SimpleDateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Date
import java.util.Locale

private fun displayGiftAmount(cents: Long) = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
    .format(java.math.BigDecimal.valueOf(cents, 2))

// Dimensions and SVGs come from the supplied HTML, not one shared card template.
private fun giftIcon(theme: String, packet: Boolean) = when {
    packet && theme == "sakura" -> R.drawable.interaction_packet_sakura
    packet && theme == "lavender" -> R.drawable.interaction_packet_lavender
    packet -> R.drawable.interaction_packet_ice
    theme == "sakura" -> R.drawable.interaction_transfer_sakura
    theme == "lavender" -> R.drawable.interaction_transfer_lavender
    else -> R.drawable.interaction_transfer_ice
}

internal fun themeMascot(theme: String) = when (theme) {
    "sakura" -> R.drawable.interaction_bear
    "lavender" -> R.drawable.interaction_bunny
    else -> R.drawable.interaction_fish
}

private fun giftCaption(theme: String) = when (theme) {
    "sakura" -> "水晶礼盒"
    "lavender" -> "星河契约"
    else -> "水光礼物"
}

@Composable
internal fun ReferenceGlass(
    theme: String, modifier: Modifier = Modifier, radius: Dp = 22.dp,
    settled: Boolean = false, onClick: (() -> Unit)? = null,
    confirmation: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = interactionColors(theme)
    val shape = RoundedCornerShape(radius)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, label = "glassPress")
    val confirmationScale = remember { Animatable(1f) }
    LaunchedEffect(confirmation) {
        if (confirmation) {
            confirmationScale.snapTo(.92f)
            confirmationScale.animateTo(1.045f, spring(dampingRatio = .42f, stiffness = 480f))
            confirmationScale.animateTo(1f, spring(dampingRatio = .58f, stiffness = 360f))
        }
    }
    Box(modifier.graphicsLayer {
        scaleX = scale * confirmationScale.value
        scaleY = scale * confirmationScale.value
        alpha = if (settled) .82f else 1f
    }
        .shadow(12.dp, shape, clip = false, ambientColor = colors.accent.copy(alpha = .15f), spotColor = colors.accent.copy(alpha = .22f))
        .clip(shape).background(Brush.linearGradient(listOf(colors.top, colors.bottom)))
        .drawWithCache {
            val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = .60f), Color.Transparent), endY = size.height * .38f)
            onDrawBehind {
                drawOval(sheen, topLeft = Offset(-size.width * .1f, -size.height * .42f), size = Size(size.width * 1.2f, size.height * .82f))
                drawLine(Color.White.copy(alpha = .65f), Offset(22.dp.toPx(), 2.dp.toPx()), Offset(size.width - 22.dp.toPx(), 2.dp.toPx()), 1.dp.toPx())
            }
        }.border(1.5.dp, Color.White.copy(alpha = .88f), shape)
        .then(if (onClick != null) Modifier.clickable(source, indication = null, onClick = onClick) else Modifier)) {
        CompositionLocalProvider(LocalContentColor provides colors.ink) { content() }
    }
}

@Composable
private fun ThemeStar(theme: String, modifier: Modifier = Modifier) {
    val ink = interactionColors(theme).accent
    Canvas(modifier.size(16.dp)) {
        val path = Path().apply {
            moveTo(size.width / 2, 0f)
            cubicTo(size.width * .58f, size.height * .4f, size.width * .6f, size.height * .42f, size.width, size.height / 2)
            cubicTo(size.width * .6f, size.height * .58f, size.width * .58f, size.height * .6f, size.width / 2, size.height)
            cubicTo(size.width * .42f, size.height * .6f, size.width * .4f, size.height * .58f, 0f, size.height / 2)
            cubicTo(size.width * .4f, size.height * .42f, size.width * .42f, size.height * .4f, size.width / 2, 0f)
            close()
        }
        drawPath(path, ink)
    }
}

@Composable
private fun GiftBadge(icon: Int, size: Dp = 50.dp, round: Dp = 16.dp, tint: Color? = null) {
    Box(Modifier.size(size).background(Color.White.copy(alpha = .65f), RoundedCornerShape(round))
        .border(1.5.dp, Color.White.copy(alpha = .95f), RoundedCornerShape(round)), contentAlignment = Alignment.Center) {
        Image(painterResource(icon), null, Modifier.size(28.dp), colorFilter = tint?.let(ColorFilter::tint))
    }
}

@Composable
internal fun GiftChatBubble(card: ChatInteraction, state: String, senderName: String, onClick: () -> Unit) {
    val packet = card.kind == "red_packet"
    val settled = card.status in setOf("accepted", "returned")
    if (packet) {
        ReferenceGlass(card.theme, Modifier.widthIn(max = 280.dp).fillMaxWidth().heightIn(min = 136.dp)
            .testTag("packet-bubble"), radius = 24.dp, settled = settled, onClick = onClick,
            confirmation = card.status == "accepted") {
            ThemeStar(card.theme, Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 14.dp))
            Row(Modifier.padding(horizontal = 18.dp, vertical = 15.dp).heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GiftBadge(giftIcon(card.theme, true))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(card.note.ifBlank { "给你的小心意" }, fontSize = 15.sp, lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (card.status == "pending") "✦ 点击开启心意" else "✦ $state", fontSize = 12.sp,
                        lineHeight = 16.sp, color = LocalContentColor.current.copy(alpha = .8f), maxLines = 1)
                }
            }
            Text(giftCaption(card.theme), Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 6.dp),
                fontSize = 10.sp, lineHeight = 12.sp, color = LocalContentColor.current.copy(alpha = .55f))
            if (card.status == "accepted") ReceiptSeal(card.theme, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 6.dp))
        }
    } else {
        ReferenceGlass(card.theme, Modifier.widthIn(max = 280.dp).fillMaxWidth().heightIn(min = 136.dp)
            .testTag("transfer-bubble"), radius = 24.dp, settled = settled, onClick = onClick,
            confirmation = card.status == "accepted") {
            val ink = LocalContentColor.current
            Canvas(Modifier.align(Alignment.BottomEnd).offset(20.dp, 16.dp).size(112.dp)) {
                val center = Offset(size.width * .7f, size.height * .7f)
                drawCircle(ink.copy(alpha = .12f), size.width * .45f, center,
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),4.dp.toPx()))))
                drawCircle(ink.copy(alpha = .12f), size.width * .30f, center, style = Stroke(1.5.dp.toPx()))
            }
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("$senderName 发起转账",
                        Modifier.background(Color.White.copy(alpha = .65f), CircleShape).border(1.dp, Color.White, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
                    if (card.status == "accepted") ReceiptSeal(card.theme)
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(6.dp).background(interactionColors(card.theme).accent, CircleShape))
                        Text(if (card.status == "pending") "待收款" else state, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GiftBadge(giftIcon(card.theme, false))
                    Column(Modifier.weight(1f)) {
                        Text("￥${displayGiftAmount(card.amountCents)}", fontSize = 20.sp, lineHeight = 25.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(card.note.ifBlank { "给你的一点心意" }, fontSize = 12.sp, lineHeight = 16.sp,
                            color = ink.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp), color = Color.White.copy(alpha = .55f))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (card.walletLinked) "转账 · 小钱包" else "转账 · 虚拟金额", fontSize = 10.sp, lineHeight = 14.sp, color = ink.copy(alpha = .65f))
                    Text(if (card.status == "pending" && card.actor == "assistant") "点击收款 ▸" else "查看详情 ▸",
                        fontSize = 10.sp, lineHeight = 14.sp, color = ink.copy(alpha = .65f))
                }
            }
        }
    }
}

@Composable
private fun ReceiptSeal(theme: String, modifier: Modifier = Modifier) {
    val colors = interactionColors(theme)
    AnimatedVisibility(visible = true, enter = fadeIn(tween(220)) + scaleIn(spring(dampingRatio = .48f, stiffness = 420f), initialScale = .45f)) {
        Row(modifier.background(Color.White.copy(alpha = .86f), CircleShape)
            .border(1.dp, Color.White, CircleShape).padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Image(painterResource(R.drawable.interaction_check), null, Modifier.size(12.dp), colorFilter = ColorFilter.tint(colors.accent))
            Text("已收下", fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        }
    }
}

@Composable
private fun PearlAction(theme: String, label: String, enabled: Boolean, round: Boolean = false, onClick: () -> Unit) {
    val colors = interactionColors(theme)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .94f else 1f, label = "pearlPress")
    val pearl = when (theme) {
        "sakura" -> listOf(Color.White, Color(0xFFFFD8E6), Color(0xFFF49AC2))
        "lavender" -> listOf(Color.White, Color(0xFFEDE9FE), Color(0xFFC4B5FD))
        else -> listOf(Color.White, Color(0xFFDBEAFE), Color(0xFF93C5FD))
    }
    Box((if (round) Modifier.size(78.dp) else Modifier.fillMaxWidth().heightIn(min = 44.dp))
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .5f }
        .shadow(8.dp, CircleShape, clip = false, ambientColor = colors.accent.copy(alpha = .2f), spotColor = colors.accent.copy(alpha = .28f))
        .clip(CircleShape).drawWithCache {
            val gradient = Brush.radialGradient(0f to pearl[0], .62f to pearl[1], 1f to pearl[2],
                center = Offset(size.width * .3f, size.height * .28f), radius = size.maxDimension * .8f)
            onDrawBehind { drawRect(gradient) }
        }
        .border(2.dp, Color.White.copy(alpha = .98f), CircleShape)
        .clickable(source, indication = null, enabled = enabled, onClick = onClick).padding(8.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = if (round) 25.sp else 14.sp, fontWeight = FontWeight.Bold, color = colors.ink)
    }
}

@Composable
internal fun GiftDetailCard(card: ChatInteraction, state: String, interactive: Boolean, busy: Boolean, error: String?,
    senderName: String = if (card.actor == "user") "你" else "对方", respond: (String) -> Unit) {
    val packet = card.kind == "red_packet"
    val recipient = card.actor == "assistant"
    val claimable = interactive && recipient && card.status == "pending"
    val amountVisible = !packet || card.status == "accepted" || !recipient
    val opened by animateFloatAsState(if (card.status == "accepted") 1f else 0f, animationSpec = androidx.compose.animation.core.tween(500, easing = androidx.compose.animation.core.CubicBezierEasing(.4f, 0f, .2f, 1f)), label = "packetFlap")
    ReferenceGlass(card.theme, Modifier.widthIn(max = 286.dp).fillMaxWidth()
        .heightIn(min = if (packet) 400.dp else 395.dp).testTag(if (packet) "packet-detail" else "transfer-detail"), radius = 32.dp) {
        if (packet) Box(Modifier.align(Alignment.TopCenter).offset(y = (-92).dp)
            .requiredSize(350.dp, 315.dp).graphicsLayer { translationY = -28.dp.toPx() * opened; scaleY = 1f - .08f * opened }
            .clip(CircleShape).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .62f), Color.White.copy(alpha = .22f))))
            .border(1.5.dp, Color.White.copy(alpha = .9f), CircleShape))
        ThemeStar(card.theme, Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 24.dp))
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = if (packet) 32.dp else 28.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            GiftBadge(if (packet) themeMascot(card.theme) else if (card.status == "accepted") R.drawable.interaction_check else R.drawable.interaction_hourglass,
                size = 56.dp, round = if (packet) 28.dp else 16.dp, tint = if (packet) interactionColors(card.theme).accent else null)
            Spacer(Modifier.height(8.dp))
            Text(if (packet) "$senderName 发出的专属红包" else (if (claimable) "待你确认收款" else state),
                fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
            if (packet) {
                Text("“${card.note.ifBlank { "给你的小心意" }}”", Modifier.padding(top = 6.dp), fontSize = 17.sp, lineHeight = 23.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (claimable) {
                    Box(Modifier.padding(top = 34.dp).size(78.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.requiredSize(92.dp).border(1.5.dp, Color.White.copy(alpha = .75f), CircleShape))
                        PearlAction(card.theme, "拆", !busy, round = true) { respond("accept") }
                    }
                } else if (amountVisible) {
                    Row(Modifier.padding(top = 28.dp), verticalAlignment = Alignment.Bottom) {
                        Text(formatChatAmount(card.amountCents), fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
                        Text("元", Modifier.padding(start = 4.dp, bottom = 4.dp), fontSize = 14.sp)
                    }
                    Text(state, Modifier.padding(top = 8.dp).background(Color.White.copy(alpha = .55f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = .8f), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 11.sp)
                } else Text(state, Modifier.padding(top = 34.dp), fontSize = 14.sp)
            } else {
                Text("￥${displayGiftAmount(card.amountCents)}", Modifier.padding(top = 4.dp, bottom = 16.dp), fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = .45f), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailLine("转账说明", card.note.ifBlank { "给你的一点心意" })
                    DetailLine("发起对象", senderName)
                    DetailLine("发起时间", SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(card.createdAtMillis)))
                }
                Spacer(Modifier.height(20.dp))
                if (claimable) PearlAction(card.theme, "确认收款  ✦", !busy) { respond("accept") }
                else Text(state, Modifier.background(Color.White.copy(alpha = .55f), CircleShape).padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp)
            }
            if (claimable) Text("退还给对方", Modifier.clickable(
                enabled = !busy,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = { respond("return") },
            ).padding(top = 4.dp), fontSize = 11.sp, lineHeight = 15.sp,
                color = LocalContentColor.current.copy(alpha = .65f))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            (error ?: card.error)?.let { raw ->
                val explanation = if (raw.contains("PGRST202") && raw.contains("orangechat_chat_payment"))
                    "小钱包支付接口尚不可用。请先在钱包数据库执行聊天红包升级 SQL，再核实这笔记录；不要重新发送。" else raw
                Text(explanation, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            if (interactive && card.walletLinked && card.status in setOf("syncing", "unconfirmed", "failed")) {
                TextButton(onClick = { respond("sync") }, enabled = !busy) { Text("核实这笔记录") }
            }
            if (packet) Spacer(Modifier.height(40.dp))
        }
        if (packet) Text(when (card.theme) { "sakura" -> "✧ 愿你今天也甜甜的 ✧"; "lavender" -> "✧ 晚安，愿你有好梦 ✧"; else -> "✧ 浮光跃金 · 静影沉璧 ✧" },
            Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp), fontSize = 12.sp,
            lineHeight = 16.sp, color = LocalContentColor.current.copy(alpha = .65f))
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = LocalContentColor.current.copy(alpha = .65f))
        Text(value, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
    }
}
