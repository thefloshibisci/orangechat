/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Phase 12 — time / cron family.
 *
 * Reuses WorkManager (same backend as scheduled jobs) but with workflow-scoped unique-work
 * names so one workflow's schedule doesn't collide with another's, and so unsync removes
 * exactly the disabled workflow's worker without touching anything else.
 *
 * Supports fixed intervals through `@every Ns` and calendar-based schedules. Calendar
 * aliases, time_of_day and arbitrary 5-field cron run on a one-shot chain with the next
 * fire computed exactly via [me.rerere.rikkahub.service.CronExpressionParser] (shared with
 * scheduled jobs). The validator rejects anything neither path can handle.
 *
 * We use [WorkflowTimeCronWorker] (separate file) which receives the workflow id and
 * dispatches into the engine.
 */
internal class TimeCronTriggerFamily(
    private val context: Context,
) : WorkflowTriggerFamily {

    override val name = "time_cron"

    @Volatile private var lastSnapshot: List<WorkflowDefinition> = emptyList()
    @Volatile private var fireCallback: TriggerFireCallback? = null

    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.TimeCron

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        fireCallback = callback
        val previous = lastSnapshot.associateBy { it.id }
        val current = matching.associateBy { it.id }
        // Cancel removed
        for (id in previous.keys - current.keys) {
            cancelWork(id)
        }
        // Schedule added or changed
        for ((id, wf) in current) {
            val prev = previous[id]
            if (prev == null
                || prev.trigger != wf.trigger
                || !prev.enabled
            ) {
                // A cold process has no snapshot. Keep the persisted WorkManager job
                // rather than restarting its interval every time the app is opened.
                scheduleWork(wf, keepExisting = prev == null)
            }
        }
        lastSnapshot = matching
    }

    override suspend fun shutdown() {
        for (wf in lastSnapshot) cancelWork(wf.id)
        lastSnapshot = emptyList()
        fireCallback = null
    }

    fun cancelWork(workflowId: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName(workflowId)) }
            .onFailure { Log.w(TAG, "time_cron: cancel work failed for $workflowId", it) }
    }

    private suspend fun scheduleWork(wf: WorkflowDefinition, appendAfterCurrent: Boolean = false, keepExisting: Boolean = false) {
        val spec = wf.trigger as? TriggerSpec.TimeCron ?: return
        val zone = spec.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val nextFireMs = computeNextFireMs(spec, zone, System.currentTimeMillis()) ?: run {
            cancelWork(wf.id)
            Log.w(TAG, "time_cron: invalid stored schedule for ${wf.id}; edit to repair")
            return
        }
        val manager = WorkManager.getInstance(context)
        val fingerprint = scheduleFingerprint(spec)
        val preserve = keepExisting && manager.getWorkInfosForUniqueWorkFlow(workName(wf.id)).first()
            .filter { !it.state.isFinished }.all { fingerprint in it.tags }

        // WorkManager's smallest repeat period is 15 minutes. Legacy shorter intervals
        // and calendar schedules use one-shot rescheduling
        // from the worker (worker re-enqueues itself).
        val periodMs = derivePeriodMs(spec)
        if (periodMs != null && periodMs >= 15 * 60 * 1000L) {
            val delay = (nextFireMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val req = PeriodicWorkRequestBuilder<WorkflowTimeCronWorker>(periodMs, TimeUnit.MILLISECONDS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_WORKFLOW_ID to wf.id, KEY_SCHEDULE to fingerprint))
                .addTag(fingerprint)
                .setConstraints(Constraints.NONE)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    workName(wf.id), if (preserve) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.REPLACE, req,
                )
            }.onFailure { Log.w(TAG, "time_cron: periodic enqueue failed for ${wf.id}", it) }
            return
        }

        // One-shot path: schedule the next fire; the worker re-enqueues itself on completion.
        val delay = (nextFireMs - System.currentTimeMillis()).coerceAtLeast(60_000L)
        val req = OneTimeWorkRequestBuilder<WorkflowTimeCronWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_WORKFLOW_ID to wf.id, KEY_SCHEDULE to fingerprint))
            .addTag(fingerprint)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(wf.id), when {
                    appendAfterCurrent -> ExistingWorkPolicy.APPEND_OR_REPLACE
                    preserve -> ExistingWorkPolicy.KEEP
                    else -> ExistingWorkPolicy.REPLACE
                }, req,
            )
        }.onFailure { Log.w(TAG, "time_cron: one-shot enqueue failed for ${wf.id}", it) }
    }

    /** Internal — fires the workflow then re-enqueues if needed. Called from worker. */
    suspend fun onWorkerFired(workflowId: String, callback: TriggerFireCallback? = fireCallback, scheduledFingerprint: String? = null) {
        val cb = callback ?: return
        // Post-boot or post-process-death race: WorkManager wakes the worker before
        // [TriggerRegistry.start] has emitted from the repo's flow, leaving `lastSnapshot`
        // empty. Fall back to a direct repository fetch so the fire isn't silently dropped.
        // The engine still re-checks enabled / cooldown / conditions, so this is safe.
        val loaded = TimeCronWorkerHelper.repositoryLookup(workflowId) ?: return
        if (!loaded.entity.enabled) return
        val wf = loaded.definition
        // Keep the day gate even when Android delays a calendar worker into another day.
        val spec = wf.trigger as? TriggerSpec.TimeCron
        if (spec == null) return
        val zone = spec.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        if (computeNextFireMs(spec, zone, System.currentTimeMillis()) == null) {
            cancelWork(workflowId)
            return
        }
        if (scheduledFingerprint != scheduleFingerprint(spec)) {
            // Stale and pre-upgrade jobs may not execute a newly edited schedule now.
            scheduleWork(wf)
            return
        }
        var dayMatches = true
        if (!spec.timeOfDay.isNullOrBlank() && spec.daysOfWeek.isNotEmpty()) {
            val zone = spec.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: ZoneId.systemDefault()
            val today = ZonedDateTime.now(zone).dayOfWeek
            if (today !in spec.daysOfWeek.map { isoDow(it) }) {
                Log.d(TAG, "time_cron: $workflowId skipped, $today not in days_of_week")
                dayMatches = false
            }
        }
        if (dayMatches) {
            // Keep execution in the worker's lifetime. A detached launch lets Android
            // stop the process after doWork() succeeds while actions are still running.
            try {
                cb.onFire(wf.id, wf.trigger)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "time_cron: fire callback failed for $workflowId", error)
            }
        }
        // For one-shot path (period < 15min or null), re-enqueue with the next fire.
        val periodMs = (wf.trigger as? TriggerSpec.TimeCron)?.let { derivePeriodMs(it) }
        if (periodMs == null || periodMs < 15 * 60 * 1000L) {
            // Re-read after execution: the workflow may have been disabled, deleted or
            // edited while its actions were running. Never recreate its stale schedule.
            val latest = TimeCronWorkerHelper.repositoryLookup(workflowId)
            if (latest?.entity?.enabled == true && latest.definition.trigger == spec) {
                scheduleWork(latest.definition, appendAfterCurrent = true)
            }
        }
    }

    companion object {
        private const val TAG = "WorkflowTrigger"
        const val KEY_WORKFLOW_ID = "workflow_id"
        const val KEY_SCHEDULE = "schedule_fingerprint"
        fun workName(workflowId: String) = "wf_timecron_$workflowId"
        fun scheduleFingerprint(spec: TriggerSpec.TimeCron) = "wf_schedule_v2:$spec"

        /**
         * Only @every is a fixed interval. Calendar schedules return null and are
         * recomputed after each run to retain their local wall-clock time across DST.
         */
        fun derivePeriodMs(spec: TriggerSpec.TimeCron): Long? {
            // Calendar schedules must be recomputed after each run; a fixed 24-hour
            // repeat drifts by an hour across daylight-saving changes.
            if (!spec.timeOfDay.isNullOrBlank()) return null
            val cron = spec.cron?.trim() ?: return null
            // @every Ns
            val every = Regex("^@every\\s+(\\d+)([smhd])$").find(cron)
            if (every != null) {
                val n = every.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
                val unit = every.groupValues[2]
                val factor = when (unit) { "s" -> 1000L; "m" -> 60_000L; "h" -> 3_600_000L; "d" -> 86_400_000L; else -> return null }
                return runCatching { Math.multiplyExact(n, factor) }.getOrNull()
                    ?.takeIf { it in 60_000L..365L * 86_400_000L }
            }
            return null // Calendar aliases and 5-field cron use the exact one-shot path.
        }

        /** Invalid stored schedules fail closed; they never turn into unrelated hourly actions. */
        fun computeNextFireMs(spec: TriggerSpec.TimeCron, zone: ZoneId, nowMs: Long): Long? {
            if (spec.timezone != null && runCatching { ZoneId.of(spec.timezone) }.isFailure) return null
            if (spec.daysOfWeek.any { it !in 1..7 }) return null
            if (!spec.timeOfDay.isNullOrBlank() && !spec.cron.isNullOrBlank()) return null
            val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
            // time_of_day + optional days_of_week
            if (!spec.timeOfDay.isNullOrBlank()) {
                val parts = spec.timeOfDay.split(":")
                if (parts.size != 2) return null
                val h = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
                val m = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
                var candidate = now.toLocalDate().atTime(LocalTime.of(h, m)).atZone(zone)
                if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
                if (spec.daysOfWeek.isNotEmpty()) {
                    val allowed = spec.daysOfWeek.map { isoDow(it) }.toSet()
                    var hops = 0
                    while (candidate.dayOfWeek !in allowed && hops < 8) {
                        candidate = candidate.plusDays(1); hops++
                    }
                }
                return candidate.toInstant().toEpochMilli()
            }
            // @every Ns
            derivePeriodMs(spec)?.let { return Math.addExact(nowMs, it) }
            if (spec.cron?.trim()?.startsWith("@every") == true) return null
            // 5-field cron: compute the exact next execution via the shared parser
            // (same dialect as scheduled jobs). Without this, "0 9 * * 1" style
            // expressions silently degraded to hourly fires.
            spec.cron?.trim()?.takeIf { it.isNotBlank() }?.let { cron ->
                me.rerere.rikkahub.service.CronExpressionParser.parse(cron).getOrNull()?.let { parsed ->
                    me.rerere.rikkahub.service.CronExpressionParser.nextExecution(parsed, now)
                        ?.let { return it.toInstant().toEpochMilli() }
                }
            }
            return null
        }

        private fun isoDow(iso: Int): DayOfWeek = when (iso) {
            1 -> DayOfWeek.MONDAY; 2 -> DayOfWeek.TUESDAY; 3 -> DayOfWeek.WEDNESDAY
            4 -> DayOfWeek.THURSDAY; 5 -> DayOfWeek.FRIDAY; 6 -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
    }
}
