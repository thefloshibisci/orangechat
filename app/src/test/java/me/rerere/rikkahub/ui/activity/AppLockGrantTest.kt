package me.rerere.rikkahub.ui.activity

import android.app.Application
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import me.rerere.rikkahub.data.service.AppLockGuard
import me.rerere.rikkahub.data.service.AppLockStore
import me.rerere.rikkahub.service.RikkaAccessibilityService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppLockGrantTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var controller: ServiceController<RikkaAccessibilityService>
    private lateinit var service: RikkaAccessibilityService
    private val overlayField = AppLockGuard::class.java.getDeclaredField("overlay").apply { isAccessible = true }
    private val onUnlockField = AppLockOverlay::class.java.getDeclaredField("onUnlock").apply { isAccessible = true }

    @Before fun setUp() {
        AppLockGuard.onServiceDisconnected()
        AppLockStore.setPin(context, "1234")
        AppLockStore.lockApp(context, TARGET)
        AppLockGuard.init(context)
        AppLockGuard.reArmLock(TARGET)
        AppLockGuard.reArmLock(OTHER)
        controller = Robolectric.buildService(RikkaAccessibilityService::class.java).create()
        service = controller.get()
        RikkaAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
            isAccessible = true
        }.invoke(service)
        foreground(TARGET)
        AppLockGuard.onForegroundChange(TARGET)
        assertNotNull(window())
    }

    @After fun tearDown() { controller.destroy() }

    private fun window() = overlayField.get(AppLockGuard) as AppLockOverlay?

    @Suppress("UNCHECKED_CAST")
    private fun unlockCallback(window: AppLockOverlay) =
        onUnlockField.get(window) as (AppLockOverlayTarget) -> Unit

    private fun foreground(packageName: String) {
        val app = AccessibilityWindowInfo.obtain()
        shadowOf(app).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(app).setRoot(AccessibilityNodeInfo.obtain().apply { this.packageName = packageName })
        val overlay = AccessibilityWindowInfo.obtain()
        shadowOf(overlay).setType(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
        shadowOf(overlay).setActive(true)
        shadowOf(overlay).setFocused(true)
        val overlayRoot = AccessibilityNodeInfo.obtain().apply { this.packageName = context.packageName }
        shadowOf(overlay).setRoot(overlayRoot)
        shadowOf(service).setRootInActiveWindow(overlayRoot)
        shadowOf(service).setWindows(listOf(overlay, app))
    }

    @Test fun `successful PIN grant survives duplicate own-layer events and unchanged settings refresh`() {
        val window = window()!!
        assertTrue(AppLockStore.verifyPin(context, "1234"))
        unlockCallback(window)(window.target)
        assertNull(window())
        repeat(3) {
            AppLockGuard.onForegroundChange(TARGET)
            AppLockGuard.onForegroundChange(context.packageName)
            service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            AppLockGuard.refresh()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(window())
        }
        assertTrue(AppLockStore.isLocked(context, TARGET))
        // The established policy has no leave/timeout revocation; keep it unchanged.
        foreground("test.other")
        AppLockGuard.onForegroundChange("test.other")
        foreground(TARGET)
        AppLockGuard.onForegroundChange(TARGET)
        assertNull(window())
    }

    @Test fun `incorrect PIN leaves the active target intercepted`() {
        val window = window()!!
        assertFalse(AppLockStore.verifyPin(context, "0000"))
        AppLockGuard.onForegroundChange(TARGET)
        AppLockGuard.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(window, window())
        assertTrue(AppLockStore.isLocked(context, TARGET))
    }

    @Test fun `late event from another locked app does not retarget the confirmed application underneath`() {
        val window = window()!!
        AppLockStore.lockApp(context, OTHER)
        AppLockGuard.onForegroundChange(OTHER)
        assertSame(window, window())
        assertEquals(TARGET, window.targetPackage)
        unlockCallback(window)(window.target)
        assertNull(window())
        foreground(OTHER)
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        assertEquals(OTHER, window()!!.targetPackage)
    }

    @Test fun `old target success cannot grant or dismiss a newly selected locked target`() {
        val window = window()!!
        val oldTarget = window.target
        val callback = unlockCallback(window)
        AppLockStore.lockApp(context, OTHER)
        foreground(OTHER)
        AppLockGuard.onForegroundChange(OTHER)
        assertSame(window, window())
        assertEquals(OTHER, window.targetPackage)
        callback(oldTarget)
        assertSame(window, window())
        callback(window.target)
        assertNull(window())
        foreground(TARGET)
        AppLockGuard.onForegroundChange(TARGET)
        assertEquals(TARGET, window()!!.targetPackage)
    }

    @Test fun `target switch before its event is delivered cannot expose the new locked app`() {
        val window = window()!!
        val oldTarget = window.target
        val callback = unlockCallback(window)
        AppLockStore.lockApp(context, OTHER)
        foreground(OTHER)
        // The old keypad callback beats the delayed foreground event.
        callback(oldTarget)
        assertSame(window, window())
        assertEquals(OTHER, window.targetPackage)
        foreground(TARGET)
        AppLockGuard.onForegroundChange(TARGET)
        assertEquals(TARGET, window()!!.targetPackage)
    }

    @Test fun `rearming same target rejects an old valid PIN callback and accepts a fresh challenge`() {
        val window = window()!!
        val oldTarget = window.target
        val callback = unlockCallback(window)
        assertTrue(AppLockStore.verifyPin(context, "1234"))
        AppLockGuard.reArmLock(TARGET)
        callback(oldTarget)
        assertSame(window, window())
        assertNotEquals(oldTarget, window.target)
        callback(window.target)
        assertNull(window())
        AppLockGuard.onForegroundChange(TARGET)
        assertNull(window())
    }

    @Test fun `callback from a removed window cannot unlock a newer interception of the same app`() {
        val oldWindow = window()!!
        val oldTarget = oldWindow.target
        val oldCallback = unlockCallback(oldWindow)
        oldCallback(oldTarget)
        assertNull(window())
        AppLockGuard.reArmLock(TARGET)
        AppLockGuard.onForegroundChange(TARGET)
        val newWindow = window()!!
        assertNotSame(oldWindow, newWindow)
        oldCallback(oldTarget)
        assertSame(newWindow, window())
    }

    @Test fun `PIN replacement revokes existing grant and stale screen without clearing the lock`() {
        val oldWindow = window()!!
        val oldTarget = oldWindow.target
        val callback = unlockCallback(oldWindow)
        AppLockStore.setPin(context, "567890")
        callback(oldTarget)
        assertSame(oldWindow, window())
        assertNotEquals(oldTarget, oldWindow.target)
        assertFalse(AppLockStore.verifyPin(context, "1234"))
        assertTrue(AppLockStore.verifyPin(context, "567890"))
        callback(oldWindow.target)
        assertNull(window())
        AppLockStore.setPin(context, "2468")
        AppLockGuard.onForegroundChange(TARGET)
        assertNotNull(window())
        assertTrue(AppLockStore.isLocked(context, TARGET))
    }

    @Test fun `unlock then relock and assistant-only mode both reject a stale successful callback`() {
        val window = window()!!
        val oldTarget = window.target
        val callback = unlockCallback(window)
        AppLockStore.unlockApp(context, TARGET)
        AppLockStore.lockApp(context, TARGET)
        callback(oldTarget)
        assertSame(window, window())
        val relockedTarget = window.target
        AppLockStore.setRequirePin(context, TARGET, false)
        callback(relockedTarget)
        assertSame(window, window())
        assertNull(window.target.unlockToken)
        assertFalse(AppLockGuard.grantGraceUnlock(TARGET, relockedTarget.unlockToken))
    }

    @Test fun `unknown underlying app keeps a locked event intercepted and never trusts own overlay root`() {
        val window = window()!!
        shadowOf(service).setWindows(emptyList())
        AppLockGuard.onForegroundChange(context.packageName)
        AppLockGuard.onWindowsChanged()
        assertSame(window, window())
        AppLockGuard.onForegroundChange(TARGET)
        assertSame(window, window())
    }

    @Test fun `locks saved before revision keys existed can still unlock without losing their saved PIN`() {
        context.getSharedPreferences("app_lock_store", Application.MODE_PRIVATE).edit()
            .remove("lock_revision_$TARGET")
            .remove("pin_revision")
            .apply()
        AppLockGuard.onForegroundChange(TARGET)
        val window = window()!!
        assertEquals(0L, window.target.unlockToken!!.lockRevision)
        assertEquals(0L, window.target.unlockToken!!.pinRevision)
        assertTrue(AppLockStore.verifyPin(context, "1234"))
        unlockCallback(window)(window.target)
        AppLockGuard.onForegroundChange(TARGET)
        assertNull(window())
        assertTrue(AppLockStore.isLocked(context, TARGET))
    }

    private companion object {
        const val TARGET = "test.locked"
        const val OTHER = "test.locked.other"
    }
}
