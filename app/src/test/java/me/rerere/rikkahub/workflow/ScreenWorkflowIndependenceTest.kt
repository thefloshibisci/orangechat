package me.rerere.rikkahub.workflow

import android.app.Application
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.service.retireLegacyDeviceSync
import me.rerere.rikkahub.workflow.condition.ConditionEvaluator
import me.rerere.rikkahub.workflow.model.ConditionSpec
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowContext
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.trigger.ScreenTriggerFamily
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Workflows must keep receiving screen broadcasts after the old uploader is retired. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class ScreenWorkflowIndependenceTest {
    @Test fun screenTriggersSurviveDeviceSyncRetirement() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val family = ScreenTriggerFamily(context, scope)
        val fired = LinkedBlockingQueue<String>()
        try {
            family.sync(listOf(
                WorkflowDefinition("on", "亮屏", trigger = TriggerSpec.ScreenOn, actions = emptyList()),
                WorkflowDefinition("off", "息屏", trigger = TriggerSpec.ScreenOff, actions = emptyList()),
            )) { id, _ -> fired.offer(id); Unit }
            retireLegacyDeviceSync(context)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("on", fired.poll(5, TimeUnit.SECONDS))
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("off", fired.poll(5, TimeUnit.SECONDS))
            assertTrue(fired.isEmpty())
        } finally {
            family.shutdown()
            scope.cancel()
        }
    }

    @Test fun screenConditionsAndInversionDoNotDependOnSyncConfiguration() {
        for (on in listOf(true, false)) {
            val context = WorkflowContext(0, null, false, null, null, on, null, null)
            assertEquals(on, ConditionEvaluator.evaluate(ConditionSpec.ScreenIsOn(), context))
            assertEquals(!on, ConditionEvaluator.evaluate(ConditionSpec.ScreenIsOff(), context))
            assertEquals(!on, ConditionEvaluator.evaluate(ConditionSpec.ScreenIsOn(invert = true), context))
        }
    }
}
