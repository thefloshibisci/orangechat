package me.rerere.rikkahub.ui.activity

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import me.rerere.rikkahub.data.service.AppLockStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppLockScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `assistant-only mode provides both exits without unlocking`() {
        AppLockStore.setRequirePin(compose.activity, "test.locked", false)
        var cancelled = false
        var unlocked = false
        var returned = false
        compose.setContent { MaterialTheme {
            AppLockUnlockScreen("test.locked", { unlocked = true }, { cancelled = true }, { returned = true })
        } }
        compose.onNodeWithText("已被西瓜瓣锁定").assertIsDisplayed()
        compose.onNodeWithText("test.locked").assertIsDisplayed()
        compose.onNodeWithText("返回桌面").performClick()
        compose.onNodeWithText("返回西瓜瓣，找助手解锁").performClick()
        compose.runOnIdle { assertTrue(cancelled); assertTrue(returned); assertFalse(unlocked) }
    }

    @Test fun `incorrect PIN cannot unlock and correct PIN does`() {
        AppLockStore.setPin(compose.activity, "1234")
        var unlocked = false
        var cancelled = false
        var returned = false
        compose.setContent { MaterialTheme {
            AppLockUnlockScreen("test.locked", { unlocked = true }, { cancelled = true }, { returned = true })
        } }
        compose.onNodeWithText("已被西瓜瓣锁定，输入密码后继续").assertIsDisplayed()
        compose.onNodeWithText("返回桌面").performClick()
        compose.onNodeWithText("返回西瓜瓣，找助手解锁").performClick()
        compose.runOnIdle { assertTrue(cancelled); assertTrue(returned); assertFalse(unlocked) }
        repeat(4) { compose.onNodeWithText("0").performScrollTo().performClick() }
        compose.onNodeWithText("密码错误").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertFalse(unlocked) }
        for (digit in listOf("1", "2", "3", "4")) compose.onNodeWithText(digit).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(unlocked) }
    }

    @Test fun `missing PIN explains recovery without offering an unusable keypad`() {
        var unlocked = false
        var cancelled = false
        var returned = false
        compose.setContent { MaterialTheme {
            AppLockUnlockScreen("test.locked", { unlocked = true }, { cancelled = true }, { returned = true })
        } }
        compose.onNodeWithText("还没有设置解锁密码，请返回西瓜瓣，让助手设置密码或解除此应用的锁定。")
            .assertIsDisplayed()
        compose.onNodeWithText("0").assertDoesNotExist()
        compose.onNodeWithText("返回桌面").performClick()
        compose.onNodeWithText("返回西瓜瓣，找助手解锁").performClick()
        compose.runOnIdle { assertTrue(cancelled); assertTrue(returned); assertFalse(unlocked) }
    }

    @Test fun `small screen and long lock message keep both exits visible while keypad scrolls`() {
        AppLockStore.setPin(compose.activity, "1234")
        AppLockStore.setLockMessage(compose.activity, "test.locked", "这是一条很长的锁定留言。".repeat(50))
        var cancelled = false
        var returned = false
        var unlocked = false
        compose.setContent { MaterialTheme {
            Box(Modifier.requiredSize(320.dp, 360.dp)) {
                AppLockUnlockScreen("test.locked", { unlocked = true }, { cancelled = true }, { returned = true })
            }
        } }
        compose.onNodeWithText("返回桌面").assertIsDisplayed().performClick()
        compose.onNodeWithText("返回西瓜瓣，找助手解锁").assertIsDisplayed().performClick()
        compose.onNodeWithText("0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("返回桌面").assertIsDisplayed()
        compose.onNodeWithText("返回西瓜瓣，找助手解锁").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(cancelled); assertTrue(returned); assertFalse(unlocked)
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/test-screenshots/applock-exits-small-screen.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
