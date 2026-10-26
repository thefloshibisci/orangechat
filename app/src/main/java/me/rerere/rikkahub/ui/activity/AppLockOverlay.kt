package me.rerere.rikkahub.ui.activity

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import me.rerere.rikkahub.data.service.AppLockUnlockToken

internal data class AppLockOverlayTarget(val packageName: String, val unlockToken: AppLockUnlockToken?)

/** A service-owned lock window: showing it does not depend on background activity starts. */
internal class AppLockOverlay(
    service: AccessibilityService,
    initialPackage: String,
    initialToken: AppLockUnlockToken?,
    private val onUnlock: (AppLockOverlayTarget) -> Unit,
    private val onCancel: () -> Unit,
    private val onReturnToApp: () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    private val windows = service.getSystemService(WindowManager::class.java)
    var target by mutableStateOf(AppLockOverlayTarget(initialPackage, initialToken))
        private set
    val targetPackage: String get() = target.packageName
    private var attached = false
    private val view = ComposeView(service)

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, code, event ->
            if (code != KeyEvent.KEYCODE_BACK) false else {
                if (event.action == KeyEvent.ACTION_UP) onCancel()
                true
            }
        }
        view.setContent {
            // The app theme needs an Activity window; this window belongs to the service.
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                // A queued click belongs to the screen that was displayed, even if a
                // later accessibility event retargets this same window before it runs.
                val displayedTarget = target
                key(displayedTarget) {
                    AppLockUnlockScreen(
                        targetPackage = displayedTarget.packageName,
                        onUnlocked = { onUnlock(displayedTarget) },
                        onCancel = onCancel,
                        onReturnToApp = onReturnToApp,
                    )
                }
            }
        }
    }

    fun show() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.OPAQUE,
        ).apply { setTitle("西瓜瓣应用锁") }
        windows.addView(view, params)
        attached = true
        registry.currentState = Lifecycle.State.RESUMED
        view.requestFocus()
    }

    fun retarget(packageName: String, token: AppLockUnlockToken?) {
        target = AppLockOverlayTarget(packageName, token)
    }

    fun dismiss() {
        try {
            if (attached) windows.removeViewImmediate(view)
        } finally {
            attached = false
            view.disposeComposition()
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
