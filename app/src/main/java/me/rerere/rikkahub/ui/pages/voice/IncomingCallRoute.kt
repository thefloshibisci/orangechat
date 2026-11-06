package me.rerere.rikkahub.ui.pages.voice

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.interaction.ChatInteraction
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.service.respondChatInteraction
import me.rerere.rikkahub.ui.context.LocalSettings
import kotlin.uuid.Uuid

@Composable
internal fun IncomingCallRoute(conversationId: Uuid, interactionId: String?, onCollapse: () -> Unit,
    onConnected: () -> Unit, stopSpeech: () -> Unit) {
    val service: ChatService = org.koin.compose.koinInject()
    val settings = LocalSettings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversation by remember(conversationId) { service.getConversationFlow(conversationId) }.collectAsStateWithLifecycle()
    LaunchedEffect(conversationId) { service.initializeConversation(conversationId, updateSelectedAssistant = false) }
    val invitation = conversation.currentMessages.flatMap { it.annotations }.filterIsInstance<ChatInteraction>()
        .lastOrNull { it.kind == "call" && it.actor == "assistant" &&
            (if (interactionId == null) it.status == "pending" else it.id == interactionId) }
    val assistant = settings.getAssistantById(conversation.assistantId)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(invitation?.id, invitation?.status) {
        if (invitation?.status == "pending") service.pendingIncomingCall.value = invitation
    }
    fun respond(action: String) {
        val card = invitation ?: return
        if (busy || card.status != "pending") return
        busy = true
        scope.launch {
            try {
                val result = service.respondChatInteraction(conversationId, card.id, "user", action)
                if (action == "accept" && result.status == "connecting") {
                    stopSpeech()
                    VoiceCallService.start(context, conversationId.toString())
                    onConnected()
                } else if (action == "decline" && result.status == "declined") {
                    service.notifyVoiceCallDeclined(conversationId)
                    onCollapse()
                }
            } catch (e: Exception) { error = e.message } finally { busy = false }
        }
    }
    IncomingCallPage(conversationId.toString(), assistant?.name?.ifBlank { "对方" } ?: "对方",
        theme = invitation?.theme ?: assistant?.chatInteractionTheme ?: "ice",
        onCollapse = onCollapse, busy = busy || invitation?.status != "pending",
        error = error ?: if (conversation.messageNodesLoaded && invitation?.status != "pending") "这条来电邀请已结束" else null,
        onStartCall = { respond("accept") }, onDecline = { respond("decline") })
}
