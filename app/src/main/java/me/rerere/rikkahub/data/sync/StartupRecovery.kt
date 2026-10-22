package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.RikkaHubApp

internal sealed interface StartupRecoveryState {
    data object ReadyToRestore : StartupRecoveryState
    data object AwaitingConfirmation : StartupRecoveryState
    data class Restoring(val progress: ChatRestoreProgress) : StartupRecoveryState
    data object Cancelling : StartupRecoveryState
    data class Failed(val message: String) : StartupRecoveryState
    data object Ready : StartupRecoveryState
}

/** The main looper remains available while recovery owns the chat database. */
internal class StartupRecovery(
    private val scope: CoroutineScope,
    private val restore: suspend ((ChatRestoreProgress) -> Unit) -> Unit,
    private val clearPending: suspend () -> Unit,
    private val initializeServices: () -> Unit,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow<StartupRecoveryState>(
        StartupRecoveryState.ReadyToRestore)
    val state = mutableState.asStateFlow()
    val isReady get() = state.value == StartupRecoveryState.Ready
    private var recoveryJob: Job? = null
    private var stopping = false
    private var initialized = false
    @Volatile var blocksChatAccess = true
        private set

    fun start(hasPending: Boolean, automatically: Boolean = false) {
        check(recoveryJob == null)
        if (!hasPending) {
            finish()
        } else mutableState.value = if (automatically) StartupRecoveryState.ReadyToRestore
            else StartupRecoveryState.AwaitingConfirmation
    }

    /** Only the visible recovery page starts a fresh task; Application.onCreate never does. */
    fun onRecoveryScreenShown() {
        if (state.value == StartupRecoveryState.ReadyToRestore && !stopping) retry()
    }

    fun retry() {
        if (isReady || recoveryJob?.isActive == true || stopping) return
        mutableState.value = StartupRecoveryState.Restoring(ChatRestoreProgress("正在读取聊天备份…"))
        recoveryJob = scope.launch {
            try {
                withContext(workDispatcher) { restore { mutableState.value = StartupRecoveryState.Restoring(it) } }
                finish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = StartupRecoveryState.Failed(error.message ?: "恢复未完成")
            }
        }
    }

    /** Join first: Room must finish rollback before a session or worker can resume. */
    fun cancelAndContinue() {
        if (isReady || stopping) return
        stopping = true
        mutableState.value = StartupRecoveryState.Cancelling
        scope.launch {
            try {
                recoveryJob?.cancelAndJoin()
                withContext(workDispatcher) { clearPending() }
                finish()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = StartupRecoveryState.Failed(error.message ?: "无法取消待恢复任务")
            } finally { stopping = false }
        }
    }

    suspend fun awaitReady() { state.first { it == StartupRecoveryState.Ready } }

    private fun finish() {
        if (!initialized) {
            // Restoration/rollback has ended; pre-warm ChatService on Main before releasing UI/workers.
            blocksChatAccess = false
            mutableState.value = StartupRecoveryState.Restoring(
                ChatRestoreProgress("正在准备进入应用…", ChatRestoreStep.FINISHING))
            initializeServices()
            initialized = true
        }
        mutableState.value = StartupRecoveryState.Ready
    }
}

internal fun Context.isChatRecoveryBlocking(): Boolean =
    (applicationContext as? RikkaHubApp)?.startupRecovery?.isReady == false

internal suspend fun Context.awaitChatRecovery() {
    (applicationContext as? RikkaHubApp)?.startupRecovery?.awaitReady()
}
