/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.hugeicons.stroke.Voice
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.context.LocalToaster
import com.dokar.sonner.ToastType
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import me.rerere.rikkahub.ui.components.message.SendChatGiftDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.supportsDirectAudio
import me.rerere.ai.provider.AudioInputMode
import me.rerere.ai.provider.Model
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Tv01
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import me.rerere.rikkahub.data.ai.compressionModelLabelFor
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ui.ExtensionSelector
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalMcpServers
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState

private val LocalActionTileWidth = staticCompositionLocalOf { 80.dp }

@Composable
internal fun FilesPicker(
    conversation: Conversation,
    assistant: Assistant,
    state: ChatInputState,
    mcpManager: McpManager,
    onCompressContext: suspend (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int, mergeWithPrevious: Boolean, onProgress: (String) -> Unit) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    showInjectionSheet: Boolean,
    onShowInjectionSheetChange: (Boolean) -> Unit,
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
    onSendGift: (String) -> Unit,
) {
    // settings 仍保留给 InjectionQuickConfigSheet -> ExtensionSelector 使用（它需要完整 Settings）
    val toaster = LocalToaster.current
    val settings = LocalSettings.current
    val currentChatModel = settings.getChatModelForAssistant(conversation.assistantId)
    val mcpServers = LocalMcpServers.current
    val provider = currentChatModel?.findProvider(providers = settings.providers)
    val videoUnavailableReason = videoInputUnavailableReason(currentChatModel, provider)
    val audioUnavailableReason = audioInputUnavailableReason(currentChatModel, provider)
    val actionsScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val hasMoreActions by remember { derivedStateOf { actionsScrollState.maxValue > 0 } }
    val compressionModelLabel = settings.compressionModelLabelFor(assistant.id)
    val navController = LocalNavController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tileWidth = (maxWidth - 12.dp * 3) / 4
            CompositionLocalProvider(LocalActionTileWidth provides tileWidth) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(actionsScrollState),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TakePicButton(onLaunchCamera = onTakePic)
                    ImagePickButton(onClick = onPickImage)
                    FilePickButton(onClick = onPickFile)
                    // Always shown so the entries never silently disappear; tap explains why when unavailable.
                    VideoPickButton(enabled = videoUnavailableReason == null) {
                        if (videoUnavailableReason == null) onPickVideo()
                        else toaster.show(videoUnavailableReason, type = ToastType.Info)
                    }
                    AudioPickButton(enabled = audioUnavailableReason == null) {
                        if (audioUnavailableReason == null) onPickAudio()
                        else toaster.show(audioUnavailableReason, type = ToastType.Info)
                    }
                    GongduButton(onClick = {
                        onDismiss()
                        navController.navigate(Screen.Gongdu(conversationId = conversation.id.toString()))
                    })
                    BigIconTextButton(
                        icon = { Icon(HugeIcons.Voice, null) },
                        text = { Text("语音通话") },
                    ) {
                        val activeId = VoiceCallService.activeConversationId.value
                        if (activeId == null || activeId == conversation.id.toString()) {
                            onDismiss()
                            navController.navigate(Screen.VoiceCall(conversation.id.toString()))
                        } else {
                            toaster.show("当前有通话进行中，请先挂断", type = ToastType.Warning)
                        }
                    }
                    BigIconTextButton(icon = { Icon(androidx.compose.ui.res.painterResource(R.drawable.action_red_packet), null) }, text = { Text("红包") }) { onSendGift("red_packet") }
                    BigIconTextButton(icon = { Icon(androidx.compose.ui.res.painterResource(R.drawable.action_transfer), null) }, text = { Text("转账") }) { onSendGift("transfer") }
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (hasMoreActions) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        scope.launch {
                            actionsScrollState.animateScrollTo(
                                (actionsScrollState.value + actionsScrollState.viewportSize)
                                    .coerceAtMost(actionsScrollState.maxValue)
                            )
                        }
                    }) {
                        Text("更多")
                        Icon(HugeIcons.ArrowRight01, contentDescription = null)
                    }
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.fillMaxWidth()
        )

        if (mcpServers.isNotEmpty()) {
            McpPickerListItem(
                assistant = assistant,
                servers = mcpServers,
                mcpManager = mcpManager,
                onUpdateAssistant = onUpdateAssistant,
            )
        }

        // Extensions (Quick Messages + Prompt Injections + Skills)
        val activeCount =
            assistant.quickMessageIds.size + assistant.modeInjectionIds.size + assistant.lorebookIds.size + assistant.enabledSkills.size
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Package,
                    contentDescription = stringResource(R.string.assistant_page_tab_extensions),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.assistant_page_tab_extensions))
            },
            trailingContent = {
                if (activeCount > 0) {
                    Text(
                        text = activeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    onShowInjectionSheetChange(true)
                },
        )

        // Compress History Button
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Package01,
                    contentDescription = stringResource(R.string.chat_page_compress_context),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.chat_page_compress_context))
            },
            trailingContent = {
                if (conversation.visibleMessageNodes.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_page_message_count, conversation.visibleMessageNodes.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    onShowCompressDialogChange(true)
                },
        )
    }


    // Injection Bottom Sheet
    if (showInjectionSheet) {
        InjectionQuickConfigSheet(
            assistant = assistant,
            settings = settings,
            onUpdateAssistant = onUpdateAssistant,
            onDismiss = { onShowInjectionSheetChange(false) })
    }

    // Compress Context Dialog
    if (showCompressDialog) {
        CompressContextDialog(compressionModelLabel = compressionModelLabel, onDismiss = {
            onShowCompressDialogChange(false)
            onDismiss()
        }, onConfirm = { additionalPrompt, targetTokens, keepRecentMessages, mergeWithPrevious, onProgress ->
            onCompressContext(additionalPrompt, targetTokens, keepRecentMessages, mergeWithPrevious, onProgress)
        })
    }
}

@Composable
private fun InjectionQuickConfigSheet(
    assistant: Assistant, settings: Settings, onUpdateAssistant: (Assistant) -> Unit, onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp),
        ) {
            ExtensionSelector(
                assistant = assistant,
                settings = settings,
                onUpdate = onUpdateAssistant,
                modifier = Modifier.weight(1f),
                onNavigateToQuickMessages = {
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                        navController.navigate(Screen.QuickMessages)
                    }
                },
                onNavigateToPrompts = {
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                        navController.navigate(Screen.Prompts)
                    }
                },
                onNavigateToSkills = {
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                        navController.navigate(Screen.Skills)
                    }
                })

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ImagePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Image02, null)
    }, text = {
        Text(stringResource(R.string.photo))
    }) {
        onClick()
    }
}

@Composable
fun TakePicButton(onLaunchCamera: () -> Unit = {}) {
    val cameraPermission = rememberPermissionState(PermissionCamera)

    PermissionManager(
        permissionState = cameraPermission
    ) {
        BigIconTextButton(icon = {
            Icon(HugeIcons.Camera01, null)
        }, text = {
            Text(stringResource(R.string.take_picture))
        }) {
            if (cameraPermission.allRequiredPermissionsGranted) {
                onLaunchCamera()
            } else {
                cameraPermission.requestPermissions()
            }
        }
    }
}

private fun ProviderSetting?.typeLabel(): String = when (this) {
    null -> "未找到"
    is ProviderSetting.Google -> "Google"
    is ProviderSetting.OpenAI -> if (useResponseApi) "OpenAI（Responses API）" else "OpenAI"
    is ProviderSetting.Claude -> "Claude"
    is ProviderSetting.Codex -> "Codex"
}

/** Null when the current chat model can take video; otherwise a user-facing reason naming the resolved provider. */
internal fun videoInputUnavailableReason(model: Model?, provider: ProviderSetting?): String? = when {
    model == null -> "当前助手没有可用的聊天模型，无法发送视频"
    provider is ProviderSetting.Google -> null
    else -> "视频仅支持 Google 类型供应商。当前模型 ${model.modelId} 实际使用的供应商类型：${provider.typeLabel()}" +
        if (model.providerOverwrite != null) "（该模型设置了单独的供应商覆盖）" else ""
}

/** Null when the current chat model can take raw audio; otherwise a user-facing reason. */
internal fun audioInputUnavailableReason(model: Model?, provider: ProviderSetting?): String? = when {
    model == null -> "当前助手没有可用的聊天模型，无法发送音频"
    model.supportsDirectAudio(provider) -> null
    model.audioInputMode == AudioInputMode.ASR -> "当前模型 ${model.modelId} 的音频输入设为“语音转写”，不能直接发送音频文件"
    provider !is ProviderSetting.Google && !(provider is ProviderSetting.OpenAI && !provider.useResponseApi) ->
        "当前模型 ${model.modelId} 实际使用的供应商类型（${provider.typeLabel()}）不支持直接发送音频" +
            if (model.providerOverwrite != null) "（该模型设置了单独的供应商覆盖）" else ""
    else -> "无法确认模型 ${model.modelId} 支持音频。可在模型设置里把音频输入改为“直接发送音频”"
}

@Composable
fun VideoPickButton(enabled: Boolean = true, onClick: () -> Unit = {}) {
    BigIconTextButton(enabled = enabled, icon = {
        Icon(HugeIcons.Video01, null)
    }, text = {
        Text(stringResource(R.string.video))
    }) {
        onClick()
    }
}

@Composable
fun AudioPickButton(enabled: Boolean = true, onClick: () -> Unit = {}) {
    BigIconTextButton(enabled = enabled, icon = {
        Icon(HugeIcons.MusicNote03, null)
    }, text = {
        Text(stringResource(R.string.audio))
    }) {
        onClick()
    }
}

@Composable
fun FilePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Files02, null)
    }, text = {
        Text(stringResource(R.string.upload_file))
    }) {
        onClick()
    }
}

@Composable
fun GongduButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Tv01, null)
    }, text = {
        Text("共度")
    }) {
        onClick()
    }
}

@Composable
private fun BigIconTextButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick
            )
            .semantics {
                role = Role.Button
            }
            .width(LocalActionTileWidth.current)
            .alpha(if (enabled) 1f else 0.45f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp, shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
        }
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            text()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BigIconTextButtonPreview() {
    Row(
        modifier = Modifier.padding(16.dp)
    ) {
        BigIconTextButton(icon = {
            Icon(HugeIcons.Image02, null)
        }, text = {
            Text(stringResource(R.string.photo))
        }) {}
    }
}
