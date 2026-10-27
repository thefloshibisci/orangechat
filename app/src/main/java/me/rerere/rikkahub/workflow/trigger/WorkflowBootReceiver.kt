/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Manifest-declared receiver for BOOT_COMPLETED / MY_PACKAGE_REPLACED / QUICKBOOT_POWERON.
 * A unique worker survives the receiver's short lifetime and does not depend on the
 * application's asynchronous trigger-registry initialization finishing first.
 */
class WorkflowBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> WorkManager.getInstance(context).enqueueUniqueWork(
                "workflow_boot_dispatch",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WorkflowBootWorker>().build(),
            )
        }
    }
}
