package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import me.rerere.rikkahub.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.interaction.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.createChatInteraction
import me.rerere.rikkahub.service.respondChatInteraction
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

internal data class InteractionColors(val top: Color, val bottom: Color, val ink: Color, val accent: Color)
internal fun interactionColors(theme: String) = when (theme) {
    "sakura" -> InteractionColors(Color(0xFFFFE8F1).copy(alpha = .82f), Color(0xFFFFC8DE).copy(alpha = .58f), Color(0xFF833252), Color(0xFFCF85A4))
    "lavender" -> InteractionColors(Color(0xFFF4EAFF).copy(alpha = .84f), Color(0xFFD6C4FF).copy(alpha = .58f), Color(0xFF4A3074), Color(0xFFA28AC8))
    else -> InteractionColors(Color(0xFFECF8FF).copy(alpha = .84f), Color(0xFFBEE0FC).copy(alpha = .58f), Color(0xFF27476E), Color(0xFF8AAACB))
}

@Composable
internal fun InteractionGlass(theme: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = interactionColors(theme)
    Column(modifier.background(Brush.linearGradient(listOf(colors.top, colors.bottom)), RoundedCornerShape(24.dp))
        .border(1.5.dp, Color.White.copy(alpha = .88f), RoundedCornerShape(24.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CompositionLocalProvider(LocalContentColor provides colors.ink) { content() }
    }
}

@Composable
fun RichChatCard(card: ChatInteraction, conversationId: Uuid?, readOnly: Boolean, modifier: Modifier = Modifier,
    senderName: String = if (card.actor == "user") "你" else "对方") {
    val interactive = !readOnly && conversationId?.toString() == card.conversationId
    val service: ChatService? = if (interactive) koinInject() else null
    val scope = rememberCoroutineScope()
    val nav = if (interactive) LocalNavController.current else null
    val permission = rememberPermissionState(PermissionRecordAudio)
    var expanded by rememberSaveable(card.id) { mutableStateOf(false) }
    var busy by remember(card.id) { mutableStateOf(false) }
    var error by remember(card.id) { mutableStateOf<String?>(null) }
    val recipient = card.actor == "assistant"
    val title = when (card.kind) { "red_packet" -> "专属红包"; "transfer" -> "转账"; else -> "语音通话" }
    val illustration = when {
        card.kind == "call" && card.theme == "lavender" -> R.drawable.interaction_moon
        card.theme == "sakura" -> R.drawable.interaction_bear
        card.kind == "call" -> R.drawable.interaction_phone
        else -> R.drawable.interaction_fish
    }
    val state = when (card.status) {
        "syncing", "unconfirmed" -> "结果待核实"
        "failed" -> "未发出"
        "connecting" -> "正在连接"
        "accepted" -> if (card.kind == "call") "通话中" else "已领取"
        "returned" -> "已退回"
        "declined" -> "已拒绝"
        "ended" -> "通话已结束"
        else -> if (card.kind == "call") "等待接听" else "待领取"
    }
    val callState = when (card.status) {
        "connecting" -> "正在连接"
        "accepted" -> "通话中"
        "declined" -> "未接听"
        "ended" -> "已结束"
        else -> "等待接听"
    }
    fun respond(action: String) {
        if (!interactive || busy) return
        if (card.kind == "call" && action == "accept" && !permission.allRequiredPermissionsGranted) {
            permission.requestPermissions(); error = "请允许录音权限后，再点接听"; return
        }
        busy = true
        scope.launch {
            try {
                val result = requireNotNull(service).respondChatInteraction(conversationId!!, card.id, "user", action)
                error = result.error
                if (card.kind == "call" && result.status == "connecting" && action == "accept") {
                    expanded = false
                    nav?.navigate(Screen.VoiceCall(conversationId.toString()))
                }
            } catch (e: Exception) { error = e.message } finally { busy = false }
        }
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (card.actor == "user") Alignment.End else Alignment.Start) {
        if (card.kind == "call") {
            val colors = interactionColors(card.theme)
            val seconds = card.connectedAtMillis?.let {
                ((card.endedAtMillis ?: System.currentTimeMillis()) - it).coerceAtLeast(0) / 1000
            }
            Row(
                Modifier.widthIn(max = 280.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(colors.top, colors.bottom)))
                    .border(1.5.dp, Color.White.copy(alpha = .88f), RoundedCornerShape(20.dp))
                    .clickable { expanded = true }.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(36.dp).background(Color.White.copy(alpha = .75f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Image(painterResource(illustration), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(colors.accent))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(when (card.status) {
                        "pending" -> "语音通话邀请"
                        "declined" -> "语音通话 · 未接听"
                        "ended" -> if (seconds == null) "语音通话 · 已结束" else "通话结束 · %02d:%02d".format(seconds / 60, seconds % 60)
                        else -> if (seconds == null) "语音通话" else "语音通话 · %02d:%02d".format(seconds / 60, seconds % 60)
                    },
                        color = colors.ink, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("✦ $callState · 点击查看", color = colors.ink.copy(alpha = .75f), fontSize = 10.sp, lineHeight = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else GiftChatBubble(card, state, senderName) { expanded = true }
    }

    if (expanded) InteractionDetailDialog(onDismiss = { if (!busy) expanded = false }, enabled = !busy) { close ->
        if (card.kind != "call") {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                GiftDetailCard(card, state, interactive, busy, error, senderName, ::respond)
                Text(if (card.walletLinked) "关联小钱包账本" else "虚拟金额 · 装个大的", Modifier.padding(top = 10.dp), fontSize = 11.sp)
                TextButton(onClick = close, enabled = !busy) { Text("关闭") }
            }
        } else         InteractionGlass(card.theme, Modifier.fillMaxWidth()) {
            Image(painterResource(illustration), null, Modifier.size(56.dp))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(state)
            if (card.kind != "call" && !(card.kind == "red_packet" && card.status == "pending" && recipient)) Text("¥ ${formatChatAmount(card.amountCents)}", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            if (card.note.isNotBlank()) Text(card.note)
            if (card.kind != "call") Text(if (card.walletLinked) "关联小钱包账本" else "虚拟金额，不影响小钱包", style = MaterialTheme.typography.bodySmall)
            (error ?: card.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (interactive && card.walletLinked && card.status in setOf("syncing", "unconfirmed", "failed")) {
                Button(onClick = { respond("sync") }, enabled = !busy) { Text("核实这笔记录") }
            }
            if (interactive && recipient && card.status == "pending") {
                Button(onClick = { respond("accept") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(when (card.kind) { "call" -> "接听"; "red_packet" -> "拆开红包"; else -> "确认收款" })
                }
                TextButton(onClick = { respond(if (card.kind == "call") "decline" else "return") }, enabled = !busy) {
                    Text(if (card.kind == "call") "拒绝" else "退还给对方")
                }
            }
            TextButton(onClick = close, enabled = !busy) { Text("关闭") }
        }
    }
}

@Composable
fun SendChatGiftDialog(conversationId: Uuid, assistant: Assistant, kind: String, onDismiss: () -> Unit) {
    val service: ChatService = koinInject()
    val scope = rememberCoroutineScope()
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val requestId = rememberSaveable { Uuid.random().toString() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        InteractionGlass(assistant.chatInteractionTheme, Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(if (kind == "red_packet") "发红包" else "转账", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(amount, { amount = it }, label = { Text("金额（元）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (kind == "red_packet") Text("单个红包最高 200 元", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(note, { note = it.take(200) }, label = { Text("想说的话") }, maxLines = 3)
            Text(if (assistant.pretendRich) "装个大的已开启：使用虚拟金额" else "关联小钱包：对方领取后记入收入", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        parseChatGiftAmount(kind, amount)
                        val card = service.createChatInteraction(conversationId, assistant.id, "user", kind, amount, note, requestId)
                        if (card.status == "pending") service.answerChatGift(conversationId)
                        onDismiss()
                    } catch (e: Exception) { error = e.message } finally { busy = false }
                }
            }) { Text("发送") }
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    }
}

@Composable
private fun InteractionDetailDialog(onDismiss: () -> Unit, enabled: Boolean, content: @Composable (() -> Unit) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(if (visible) 1f else .90f,
        animationSpec = tween(350, easing = CubicBezierEasing(.34f, 1.56f, .64f, 1f)), label = "giftDetailScale")
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(180), label = "giftDetailAlpha")
    LaunchedEffect(Unit) { visible = true }
    val close: () -> Unit = {
        if (enabled) scope.launch { visible = false; kotlinx.coroutines.delay(350); onDismiss() }
        Unit
    }
    Dialog(onDismissRequest = close) {
        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }) { content(close) }
    }
}
