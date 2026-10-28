package me.rerere.rikkahub.ui.components.richtext

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RememberMarkdownParseTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstParseRunsOffMainAndAnObsoleteResultCannotReplaceNewContent() {
        val source = mutableStateOf("old")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val parsedOnMain = AtomicBoolean(false)
        val displayed = CopyOnWriteArrayList<String?>()
        val cache = MarkdownParseCache<String>()
        try {
            compose.setContent {
                val value by rememberMarkdownParse(source.value, cache, inlineMaxChars = 0) {
                    if (Looper.myLooper() == Looper.getMainLooper()) parsedOnMain.set(true)
                    if (it == "old") {
                        started.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        finished.countDown()
                    }
                    "parsed:$it"
                }
                SideEffect { displayed += value }
                Text(value ?: "pending")
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("pending").assertExists()
            compose.runOnIdle { source.value = "new" }
            compose.waitForIdle()
            // The obsolete CPU parse cannot be interrupted, but must not overlap the next one.
            assertNull(cache.get("new"))
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            compose.waitUntil(5000) { cache.get("new") != null }
            compose.waitForIdle()
            compose.onNodeWithText("parsed:new").assertExists()
            assertFalse(displayed.contains("parsed:old"))
            assertFalse(parsedOnMain.get())
        } finally {
            release.countDown()
        }
    }

    @Test fun shortUncachedMessageIsShownOnFirstFrame() {
        val displayed = CopyOnWriteArrayList<String?>()
        compose.setContent {
            val value by rememberMarkdownParse("hi", MarkdownParseCache<String>()) { "parsed:$it" }
            SideEffect { displayed += value }
            Text(value ?: "pending")
        }
        compose.onNodeWithText("parsed:hi").assertExists()
        assertEquals("parsed:hi", displayed.first())
    }
}
