/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.ui.activity.AppLockOverlay
import me.rerere.rikkahub.ui.activity.AppLockOverlayTarget

object AppLockGuard {
    private const val TAG = "AppLockGuard"
    private const val REENTRY_GUARD_MS = 1_000L

    private lateinit var appContext: Context
    private val unlockedPackages = java.util.concurrent.ConcurrentHashMap<String, AppLockUnlockToken>()

    @Volatile private var listening = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: AppLockOverlay? = null
    private var lastFailedPackage: String? = null
    private var lastFailureAt: Long = -REENTRY_GUARD_MS

    fun init(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    fun refresh() {
        if (!::appContext.isInitialized) return
        val shouldListen = AppLockStore.getLockedPackages(appContext).isNotEmpty()
        listening = shouldListen
        mainHandler.post {
            overlay?.targetPackage?.let { pkg ->
                if (!AppLockStore.isLocked(appContext, pkg) || hasGraceUnlock(pkg)) dismissOverlay()
                else overlay?.retarget(pkg, AppLockStore.unlockToken(appContext, pkg))
            }
        }
    }

    fun grantGraceUnlock(packageName: String) {
        if (!::appContext.isInitialized) return
        grantGraceUnlock(packageName, AppLockStore.unlockToken(appContext, packageName))
    }

    internal fun grantGraceUnlock(packageName: String, expectedToken: AppLockUnlockToken?): Boolean {
        if (!::appContext.isInitialized || expectedToken == null || expectedToken.packageName != packageName) return false
        if (AppLockStore.unlockToken(appContext, packageName) != expectedToken) return false
        unlockedPackages[packageName] = expectedToken
        return true
    }

    private fun hasGraceUnlock(packageName: String): Boolean {
        val token = unlockedPackages[packageName] ?: return false
        if (token == AppLockStore.unlockToken(appContext, packageName)) return true
        unlockedPackages.remove(packageName, token)
        return false
    }

    fun reArmLock(packageName: String) {
        unlockedPackages.remove(packageName)
        if (::appContext.isInitialized) AppLockStore.invalidateUnlockToken(appContext, packageName)
    }

    fun goHome(): Boolean {
        if (!::appContext.isInitialized) return false
        val accepted = runCatching {
            RikkaAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true
        }.onFailure { Log.w(TAG, "GLOBAL_ACTION_HOME failed", it) }.getOrDefault(false)
        if (accepted) return true
        // Some ROMs reject the accessibility action. Launch the home destination explicitly.
        return launchExit(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    }

    fun returnToApp(): Boolean {
        if (!::appContext.isInitialized) return false
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: return false
        return launchExit(intent)
    }

    private fun launchExit(intent: Intent): Boolean = runCatching {
        appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.onFailure {
        Log.w(TAG, "Cannot open lock-screen exit", it)
        Toast.makeText(appContext, "暂时无法打开，请尝试另一个返回按钮", Toast.LENGTH_LONG).show()
    }.getOrDefault(false)

    // A full-screen overlay owns focus, so inspect the application window underneath it.
    // Neither requesting HOME nor seeing our own overlay event proves the target is gone.
    fun onWindowsChanged() {
        if (!listening) return
        val pkg = RikkaAccessibilityService.instance?.currentApplicationPackage(beneathOverlay = true) ?: return
        onForegroundChange(pkg)
    }

    fun onServiceDisconnected() {
        dismissOverlay()
    }

    private fun dismissOverlay() {
        val current = overlay
        overlay = null
        runCatching { current?.dismiss() }.onFailure { Log.w(TAG, "Lock window cleanup failed", it) }
    }

    // Called directly on the accessibility main thread, before workflow de-duplication.
    // A second attempt to open the same package must still be intercepted.
    fun onForegroundChange(eventPackage: String?) {
        if (eventPackage.isNullOrBlank()) return
        if (!::appContext.isInitialized) return
        if (!listening) return

        val locked = AppLockStore.getLockedPackages(appContext)
        val service = RikkaAccessibilityService.instance ?: return
        // Window events can arrive after a switch. Prefer the actual application
        // beneath our overlay; when unavailable keep the conservative event path.
        val foreground = service.currentApplicationPackage(beneathOverlay = true)
        val pkg = foreground ?: eventPackage
        if (pkg == appContext.packageName) {
            if (overlay != null && foreground == pkg) dismissOverlay()
            return
        }
        if (pkg !in locked || hasGraceUnlock(pkg)) {
            // Ignore notification/system windows: only a real application switch dismisses it.
            if (overlay != null && foreground == pkg) dismissOverlay()
            return
        }
        overlay?.let {
            it.retarget(pkg, AppLockStore.unlockToken(appContext, pkg))
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastFailedPackage == pkg && now - lastFailureAt < REENTRY_GUARD_MS) return

        Log.i(TAG, "Intercepting locked app: $pkg")
        var pendingWindow: AppLockOverlay? = null
        runCatching {
            val window = AppLockOverlay(
                service = service,
                initialPackage = pkg,
                initialToken = AppLockStore.unlockToken(appContext, pkg),
                onUnlock = { target ->
                    completeOverlayUnlock(pendingWindow, target)
                },
                onCancel = { goHome() },
                onReturnToApp = { returnToApp() },
            )
            pendingWindow = window
            window.show()
            overlay = window
            lastFailedPackage = null
        }.onFailure {
            runCatching { pendingWindow?.dismiss() }
            lastFailedPackage = pkg
            lastFailureAt = now
            Log.e(TAG, "Failed to show lock window", it)
            goHome()
            Toast.makeText(appContext, "此应用已被西瓜瓣锁定，拦截页暂时无法显示", Toast.LENGTH_LONG).show()
        }
    }

    private fun completeOverlayUnlock(window: AppLockOverlay?, target: AppLockOverlayTarget) {
        // Neither an old screen nor a removed window can unlock a newly selected
        // app or a newer lock request, or dismiss its current interception screen.
        if (window == null || overlay !== window || window.target != target) return
        val foreground = RikkaAccessibilityService.instance?.currentApplicationPackage(beneathOverlay = true)
        if (foreground != null && foreground != target.packageName) {
            onForegroundChange(foreground)
            return
        }
        if (!grantGraceUnlock(target.packageName, target.unlockToken)) {
            window.retarget(target.packageName, AppLockStore.unlockToken(appContext, target.packageName))
            return
        }
        // The target remains beneath the overlay and needs no relaunch.
        dismissOverlay()
    }
}
