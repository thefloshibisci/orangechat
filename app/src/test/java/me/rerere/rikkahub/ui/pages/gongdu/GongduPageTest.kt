package me.rerere.rikkahub.ui.pages.gongdu

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import me.rerere.rikkahub.data.gongdu.GongduConfiguration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class GongduPageTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unconfiguredEntryExplainsSetupAndHasNoFakeFeed() {
        var clicked = false
        compose.setContent { MaterialTheme { Surface { GongduEmptyService { clicked = true } } } }
        compose.onNodeWithText("一起逛小红书").assertExists()
        compose.onNodeWithText("配置小红书服务").performClick()
        assertTrue(clicked)
    }
    @Test fun setupRequiresExplicitSaveAndDoesNotOfferQrBeforeAConnection() {
        var saved: Pair<String, String>? = null
        compose.setContent { MaterialTheme { Surface { GongduSetup(GongduConfiguration(), "尚未连接", false, "", false,
            false, { url, token -> saved = url to token }, {}, {}, {}, {}) } } }
        // Unconfigured setup opens the advanced section so the address is reachable at once.
        compose.onNodeWithText("保存配置").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("服务地址").performScrollTo().performTextInput("https://example.test/mcp")
        compose.onNodeWithText("访问令牌（如服务需要）").performScrollTo().performTextInput("test-token")
        assertNull(saved)
        compose.onNodeWithText("保存配置").performScrollTo().performClick()
        assertEquals("https://example.test/mcp" to "test-token", saved)
        compose.onNodeWithText("扫码登录").performScrollTo().assertIsNotEnabled()
    }
}
