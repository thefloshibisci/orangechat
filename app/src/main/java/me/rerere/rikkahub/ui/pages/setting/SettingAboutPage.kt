/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.SecurityCheck
import me.rerere.hugeicons.stroke.SmartPhone01
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import me.rerere.rikkahub.ui.theme.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.easteregg.EmojiBurstHost
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.ProjectLinks
import me.rerere.rikkahub.utils.aboutBuildLabel
import me.rerere.rikkahub.utils.aboutUpdateStatus
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateInfo
import me.rerere.rikkahub.utils.UpdateChecker
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SettingAboutPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val navController = LocalNavController.current
    val updateChecker: UpdateChecker = koinInject()
    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UiState<UpdateInfo>>(UiState.Idle) }
    val emojiOptions = remember {
        listOf(
            "🎉", "✨", "🌟", "💫", "🎊", "🥳", "🎈", "🎆", "🎇", "🧨",
            "🌈", "🧧", "🎁", "🍬", "🍭", "🍉", "🍓", "🍒", "🍍", "🥭",
            "🐱", "🐶", "🦊", "🐼", "🦁", "🐯", "🐵", "🦄",
            "❤️", "🧡", "💛", "💚", "💙", "💜",
            "🇨🇳", "🌏", "🌍", "🌎",
            "🤗", "🤩", "😆", "😺", "😸", "🤡",
            "💡", "🔥", "💥", "🚀", "⭐", "🌙"
        )
    }
    var logoCenterPx by remember { mutableStateOf(Offset.Zero) }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.about_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = settingsScaffoldContainerColor(CustomColors.topBarColors.containerColor),
    ) { innerPadding ->
        EmojiBurstHost(
            modifier = Modifier.fillMaxSize(),
            emojiOptions = emojiOptions,
            burstCount = 12
        ) { onBurst ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AsyncImage(
                            model = R.mipmap.ic_launcher,
                            contentDescription = "Logo",
                            modifier = Modifier
                                .clip(CircleShape)
                                .size(150.dp)
                                .onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInParent()
                                    val size = coordinates.size
                                    logoCenterPx = Offset(
                                        position.x + size.width / 2f,
                                        position.y + size.height / 2f
                                    )
                                }
                                .clickable {
                                    onBurst(logoCenterPx)
                                }
                        )

                        Text(
                            text = "西瓜瓣",
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { navController.navigate(Screen.Debug) },
                                interactionSource = remember { MutableInteractionSource() },
                                indication = LocalIndication.current,
                            ),
                            leadingContent = { Icon(HugeIcons.Code, null) },
                            supportingContent = {
                                Text(aboutBuildLabel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString(),
                                    BuildConfig.GIT_COMMIT, BuildConfig.BUILD_TIME,
                                    BuildConfig.APPLICATION_ID, BuildConfig.BUILD_TYPE))
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_version)) },
                        )
                        item(
                            onClick = {
                                if (updateState !is UiState.Loading) {
                                    updateState = UiState.Loading
                                    scope.launch { updateChecker.checkUpdate(force = true).collect { updateState = it } }
                                }
                            },
                            leadingContent = { Icon(HugeIcons.Code, null) },
                            supportingContent = { Text(aboutUpdateStatus(updateState, BuildConfig.VERSION_NAME)) },
                            headlineContent = { Text("检查更新") },
                            trailingContent = {
                                if (updateState is UiState.Loading) CircularProgressIndicator(Modifier.size(24.dp))
                            },
                        )
                        item(
                            leadingContent = { Icon(HugeIcons.SmartPhone01, null) },
                            supportingContent = {
                                Text("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} / SDK ${android.os.Build.VERSION.SDK_INT}")
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_system)) },
                        )
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            onClick = { context.openUrl(ProjectLinks.HOME) },
                            leadingContent = { Icon(HugeIcons.Earth, null) },
                            supportingContent = {
                                Text(ProjectLinks.HOME)
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_website)) },
                        )
                        item(
                            onClick = { context.openUrl(ProjectLinks.HOME) },
                            leadingContent = { Icon(HugeIcons.Github, null) },
                            supportingContent = {
                                Text(ProjectLinks.HOME)
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_github)) },
                        )
                        item(
                            onClick = { navController.navigate(Screen.WebView(url = ProjectLinks.DOCUMENTATION)) },
                            leadingContent = { Icon(HugeIcons.File02, null) },
                            supportingContent = { Text("本项目使用说明") },
                            headlineContent = { Text("使用文档") },
                        )
                        item(
                            onClick = { context.openUrl(ProjectLinks.ISSUES) },
                            leadingContent = { Icon(HugeIcons.Github, null) },
                            supportingContent = { Text(ProjectLinks.ISSUES) },
                            headlineContent = { Text("问题反馈") },
                        )
                        item(
                            onClick = { context.openUrl(ProjectLinks.RELEASES) },
                            leadingContent = { Icon(HugeIcons.Earth, null) },
                            supportingContent = { Text("打开本项目发布页，按当前安装渠道核对") },
                            headlineContent = { Text("版本发布") },
                        )
                        item(
                            onClick = { context.openUrl(ProjectLinks.LICENSE) },
                            leadingContent = { Icon(HugeIcons.File02, null) },
                            supportingContent = {
                                Text(ProjectLinks.LICENSE)
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_license)) },
                        )
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            onClick = { context.openUrl(ProjectLinks.RIKKAHUB_UPSTREAM) },
                            leadingContent = { Icon(HugeIcons.Github, null) },
                            supportingContent = {
                                Text(stringResource(R.string.about_page_upstream_desc))
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_upstream_name)) },
                        )
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            onClick = {
                                navController.navigate(
                                    Screen.Legal(
                                        titleRes = R.string.legal_user_agreement_title,
                                        contentRes = R.string.legal_user_agreement_text
                                    )
                                )
                            },
                            leadingContent = { Icon(HugeIcons.File02, null) },
                            supportingContent = { Text(stringResource(R.string.legal_user_agreement_desc)) },
                            headlineContent = { Text(stringResource(R.string.legal_user_agreement_title)) },
                        )
                        item(
                            onClick = {
                                navController.navigate(
                                    Screen.Legal(
                                        titleRes = R.string.legal_privacy_policy_title,
                                        contentRes = R.string.legal_privacy_policy_text
                                    )
                                )
                            },
                            leadingContent = { Icon(HugeIcons.File02, null) },
                            supportingContent = { Text(stringResource(R.string.legal_privacy_policy_desc)) },
                            headlineContent = { Text(stringResource(R.string.legal_privacy_policy_title)) },
                        )
                        item(
                            onClick = {
                                navController.navigate(
                                    Screen.Legal(
                                        titleRes = R.string.legal_disclaimer_title,
                                        contentRes = R.string.legal_disclaimer_text
                                    )
                                )
                            },
                            leadingContent = { Icon(HugeIcons.SecurityCheck, null) },
                            supportingContent = { Text(stringResource(R.string.legal_disclaimer_desc)) },
                            headlineContent = { Text(stringResource(R.string.legal_disclaimer_title)) },
                        )
                        item(
                            onClick = {
                                navController.navigate(
                                    Screen.Legal(
                                        titleRes = R.string.legal_plugin_security_title,
                                        contentRes = R.string.legal_plugin_security_text
                                    )
                                )
                            },
                            leadingContent = { Icon(HugeIcons.SecurityCheck, null) },
                            supportingContent = { Text(stringResource(R.string.legal_plugin_security_desc)) },
                            headlineContent = { Text(stringResource(R.string.legal_plugin_security_title)) },
                        )
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.about_page_derivation_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    )
                }
            }
        }
    }
}
