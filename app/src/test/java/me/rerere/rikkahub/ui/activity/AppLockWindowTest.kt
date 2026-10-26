package me.rerere.rikkahub.ui.activity

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import me.rerere.rikkahub.data.service.AppLockGuard
import me.rerere.rikkahub.data.service.AppLockStore
import me.rerere.rikkahub.service.RikkaAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppLockWindowTest {
    private val overlayField = AppLockGuard::class.java.getDeclaredField("overlay").apply { isAccessible = true }

    private fun withLockedApp(block: (Application, RikkaAccessibilityService) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val controller = Robolectric.buildService(RikkaAccessibilityService::class.java).create()
        val service = controller.get()
        RikkaAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
            isAccessible = true
        }.invoke(service)
        try {
            AppLockStore.lockApp(context, "test.locked")
            AppLockGuard.reArmLock("test.locked")
            AppLockGuard.init(context)
            setUnderlyingApp(service, "test.locked", context.packageName)
            AppLockGuard.onForegroundChange("test.locked")
            assertNotNull(overlayField.get(AppLockGuard))
            block(context, service)
        } finally {
            controller.destroy()
        }
    }

    private fun setUnderlyingApp(service: RikkaAccessibilityService, pkg: String, self: String) {
        val app = AccessibilityWindowInfo.obtain()
        shadowOf(app).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(app).setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
        // The real app is neither active nor focused when the lock overlay owns focus.
        val overlay = AccessibilityWindowInfo.obtain()
        shadowOf(overlay).setType(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
        shadowOf(overlay).setActive(true)
        shadowOf(overlay).setFocused(true)
        val overlayRoot = AccessibilityNodeInfo.obtain().apply { packageName = self }
        shadowOf(overlay).setRoot(overlayRoot)
        shadowOf(service).setRootInActiveWindow(overlayRoot)
        shadowOf(service).setWindows(listOf(overlay, app))
    }

    @Test fun `home exits only after a safe foreground transition and never unlocks target`() = withLockedApp { context, service ->
        val window = overlayField.get(AppLockGuard)
        AppLockGuard.onForegroundChange(context.packageName) // Event from our own overlay.
        assertSame(window, overlayField.get(AppLockGuard))
        assertTrue(AppLockGuard.goHome())
        assertSame(window, overlayField.get(AppLockGuard)) // Accepted does not mean completed.
        setUnderlyingApp(service, "test.launcher", context.packageName)
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        assertNull(overlayField.get(AppLockGuard))
        assertTrue(AppLockStore.isLocked(context, "test.locked"))
        setUnderlyingApp(service, "test.locked", context.packageName)
        AppLockGuard.onForegroundChange("test.locked")
        assertNotNull(overlayField.get(AppLockGuard))
    }

    @Test fun `return to own app removes overlay after launch without granting grace`() = withLockedApp { context, service ->
        assertTrue(AppLockGuard.returnToApp())
        val launch = shadowOf(context).nextStartedActivity
        assertEquals(context.packageName, launch.component!!.packageName)
        assertNotNull(overlayField.get(AppLockGuard))
        setUnderlyingApp(service, context.packageName, context.packageName)
        AppLockGuard.onForegroundChange(context.packageName)
        assertNull(overlayField.get(AppLockGuard))
        assertTrue(AppLockStore.isLocked(context, "test.locked"))
        setUnderlyingApp(service, "test.locked", context.packageName)
        AppLockGuard.onForegroundChange("test.locked")
        assertNotNull(overlayField.get(AppLockGuard))
    }

    @Test fun `missing application window cannot mistake overlay root for safe exit`() = withLockedApp { context, service ->
        shadowOf(service).setWindows(emptyList())
        // Preserve the existing fallback used by screen context injection outside lock handling.
        assertEquals(context.packageName, service.currentApplicationPackage())
        assertNull(service.currentApplicationPackage(beneathOverlay = true))
        AppLockGuard.onForegroundChange(context.packageName)
        AppLockGuard.onWindowsChanged()
        assertNotNull(overlayField.get(AppLockGuard))
    }

    @Test
    @Config(shadows = [RejectHomeAction::class])
    fun `rejected home action falls back to launcher and keeps lock until transition`() = withLockedApp { context, _ ->
        assertTrue(AppLockGuard.goHome())
        val home = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, home.action)
        assertTrue(home.hasCategory(Intent.CATEGORY_HOME))
        assertNotNull(overlayField.get(AppLockGuard))
        assertTrue(AppLockStore.isLocked(context, "test.locked"))
    }

    @Test fun `locked app gets visible service window without home bounce or activity launch`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val controller = Robolectric.buildService(RikkaAccessibilityService::class.java).create()
        val service = controller.get()
        RikkaAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
            isAccessible = true
        }.invoke(service)
        try {
            AppLockStore.lockApp(context, "test.locked.one")
            AppLockStore.lockApp(context, "test.locked.two")
            AppLockGuard.init(context)
            AppLockGuard.onForegroundChange("test.locked.one")
            val overlayField = AppLockGuard::class.java.getDeclaredField("overlay").apply { isAccessible = true }
            val window = overlayField.get(AppLockGuard) as AppLockOverlay
            assertEquals(Lifecycle.State.RESUMED, window.lifecycle.currentState)
            assertFalse(shadowOf(service).globalActionsPerformed.contains(AccessibilityService.GLOBAL_ACTION_HOME))
            assertNull(shadowOf(context).nextStartedActivity)

            // Immediate same-package events and another locked app reuse a visible window.
            AppLockGuard.onForegroundChange("test.locked.one")
            assertSame(window, overlayField.get(AppLockGuard))
            AppLockGuard.onForegroundChange("test.locked.two")
            assertSame(window, overlayField.get(AppLockGuard))
            assertEquals("test.locked.two", window.targetPackage)

            service.onUnbind(Intent())
            assertNull(overlayField.get(AppLockGuard))
            assertEquals(Lifecycle.State.DESTROYED, window.lifecycle.currentState)
        } finally {
            controller.destroy()
        }
    }
}

@Implements(AccessibilityService::class)
class RejectHomeAction : ShadowAccessibilityService() {
    @Implementation
    override fun performGlobalAction(action: Int): Boolean = false
}
