package me.rerere.rikkahub.data.ai.transformers

import android.app.Application
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TemplateTimestampTest {
    @Test fun historicalTemplatesUseMessageDateAndKeepTheRequestPrefix() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val scope = AppScope().also { it.cancel() }
        val assistant = Assistant(messageTemplate = "{{date}} {{time}} {{message}}")
        val settings = Settings(assistants = listOf(assistant))
        val store = SettingsStore(context, scope).also { it.settingsFlow.value = settings }
        val engine = PebbleEngine.Builder().loader(AssistantTemplateLoader(store)).build()
        val transformer = TemplateTransformer(engine, store)
        val ctx = TransformerContext(context, Model(), assistant, settings)
        val old = UIMessage.user("older message").copy(createdAt = LocalDateTime(2020, 1, 2, 3, 4, 5))
        val first = transformer.transform(ctx, listOf(old))
        val second = transformer.transform(ctx, listOf(old, UIMessage.user("new turn")))
        assertEquals(first.single(), second.first())
        assertTrue(first.single().toText().contains("2020"))
        assertTrue(first.single().toText().contains("3:04"))
    }
}
