package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SystemToolsSetting
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtraInjectionRecompositionTest {
    @get:Rule val compose = createComposeRule()

    private val loaded = Settings(systemToolsSetting = SystemToolsSetting(
        extraInfoInjectionEnabled = true,
        extraInfoInProactiveEnabled = true,
        timeContextInjectionEnabled = true,
        batteryContextInjectionEnabled = true,
    ))

    private fun show(settings: MutableStateFlow<Settings>) {
        compose.setContent {
            CompositionLocalProvider(LocalDisplaySettings provides DisplaySetting()) {
                MaterialTheme {
                    ExtraInjectionSettingsContent(
                        settingsFlow = settings,
                        updateSystemToolsSetting = { update ->
                            settings.value = settings.value.copy(systemToolsSetting = update(settings.value.systemToolsSetting))
                        },
                        navigationIcon = {},
                    )
                }
            }
        }
    }

    // The first two switches are visible on entry; do not scroll to find test targets.
    private fun toggle(label: String) = compose.onAllNodes(isToggleable(), useUnmergedTree = true)[
        when (label) {
            "启用额外信息" -> 0
            "用于主动消息" -> 1
            else -> error("Unknown test target")
        }
    ]

    @Test fun loadedSettingsReplaceDisabledInitialControlsWithoutScrolling() {
        val settings = MutableStateFlow(Settings(init = true, providers = emptyList()))
        show(settings)
        toggle("启用额外信息").assertIsNotEnabled()
        toggle("用于主动消息").assertIsOff().assertIsNotEnabled()
        compose.runOnIdle { settings.value = loaded }
        toggle("启用额外信息").assertIsOn().assertIsEnabled()
        toggle("用于主动消息").assertIsOn().assertIsEnabled()
        compose.onNodeWithText("正在读取设置…").assertDoesNotExist()
    }

    @Test fun masterSwitchUpdatesDependentControlsWithoutScrolling() {
        show(MutableStateFlow(loaded))
        toggle("用于主动消息").assertIsOn().assertIsEnabled()
        toggle("启用额外信息").performClick().assertIsOff()
        toggle("用于主动消息").assertIsOn().assertIsNotEnabled()
        toggle("启用额外信息").performClick().assertIsOn()
        toggle("用于主动消息").assertIsOn().assertIsEnabled()
        toggle("用于主动消息").performClick().assertIsOff()
    }
}
