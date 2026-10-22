/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.web

import android.content.Context
import android.util.Log
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import java.net.ServerSocket

private const val TAG = "WebServerManager"
private const val HOST_ALL_INTERFACES = "0.0.0.0"
private const val HOST_LOOPBACK = "127.0.0.1"

data class WebServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = 8080,
    val serviceName: String = DEFAULT_SERVICE_NAME,
    val localhostOnly: Boolean = false,
    val hostname: String? = null,
    val address: String? = null,
    val error: String? = null
)

class WebServerManager(
    private val context: Context,
    private val appScope: AppScope,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager
) {
    private val lifecycle = WebServerLifecycle<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()
    private val nsdRegistrar = NsdServiceRegistrar(context)

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    fun start(
        port: Int = 8080,
        serviceName: String = DEFAULT_SERVICE_NAME,
        localhostOnly: Boolean = false
    ) = launchCommand {
        lifecycle.start { startServer(port, serviceName, localhostOnly) }
    }

    fun stop() = launchCommand {
        lifecycle.stop(::stopServer)
    }

    fun restart(
        port: Int = _state.value.port,
        serviceName: String = _state.value.serviceName,
        localhostOnly: Boolean = _state.value.localhostOnly
    ) = launchCommand {
        lifecycle.restart({ startServer(port, serviceName, localhostOnly) }, ::stopServer)
    }

    // Enter the mutex before dispatching blocking work, so commands keep invocation order.
    private fun launchCommand(block: suspend () -> Unit) = appScope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Web server command failed", e)
        }
    }

    private suspend fun startServer(
        port: Int,
        serviceName: String,
        localhostOnly: Boolean,
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        _state.value = WebServerState(
            isLoading = true, port = port, serviceName = serviceName, localhostOnly = localhostOnly,
        )
        return withContext(Dispatchers.IO) {
            try {
                check(isPortAvailable(port)) { "Port $port is already in use" }
                val host = if (localhostOnly) HOST_LOOPBACK else HOST_ALL_INTERFACES
                val started = startWebServer(port = port, host = host) {
                    configureWebApi(context, chatService, conversationRepo, settingsStore, filesManager)
                }.start(wait = false)
                _state.value = _state.value.copy(isRunning = true, isLoading = false)
                if (!localhostOnly) {
                    runCatching {
                        nsdRegistrar.register(port = port, serviceName = serviceName) { info ->
                            _state.value = _state.value.copy(
                                serviceName = info.serviceName,
                                hostname = info.hostname,
                                address = info.address.hostAddress,
                            )
                        }
                    }.onFailure { Log.w(TAG, "NSD register failed", it) }
                }
                started
            } catch (e: Exception) {
                _state.value = _state.value.copy(isRunning = false, isLoading = false, error = e.message)
                throw e
            }
        }
    }

    private suspend fun stopServer(server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>) {
        _state.value = _state.value.copy(isLoading = true, error = null)
        withContext(Dispatchers.IO) {
            try {
                server.stop(1000, 2000)
                runCatching { nsdRegistrar.unregister() }
                    .onFailure { Log.w(TAG, "NSD unregister failed", it) }
                _state.value = _state.value.copy(
                    isRunning = false, isLoading = false, hostname = null, address = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
                throw e
            }
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }
}
