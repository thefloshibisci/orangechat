package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
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

// Filled mascots already carry their theme palette. A flat tint erases their faces.
internal fun interactionIconColorFilter(icon: Int, tint: Color): ColorFilter? =
    if (icon == R.drawable.interaction_bear || icon == R.drawable.interaction_fish) null else ColorFilter.tint(tint)

private fun giftCaption(theme: String) = when (theme) {
    "sakura" -> "水晶礼盒"
    "lavender" -> "星河契约"
    else -> "水光礼物"
}

@Composable
internal fun ReferenceGlass(
    theme: String, modifier: Modifier = Modifier, radius: Dp = 22.dp,
    settled: Boolean = false, onClick: (() -> Unit)? = null,
    pressFeedback: Boolean = true, receiptScale: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = interactionColors(theme)
    val shape = RoundedCornerShape(radius)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && pressFeedback) .97f else 1f, label = "glassPress")
    Box(modifier.semantics { this[GlassScale] = scale * receiptScale }.graphicsLayer {
        scaleX = scale * receiptScale
        scaleY = scale * receiptScale
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
        Image(painterResource(icon), null, Modifier.size(28.dp), colorFilter = tint?.let { interactionIconColorFilter(icon, it) })
    }
}

@Composable
internal fun GiftChatBubble(card: ChatInteraction, state: String, senderName: String, onClick: () -> Unit) {
    val packet = card.kind == "red_packet"
    val settled = card.status in setOf("accepted", "returned")
    // Observe live state changes only. Mounting an already accepted card (including scrolling
    // back into view) must never replay the receipt, nor may a touch/drag start it.
    var previousStatus by remember(card.id) { mutableStateOf(card.status) }
    var receiptPlayed by rememberSaveable(card.id) { mutableStateOf(card.status == "accepted") }
    val receiptMotion = remember(card.id) { Animatable(1f) }
    LaunchedEffect(card.id, card.status) {
        val acceptedNow = previousStatus != "accepted" && card.status == "accepted"
        previousStatus = card.status
        if (card.actor == "user" && acceptedNow && !receiptPlayed) {
            receiptPlayed = true
            receiptMotion.animateTo(1f, keyframes {
                durationMillis = 480
                1f at 0; .96f at 80; 1.045f at 210; .992f at 350; 1f at 480
            })
        } else receiptMotion.snapTo(1f)
    }
    if (packet) {
        ReferenceGlass(card.theme, Modifier.widthIn(max = 280.dp).fillMaxWidth().heightIn(min = 94.dp)
            .testTag("packet-bubble"), radius = 22.dp, settled = settled, onClick = onClick, pressFeedback = false,
            receiptScale = receiptMotion.value) {
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
        }
    } else {
        ReferenceGlass(card.theme, Modifier.widthIn(max = 280.dp).fillMaxWidth().heightIn(min = 136.dp)
            .testTag("transfer-bubble"), radius = 24.dp, settled = settled, onClick = onClick, pressFeedback = false,
            receiptScale = receiptMotion.value) {
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
                    TransferBunnyBadge(card)
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
    Row(modifier.background(Color.White.copy(alpha = .86f), CircleShape)
            .border(1.dp, Color.White, CircleShape).padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Image(painterResource(R.drawable.interaction_check), null, Modifier.size(12.dp), colorFilter = ColorFilter.tint(colors.accent))
            Text("已收下", fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
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
    val opening = remember(card.id) { androidx.compose.animation.core.Animatable(if (card.status == "accepted") 1f else 0f) }
    LaunchedEffect(card.status) {
        // Only a confirmed receipt starts the reveal; a pending wallet response is not success.
        if (card.status == "accepted") opening.animateTo(1f, tween(500))
    }
    val opened = opening.value
    val openingPacket = packet && card.status == "accepted" && opened < 1f
    val flap = androidx.compose.animation.core.CubicBezierEasing(.4f, 0f, .2f, 1f).transform(opened)
    val buttonProgress = (opened / .7f).coerceIn(0f, 1f)
    ReferenceGlass(card.theme, Modifier.widthIn(max = 286.dp).fillMaxWidth()
        .heightIn(min = if (packet) 400.dp else 395.dp).testTag(if (packet) "packet-detail" else "transfer-detail"), radius = 32.dp) {
        if (packet) Box(Modifier.align(Alignment.TopCenter).offset(y = (-92).dp)
            .requiredSize(350.dp, 315.dp).graphicsLayer { translationY = -28.dp.toPx() * flap; scaleY = 1f - .08f * flap }
            .clip(CircleShape).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .62f), Color.White.copy(alpha = .22f))))
            .border(1.5.dp, Color.White.copy(alpha = .9f), CircleShape))
        ThemeStar(card.theme, Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 24.dp))
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = if (packet) 32.dp else 28.dp,
            bottom = if (!packet && claimable) 4.dp else 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (packet) GiftBadge(themeMascot(card.theme), size = 56.dp, round = 28.dp, tint = interactionColors(card.theme).accent)
            else TransferBunnyBadge(card, size = 56.dp)
            Spacer(Modifier.height(8.dp))
            Text(if (packet) "$senderName 发出的专属红包" else (if (claimable) "待你确认收款" else state),
                fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
            if (packet) {
                Text("“${card.note.ifBlank { "给你的小心意" }}”", Modifier.padding(top = 6.dp), fontSize = 17.sp, lineHeight = 23.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (claimable || openingPacket || (busy && recipient) || amountVisible) {
                    Box(Modifier.fillMaxWidth().height(112.dp).semantics { this[PacketOpeningProgress] = opened }, contentAlignment = Alignment.TopCenter) {
                        if ((claimable || busy || openingPacket) && opened < .7f) Box(Modifier.padding(top = 34.dp).size(78.dp)
                            .testTag("packet-open-button").graphicsLayer {
                                scaleX = 1f - buttonProgress; scaleY = 1f - buttonProgress
                                rotationZ = 180f * buttonProgress; alpha = 1f - buttonProgress
                            }, contentAlignment = Alignment.Center) {
                            Box(Modifier.requiredSize(92.dp).border(1.5.dp, Color.White.copy(alpha = .75f), CircleShape))
                            PearlAction(card.theme, if (busy) "…" else "拆", claimable && !busy, round = true) { respond("accept") }
                        }
                        if (amountVisible && (card.status != "accepted" || opened >= .56f)) Column(Modifier.padding(top = 28.dp).testTag("packet-amount-reveal"), horizontalAlignment = Alignment.CenterHorizontally) {
                            PacketAmount(card, state)
                        }
                    }
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
                if (claimable || busy) PearlAction(card.theme, if (busy) "处理中…" else "确认收款  ✦", claimable && !busy) { respond("accept") }
                else Column(Modifier.testTag("transfer-confirmation-reveal").semantics {
                    this[GiftRevealProgress] = if (card.status == "accepted") opened else 1f
                }.graphicsLayer {
                    // Animate the receipt itself, not the whole card or its scrolling bubble.
                    val progress = if (card.status == "accepted") opened else 1f
                    alpha = progress
                    scaleX = .82f + .18f * progress
                    scaleY = scaleX
                    translationY = (1f - progress) * 10.dp.toPx()
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.background(Color.White.copy(alpha = .75f), CircleShape)
                        .border(1.dp, Color.White, CircleShape).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (card.status == "accepted") Image(painterResource(R.drawable.interaction_check), null,
                            Modifier.size(16.dp), colorFilter = ColorFilter.tint(interactionColors(card.theme).accent))
                        Text(if (card.status == "accepted") "已确认收款" else state, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (claimable && !packet) Text("退还给对方", Modifier.padding(top = 24.dp).testTag("gift-return").clickable(
                enabled = !busy,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = { respond("return") },
            ).padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 11.sp, lineHeight = 15.sp,
                color = LocalContentColor.current.copy(alpha = .65f))
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
        if (packet) Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (claimable) {
                Text("退还给对方", Modifier.testTag("gift-return").clickable(
                    enabled = !busy, role = androidx.compose.ui.semantics.Role.Button,
                    onClick = { respond("return") },
                ).padding(horizontal = 12.dp), fontSize = 11.sp, lineHeight = 15.sp,
                    color = LocalContentColor.current.copy(alpha = .65f))
                Spacer(Modifier.height(10.dp))
            }
            Text(when (card.theme) { "sakura" -> "✧ 愿你今天也甜甜的 ✧"; "lavender" -> "✧ 晚安，愿你有好梦 ✧"; else -> "✧ 浮光跃金 · 静影沉璧 ✧" },
                Modifier.testTag("packet-footer"), fontSize = 12.sp, lineHeight = 16.sp,
                color = LocalContentColor.current.copy(alpha = .65f))
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = LocalContentColor.current.copy(alpha = .65f))
        Text(value, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
    }
}

internal val GiftRevealProgress = SemanticsPropertyKey<Float>("GiftRevealProgress")
internal val GlassScale = SemanticsPropertyKey<Float>("GlassScale")
internal val PacketOpeningProgress = SemanticsPropertyKey<Float>("PacketOpeningProgress")

@Composable
private fun PacketAmount(card: ChatInteraction, state: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(formatChatAmount(card.amountCents), fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
        Text("元", Modifier.padding(start = 4.dp, bottom = 4.dp), fontSize = 14.sp)
    }
    Text(state, Modifier.padding(top = 8.dp).background(Color.White.copy(alpha = .55f), CircleShape)
        .border(1.dp, Color.White.copy(alpha = .8f), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 11.sp)
}
