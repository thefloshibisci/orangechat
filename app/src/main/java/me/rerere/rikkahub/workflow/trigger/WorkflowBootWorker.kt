package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.sync.awaitChatRecovery
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Fire boot workflows inside WorkManager's lifetime, including cold-process starts. */
class WorkflowBootWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val repository: WorkflowRepository by inject()
    private val engine: WorkflowEngine by inject()

    override suspend fun doWork(): Result {
        applicationContext.awaitChatRecovery()
        for (loaded in repository.listEnabled()) {
            val workflow = loaded.definition
            if (workflow.trigger is TriggerSpec.BootCompleted) {
                engine.triggerCallback.onFire(workflow.id, workflow.trigger)
            }
        }
        return Result.success()
    }
}
