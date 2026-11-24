package me.rerere.rikkahub.plugin.localtasks

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.plugin.localdata.LocalDataGrant
import me.rerere.rikkahub.plugin.localdata.LocalDataSession
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the real executor and ledger; only the provider is replaced. No network requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LocalTaskExecutorImageTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
    private val session = LocalDataSession(app, "test.image.budget", LocalDataGrant.all())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val client = OkHttpClient.Builder().addInterceptor { error("Network is forbidden in this fixture") }.build()
    private val provider = ImageProvider()
    private val models = listOf(Model(modelId = "text-model"), Model(modelId = "image-model", type = ModelType.IMAGE))
    private val assistant = Assistant(id = kotlin.uuid.Uuid.parse(ASSISTANT_A.id), chatModelId = models[0].id)
    private val appSettings = Settings(
        providers = listOf(ProviderSetting.OpenAI(models = models)),
        assistants = listOf(assistant), chatModelId = models[0].id, imageGenerationModelId = models[1].id,
    )
    private val settings = testSettings(dailyBudget = 4, maxCallsPerPost = 20).copy(
        allowImages = true, allowGeneratedImages = true,
    )
    private val ledger = LocalTaskLedger(MemoryLocalTaskStore()) { 1000L }
    private val budget = LedgerLocalTaskImageBudget(ledger)
    private val manager = ProviderManager(client, app).also { it.registerProvider("openai", provider) }
    private val recorder = UsageRecorder(database.usageRecordDao(), scope)

    @After fun cleanup() {
        scope.cancel()
        session.close()
        database.close()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }

    private fun request(id: String = "lt_image"): LocalTaskTurnRequest {
        ledger.createTask(LocalTaskRecord(taskId = id, state = LocalTaskState.RUNNING))
        // The real scheduler charges the text call before entering the executor.
        check(ledger.tryChargeModelCall("publish:0", settings))
        ledger.updateTask(id) { it.copy(modelCalls = 1) }
        return LocalTaskTurnRequest(id, LocalTaskMode.PUBLISH, null, 1, 1,
            ASSISTANT_A, settings, LocalTaskCancellationToken(), usageKey = "publish:0")
    }

    private fun executor(imageBudget: LocalTaskImageBudget = budget) = LocalTaskExecutor(
        settingsProvider = { appSettings }, session, manager, recorder, clock = { 1000L }, imageBudget,
    )

    private fun assertCharged() {
        assertEquals(1, provider.textCalls)
        assertEquals(1, provider.imageCalls)
        assertEquals(2, ledger.snapshot().modelCallsToday)
        assertEquals(2, ledger.task("lt_image")?.modelCalls)
    }

    @Test fun emptyResult() = runBlocking {
        val result = executor().runTurn(request())
        assertTrue(result is LocalTaskTurnOutcome.Failed)
        assertCharged()
    }

    @Test fun invalidImage() = runBlocking {
        provider.image = { ImageGenerationResult(listOf(ImageGenerationItem("data:image/png;base64,", "image/png"))) }
        assertTrue(executor().runTurn(request()) is LocalTaskTurnOutcome.Failed)
        assertCharged()
    }

    @Test fun networkFailure() = runBlocking {
        provider.image = { throw IOException("response lost after dispatch") }
        assertTrue(executor().runTurn(request()) is LocalTaskTurnOutcome.Failed)
        assertCharged()
    }

    @Test fun timeoutAfterDispatch() = runBlocking {
        provider.image = { withTimeout(1) { awaitCancellation() } }
        try { executor().runTurn(request()); fail("Expected timeout") } catch (_: CancellationException) { }
        assertCharged()
    }

    @Test fun cancelledAfterDispatch() = runBlocking {
        provider.image = { throw CancellationException("request was dispatched") }
        try { executor().runTurn(request()); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertCharged()
    }

    @Test fun cancelledBeforeDispatch() = runBlocking {
        val turn = request()
        val cancelOnReserve = object : LocalTaskImageBudget by budget {
            override fun tryCharge(usageKey: String, taskId: String, settings: LocalTaskSettings): Boolean =
                budget.tryCharge(usageKey, taskId, settings).also { if (it) turn.token.cancel() }
        }
        try { executor(cancelOnReserve).runTurn(turn); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, provider.imageCalls)
        assertEquals(1, ledger.snapshot().modelCallsToday)
        assertEquals(1, ledger.task("lt_image")?.modelCalls)
    }

    @Test fun failuresStillReachDailyLimit() = runBlocking {
        repeat(2) { assertTrue(executor().runTurn(request("lt_image_$it")) is LocalTaskTurnOutcome.Failed) }
        assertEquals(2, provider.textCalls)
        assertEquals(2, provider.imageCalls)
        assertEquals(4, ledger.snapshot().modelCallsToday)
        assertFalse(ledger.tryChargeModelCall("publish:0", settings))
    }

    private class ImageProvider : Provider<ProviderSetting> {
        var textCalls = 0
        var imageCalls = 0
        var image: suspend () -> ImageGenerationResult = { ImageGenerationResult(emptyList()) }
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): MessageChunk {
            textCalls++
            val content = """{"action":"draft","body":"rain","image":{"source":"generate_image","prompt":"rain"}}"""
            return MessageChunk("text-$textCalls", params.model.modelId,
                listOf(UIMessageChoice(0, null, UIMessage.assistant(content), "stop")))
        }
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> =
            flowOf(generateText(providerSetting, messages, params))
        override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): ImageGenerationResult {
            imageCalls++
            return image()
        }
    }
}
