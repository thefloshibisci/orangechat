/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.provider.AudioInputMode
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelCapabilityMode
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.supportsDirectAudio
import me.rerere.ai.registry.ModelRegistry
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.ModelCapabilitySync
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomBodies
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomHeaders
import org.koin.compose.koinInject

@Composable
internal fun ModelSettingsForm(
    model: Model,
    onModelChange: (Model) -> Unit,
    isEdit: Boolean,
    parentProvider: ProviderSetting? = null,
    enableAutomatic: (ProviderSetting, Model) -> Model = koinInject<ModelCapabilitySync>()::enableAutomatic,
) {
    val pagerState = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()

    fun setModelId(id: String) {
        val inputModality = ModelRegistry.MODEL_INPUT_MODALITIES.getData(id)
        val outputModality = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id)
        val abilities = ModelRegistry.MODEL_ABILITIES.getData(id)
        onModelChange(
            model.copy(
                modelId = id,
                displayName = id,
                inputModalities = inputModality,
                outputModalities = outputModality,
                abilities = abilities,
                capabilityMode = ModelCapabilityMode.AUTO,
                capabilitySource = "builtin",
            ),
        )
    }

    Column {
        SecondaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
        ) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(0)
                    }
                },
                text = { Text(stringResource(R.string.setting_provider_page_basic_settings)) },
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(1)
                    }
                },
                text = { Text(stringResource(R.string.setting_provider_page_advanced_settings)) },
            )
            Tab(
                selected = pagerState.currentPage == 2,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(2)
                    }
                },
                text = { Text(stringResource(R.string.setting_page_built_in_tools)) },
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
            when (page) {
                0 -> {
                    // 基本设置页面
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 12.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        OutlinedTextField(
                            value = model.modelId,
                            onValueChange = {
                                if (!isEdit) {
                                    setModelId(it.trim())
                                }
                            },
                            label = { Text(stringResource(R.string.setting_provider_page_model_id)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                if (!isEdit) {
                                    Text(stringResource(R.string.setting_provider_page_model_id_placeholder))
                                }
                            },
                            enabled = !isEdit,
                        )

                        OutlinedTextField(
                            value = model.displayName,
                            onValueChange = {
                                onModelChange(model.copy(displayName = it.trim()))
                            },
                            label = {
                                Text(
                                    stringResource(
                                        if (isEdit) R.string.setting_provider_page_model_name
                                        else R.string.setting_provider_page_model_display_name,
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                if (!isEdit) {
                                    Text(stringResource(R.string.setting_provider_page_model_display_name_placeholder))
                                }
                            },
                        )

                        ModelTypeSelector(
                            selectedType = model.type,
                            onTypeSelected = {
                                onModelChange(model.copy(type = it))
                            },
                        )

                        run {
                            val usesCodexMetadata = (model.providerOverwrite ?: parentProvider) is ProviderSetting.Codex
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("自动识别模型能力")
                                    Text(
                                        when {
                                            model.capabilityMode != ModelCapabilityMode.AUTO ->
                                                "使用手动设置，自动更新不会覆盖当前能力"
                                            usesCodexMetadata ->
                                                "跟随 Codex 官方资料，在登录或刷新模型列表时更新"
                                            model.capabilitySource?.startsWith("models.dev:") == true ->
                                                "来源：${model.capabilitySource}"
                                            else -> "使用内置资料，联网精确匹配成功后自动更新"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = model.capabilityMode == ModelCapabilityMode.AUTO,
                                    onCheckedChange = { enabled ->
                                        onModelChange(
                                            if (enabled && parentProvider != null) {
                                                enableAutomatic(parentProvider, model)
                                            } else {
                                                model.copy(
                                                    capabilityMode = ModelCapabilityMode.MANUAL,
                                                    capabilitySource = null,
                                                )
                                            },
                                        )
                                    },
                                )
                            }
                        }

                        ModelModalitySelector(
                            model = model,
                            inputModalities = model.inputModalities,
                            onUpdateInputModalities = {
                                onModelChange(
                                    model.copy(
                                        inputModalities = it,
                                        capabilityMode = ModelCapabilityMode.MANUAL,
                                        capabilitySource = null,
                                    ),
                                )
                            },
                            outputModalities = model.outputModalities,
                            onUpdateOutputModalities = {
                                onModelChange(
                                    model.copy(
                                        outputModalities = it,
                                        capabilityMode = ModelCapabilityMode.MANUAL,
                                        capabilitySource = null,
                                    ),
                                )
                            },
                        )

                        if (model.type == ModelType.CHAT) {
                            ModalAbilitySelector(
                                abilities = model.abilities,
                                onUpdateAbilities = {
                                    onModelChange(
                                        model.copy(
                                            abilities = it,
                                            capabilityMode = ModelCapabilityMode.MANUAL,
                                            capabilitySource = null,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                1 -> {
                    // 高级设置页面
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (model.type == ModelType.CHAT) {
                            ModelAudioInputSetting(model, parentProvider, onModelChange)
                        }
                        ProviderOverrideSettings(
                            providerOverride = model.providerOverwrite,
                            onUpdateProviderOverride = { providerOverride ->
                                onModelChange(model.copy(providerOverwrite = providerOverride))
                            },
                            parentProvider = parentProvider,
                        )

                        CustomHeaders(
                            headers = model.customHeaders,
                            onUpdate = { headers ->
                                onModelChange(model.copy(customHeaders = headers))
                            },
                        )

                        CustomBodies(
                            customBodies = model.customBodies,
                            onUpdate = { bodies ->
                                onModelChange(model.copy(customBodies = bodies))
                            },
                        )
                    }
                }

                2 -> {
                    // 内置工具页面
                    BuiltInToolsSettings(
                        tools = model.tools,
                        onUpdateTools = { tools ->
                            onModelChange(model.copy(tools = tools))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelAudioInputSetting(
    model: Model,
    parentProvider: ProviderSetting?,
    onModelChange: (Model) -> Unit,
) {
    val provider = model.providerOverwrite ?: parentProvider
    val direct = model.supportsDirectAudio(provider)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("语音输入方式", style = MaterialTheme.typography.titleSmall)
        Select(
            options = AudioInputMode.entries,
            selectedOption = model.audioInputMode,
            onOptionSelected = { onModelChange(model.copy(audioInputMode = it)) },
            optionToString = { mode ->
                when (mode) {
                    AudioInputMode.AUTO -> "自动"
                    AudioInputMode.AUDIO -> "直接发送音频"
                    AudioInputMode.ASR -> "先转为文字"
                }
            },
        )
        Text(
            text = when {
                model.audioInputMode == AudioInputMode.ASR -> "使用已配置的语音识别服务，将录音转为文字后发送。"
                direct -> "当前模型会接收原始录音。如果服务不支持音频，可改为先转为文字。"
                model.audioInputMode == AudioInputMode.AUDIO -> "当前通道不支持直接发送音频，录音将先转为文字。"
                else -> "当前模型默认先将录音转为文字；支持的 Gemini 或 GPT 音频模型会通过兼容通道接收原始录音。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AddModelButton(
    models: List<Model>,
    selectedModels: List<Model>,
    expanded: Boolean,
    onAddModel: (Model) -> Unit,
    onRemoveModel: (Model) -> Unit,
    parentProvider: ProviderSetting,
    onUpdateProvider: (ProviderSetting) -> Unit,
) {
    val dialogState = useEditState<Model> { onAddModel(it) }
    val scope = rememberCoroutineScope()

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelPicker(
            models = models,
            useProviderMetadata = parentProvider is ProviderSetting.Codex,
            selectedModels = selectedModels,
            onModelSelected = { model ->
                val inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId)
                val outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId)
                val abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId)
                onAddModel(
                    if (parentProvider is ProviderSetting.Codex) {
                        model
                    } else {
                        model.copy(
                            inputModalities = inputModalities,
                            outputModalities = outputModalities,
                            abilities = abilities,
                            capabilityMode = ModelCapabilityMode.AUTO,
                            capabilitySource = "builtin",
                        )
                    },
                )
            },
            onModelDeselected = { model ->
                onRemoveModel(model)
            },
            onAllModelSelected = {
                onUpdateProvider(
                    parentProvider.copyProvider(
                        models = parentProvider.models + it.filter { model ->
                            parentProvider.models.none { existing -> existing.modelId == model.modelId }
                        }.map { model ->
                            if (parentProvider is ProviderSetting.Codex) {
                                model
                            } else {
                                model.copy(
                                    inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId),
                                    outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId),
                                    abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId),
                                    capabilityMode = ModelCapabilityMode.AUTO,
                                    capabilitySource = "builtin",
                                )
                            }
                        },
                    ),
                )
            },
            onAllModelDeselected = { filteredModels ->
                onUpdateProvider(
                    parentProvider.copyProvider(
                        models = parentProvider.models.filter { model ->
                            filteredModels.none { filtered -> filtered.modelId == model.modelId }
                        },
                    ),
                )
            },
        )

        Button(
            onClick = {
                dialogState.open(Model(capabilityMode = ModelCapabilityMode.AUTO))
            },
        ) {
            Row(
                modifier = Modifier,
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    HugeIcons.Add01,
                    contentDescription = stringResource(R.string.setting_provider_page_add_model),
                )
                AnimatedVisibility(expanded) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.setting_provider_page_add_new_model),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }

    if (dialogState.isEditing) {
        dialogState.currentState?.let { modelState ->
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = {
                    dialogState.dismiss()
                },
                sheetState = sheetState,
                sheetGesturesEnabled = false,
                dragHandle = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                dialogState.dismiss()
                            }
                        },
                    ) {
                        Icon(HugeIcons.ArrowDown01, null)
                    }
                },
            ) {
                ModelEditorLayout(
                    modifier = Modifier.fillMaxHeight(0.95f),
                    title = {
                        Text(
                            stringResource(R.string.setting_provider_page_add_model),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    content = {
                        ModelSettingsForm(
                            model = modelState,
                            onModelChange = { dialogState.currentState = it },
                            isEdit = false,
                            parentProvider = parentProvider,
                        )
                    },
                    actions = {
                        TextButton(onClick = { dialogState.dismiss() }) { Text(stringResource(R.string.cancel)) }
                        TextButton(
                            onClick = {
                                if (modelState.modelId.isNotBlank() && modelState.displayName.isNotBlank()) {
                                    dialogState.confirm()
                                }
                            },
                        ) { Text(stringResource(R.string.setting_provider_page_add)) }
                    },
                )
            }
        }
    }
}
