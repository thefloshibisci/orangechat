package me.rerere.rikkahub.data.service

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.AssistantProactiveConfig
import me.rerere.rikkahub.data.datastore.ProactiveGlobalPolicy
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ProactiveTaskStoreTest {
    private val now = Instant.parse("2026-06-01T12:00:00Z").toEpochMilli()
    private val configs = listOf(AssistantProactiveConfig("a", "ca", cooldownMinutes = 0),
        AssistantProactiveConfig("b", "cb", cooldownMinutes = 0))
    private fun host(limit: Int = 3) = ProactiveHostSnapshot(
        ProactiveMessageSetting(enabled = true, activeHoursEnabled = false, assistantConfigs = configs,
            globalPolicy = ProactiveGlobalPolicy(dailyModelTurnLimit = limit, budgetZoneId = "UTC")),
        setOf("a", "b"), mapOf("ca" to "a", "cb" to "b"), mapOf("ca" to "anchor", "cb" to "anchor"),
    )
    private fun task(id: String, assistant: String = "a") = ProactiveTask(
        taskId = id, assistantId = assistant, conversationId = "c$assistant", trigger = "automatic",
        anchorUserMessageId = "anchor", createdAtMillis = now,
    )
    private class MemoryStorage : ProactiveSchedulerStorage {
        var value: String? = null
        var fail = false
        override fun read() = value
        override fun write(value: String) { check(!fail); this.value = value }
    }

    @Test fun legacySlotMigratesOnlyToSingleParticipantWithoutExtendingExpiredOrOverwritingNewSlot() {
        val store = ProactiveTaskStore(MemoryStorage())
        val single = host().setting.copy(assistantConfigs = listOf(configs[0]))
        store.migrateLegacyNextSlot(host().setting, now + 600_000, now)
        assertTrue(store.snapshot().nextSlots.isEmpty())
        store.migrateLegacyNextSlot(single, now + 600_000, now)
        assertFalse(store.slotAllows(task("a"), now))
        store.migrateLegacyNextSlot(single, now + 900_000, now)
        assertEquals(now + 600_000, store.snapshot().nextSlots.getValue("a").dueAtMillis)
        assertTrue(store.slotAllows(task("a").copy(trigger = "manual"), now))
        store.resetConversationSlot("ca")
        store.migrateLegacyNextSlot(single, now - 60_000, now)
        assertTrue(store.slotAllows(task("a"), now))
        assertEquals(now, store.snapshot().nextSlots.getValue("a").dueAtMillis)
    }

    @Test fun independentSlotsSurviveRestartAndNewUserMessageReleasesOnlyItsConversation() {
        val disk = MemoryStorage()
        val store = ProactiveTaskStore(disk)
        val a = task("a")
        val b = task("b", "b")
        store.setNextSlot(a, now + 120_000)
        store.setNextSlot(b, now + 600_000)
        val reopened = ProactiveTaskStore(disk)
        assertEquals(now + 120_000, reopened.ensureNextSlots(host().setting, now))
        assertFalse(reopened.slotAllows(a, now))
        assertFalse(reopened.slotAllows(b, now))
        assertTrue(reopened.slotAllows(a, now + 120_000))
        assertFalse(reopened.slotAllows(b, now + 120_000))
        assertTrue(reopened.slotAllows(a.copy(anchorUserMessageId = "new"), now))
        reopened.resetConversationSlot("ca")
        assertTrue(reopened.slotAllows(a, now))
        assertFalse(reopened.slotAllows(b, now))
        assertEquals(now + 600_000, ProactiveTaskStore(disk).snapshot().nextSlots.getValue("b").dueAtMillis)
    }

    @Test fun appointmentForOneAssistantDoesNotDelayAnotherAssistantSlot() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.setNextSlot(task("a"), now + 120_000)
        store.setNextSlot(task("b", "b"), now + 600_000)
        assertEquals(now + 600_000, store.ensureNextSlots(host().setting, now, awaitingAppointment = setOf("a")))
        assertEquals(now + 120_000, store.ensureNextSlots(host().setting, now, awaitingAppointment = setOf("b")))
    }

    @Test fun appointmentCanRunOutsideActiveHoursInItsAgreedWindow() {
        val scheduled = configs[0].copy(pinConversation = false, customEnabled = true,
            activeHoursEnabled = true, activeStartHour = 8, activeEndHour = 9)
        val setting = host().setting.copy(assistantConfigs = listOf(scheduled, configs[1]),
            allowAiScheduledWakeup = true, activeHoursEnabled = true, activeStartHour = 8, activeEndHour = 9)
        val host = host().copy(setting = setting)
        val task = task("appointment").copy(trigger = "appointment", appointmentToken = "token",
            conversationId = "appointment-window")
        assertTrue(proactiveTaskAllowed(task, host.copy(conversationOwners =
            host.conversationOwners + ("appointment-window" to "a"), userAnchors =
            host.userAnchors + ("appointment-window" to "anchor")), now))
        assertFalse(proactiveTaskAllowed(task.copy(appointmentToken = null), host, now))
        assertFalse(proactiveTaskAllowed(task, host.copy(setting = setting.copy(allowAiScheduledWakeup = false)), now))
    }

    @Test fun unpinnedParticipantAcceptsAppointmentWindowButPinnedParticipantDoesNot() {
        assertTrue(configs[0].acceptsWindow("another-window", appointment = true))
        assertFalse(configs[0].copy(pinConversation = true).acceptsWindow("another-window", appointment = true))
        assertFalse(configs[0].acceptsWindow("another-window", appointment = false))
    }

    @Test fun completedChatStartsFreshIdleIntervalWithoutResettingAnotherAssistantWait() {
        val store = ProactiveTaskStore(MemoryStorage())
        val setting = host().setting.copy(minIntervalMinutes = 30, maxIntervalMinutes = 30)
        store.setNextSlot(task("a"), now + 30 * 60_000)
        store.setNextSlot(task("b", "b"), now + 240 * 60_000)
        val completedAt = now + 20 * 60_000
        store.resetConversationSlot("ca")
        assertEquals(completedAt + 30 * 60_000, store.ensureNextSlots(setting, completedAt))
        assertEquals(completedAt + 30 * 60_000, store.snapshot().nextSlots.getValue("a").dueAtMillis)
        assertEquals(now + 240 * 60_000, store.snapshot().nextSlots.getValue("b").dueAtMillis)
    }

    @Test fun legacySerializationAndExplicitEmptyRemainDistinct() {
        val legacy = Json.decodeFromString<ProactiveMessageSetting>("{\"enabled\":true}")
        val empty = Json.decodeFromString<ProactiveMessageSetting>("{\"assistantConfigs\":[]}")
        assertNull(legacy.assistantConfigs)
        assertEquals(emptyList<AssistantProactiveConfig>(), empty.assistantConfigs)
        val owner = mapOf("ca" to "a")
        assertEquals(listOf(configs[0]), migrateProactiveParticipants(null, "a", configs, owner))
        assertEquals(listOf(configs[0]), migrateProactiveParticipants(null, "", listOf(configs[0]), owner))
        assertTrue(migrateProactiveParticipants(emptyList(), "a", configs, owner).isEmpty())
        assertTrue(migrateProactiveParticipants(null, "gone", configs, owner).isEmpty())
        assertTrue(migrateProactiveParticipants(null, "a", configs, mapOf("ca" to "b")).isEmpty())
        assertTrue(migrateProactiveParticipants(null, "", configs, owner).isEmpty())
        // After persist, changed foreground candidates cannot remigrate an existing enrollment.
        assertEquals(listOf(configs[0]), migrateProactiveParticipants(listOf(configs[0]), "b", configs, owner))
    }

    // ------------------------------------------------------------------
    // 跟随最新聊天窗口（pinConversation 默认 false）
    // ------------------------------------------------------------------

    @Test fun savedConfigWithoutPinFieldDecodesAsFollowLatest() {
        val decoded = Json.decodeFromString<AssistantProactiveConfig>(
            "{\"assistantId\":\"a\",\"conversationId\":\"ca\",\"enabled\":true}",
        )
        assertFalse("旧数据没有 pinConversation 字段：默认跟随最新", decoded.pinConversation)
        assertTrue(Json.decodeFromString<AssistantProactiveConfig>(
            "{\"assistantId\":\"a\",\"conversationId\":\"ca\",\"pinConversation\":true}",
        ).pinConversation)
    }

    @Test fun followLatestMovesOnlyUnpinnedToANonBlankDifferentLatest() {
        val pinned = configs[0].copy(pinConversation = true)
        val moved = followLatestConversations(
            listOf(configs[0], configs[1]),
            mapOf("a" to "new-window", "b" to ""),
        )
        assertEquals("new-window", moved[0].conversationId)
        assertEquals("cb", moved[1].conversationId) // 最新为空：不动
        assertEquals(listOf(pinned), followLatestConversations(listOf(pinned), mapOf("a" to "new-window")))
        // 已经指向最新 = no-op
        assertEquals(listOf(configs[0]), followLatestConversations(
            listOf(configs[0]), mapOf("a" to "ca")))
        // map 里没有这个助手 = no-op
        assertEquals(listOf(configs[1]), followLatestConversations(
            listOf(configs[1]), emptyMap()))
    }

    @Test fun followLatestCarriesTheDueSlotAcrossConversations() {
        val store = ProactiveTaskStore(MemoryStorage())
        // "a" 的槽排在 10 分钟后、绑定在旧窗口 ca；随后配置被同步到最新窗口 new-a。
        store.setNextSlot(task("a"), now + 600_000)
        val moved = host().setting.copy(assistantConfigs = listOf(
            configs[0].copy(conversationId = "new-a"),
            configs[1],
        ))
        store.ensureNextSlots(moved, now)
        val slotA = store.snapshot().nextSlots.getValue("a")
        assertEquals("跟随最新换窗口时，排好的到期时间要带过去", now + 600_000, slotA.dueAtMillis)
        assertEquals("new-a", slotA.conversationId)
        assertEquals("", slotA.anchorUserMessageId)
        // 未动窗口的 "b" 不受影响。
        assertEquals("cb", store.snapshot().nextSlots.getValue("b").conversationId)
    }

    @Test fun pinnedConfigGetsAFreshRandomSlotWhenItsWindowChanges() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.setNextSlot(task("a"), now + 600_000)
        val moved = host().setting.copy(assistantConfigs = listOf(
            configs[0].copy(conversationId = "new-a", pinConversation = true),
        ))
        val due = store.ensureNextSlots(moved, now)
        val slot = store.snapshot().nextSlots.getValue("a")
        assertEquals("new-a", slot.conversationId)
        // 固定窗口换目标 = 重新抽签（不带旧窗口的到期时间）。
        assertTrue(slot.dueAtMillis >= now)
        assertTrue(due != null && due >= now + 60_000L)
    }

    @Test fun fairClaimsReserveEachConversationAndTerminalCas() {
        val store = ProactiveTaskStore(MemoryStorage())
        listOf(task("a1"), task("a2"), task("b1", "b")).forEach { assertTrue(store.enqueue(it)) }
        assertFalse(store.enqueue(task("a1")))
        assertEquals("a1", store.claimNext(host(), now)?.taskId)
        assertEquals("b1", store.claimNext(host(), now)?.taskId)
        assertNull(store.claimNext(host(), now)) // a2 shares a1's busy conversation.
        assertTrue(store.finish("a1", ProactiveTaskStatus.Failed, "no_provider"))
        assertFalse(store.finish("a1", ProactiveTaskStatus.Sent, "late"))
        assertEquals("a2", store.claimNext(host(), now)?.taskId)
    }

    @Test fun everyTurnIsChargedAndOnlyPreDispatchCanRollback() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        val first = requireNotNull(store.reserveTurn("a", host(1), now))
        assertNull(store.reserveTurn("a", host(1), now))
        assertTrue(store.settleTurn(first)) // preparation failure: no provider call
        val second = requireNotNull(store.reserveTurn("a", host(1), now))
        assertFalse(store.settleTurn(first)) // stale token cannot refund second turn
        assertTrue(store.markDispatched(second, host(1), now))
        assertTrue(store.settleTurn(second)) // includes HTTP failure/timeouts, still charged
        assertNull(store.reserveTurn("a", host(1), now))
        assertEquals(1, store.snapshot().days.getValue("2026-06-01").modelTurns)
        val continuation = requireNotNull(store.reserveTurn("a", host(2), now))
        assertTrue(store.markDispatched(continuation, host(2), now))
        assertTrue(store.settleTurn(continuation))
        assertNull(store.reserveTurn("a", host(2), now)) // finalizer also needs allowance
    }

    @Test fun cancellationAndRestartRejectLateResultsWithoutReplay() {
        val disk = MemoryStorage()
        val store = ProactiveTaskStore(disk)
        store.enqueue(task("a")); store.claimNext(host(), now)
        val token = requireNotNull(store.reserveTurn("a", host(), now))
        val restarted = ProactiveTaskStore(disk)
        assertEquals(setOf("a"), restarted.interruptRunning())
        assertFalse(restarted.settleTurn(token)) // unknown reservation conservatively charged
        assertFalse(restarted.canCommit("a", host(), now))
        assertNull(restarted.claimNext(host(), now))
        assertEquals(1, restarted.snapshot().days.getValue("2026-06-01").modelTurns)
        restarted.enqueue(task("b", "b")); restarted.claimNext(host(), now)
        assertEquals(setOf("b"), restarted.cancel("b"))
        assertFalse(restarted.canCommit("b", host(), now))
        assertFalse(restarted.finish("b", ProactiveTaskStatus.Sent, "late"))
    }

    @Test fun freshOwnerAssistantConfigAndPauseChecksFailClosed() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        val h = host()
        assertFalse(store.canCommit("a", h.copy(conversationOwners = mapOf("ca" to "b")), now))
        assertFalse(store.canCommit("a", h.copy(assistantIds = setOf("b")), now))
        assertFalse(store.canCommit("a", h.copy(userAnchors = emptyMap()), now))
        assertFalse(store.canCommit("a", h.copy(setting = h.setting.copy(
            globalPolicy = h.setting.globalPolicy.copy(paused = true))), now))
        assertNull(store.reserveTurn("a", h.copy(setting = h.setting.copy(assistantConfigs = emptyList())), now))
        assertFalse(store.canCommit("a", h.copy(setting = h.setting.copy(
            assistantConfigs = listOf(configs[0].copy(promptOverride = "changed")))), now))
    }

    @Test fun cooldownDailyLimitBusyWindowAndFutureTaskAreSkippedFairly() {
        val store = ProactiveTaskStore(MemoryStorage())
        val h = host().let { it.copy(setting = it.setting.copy(
            assistantConfigs = listOf(
                configs[0].copy(customEnabled = true, cooldownMinutes = 60, dailyLimit = 1),
                configs[1],
            ))) }
        store.enqueue(task("a1")); store.enqueue(task("a2")); store.enqueue(task("b1", "b"))
        assertEquals("b1", store.claimNext(h.copy(busyConversations = setOf("ca")), now)?.taskId)
        store.finish("b1", ProactiveTaskStatus.Failed, "pass")
        assertEquals("a1", store.claimNext(h, now)?.taskId)
        store.finish("a1", ProactiveTaskStatus.Failed, "pass")
        assertNull(store.claimNext(h, now + 61 * 60_000)) // daily task count, not network count
        assertEquals("a2", store.claimNext(h, now + 24 * 60 * 60_000)?.taskId)
    }

    @Test fun blankLegacyBudgetZoneStillAllowsClaims() {
        val store = ProactiveTaskStore(MemoryStorage())
        val h = host().let { it.copy(setting = it.setting.copy(
            globalPolicy = it.setting.globalPolicy.copy(budgetZoneId = ""),
        )) }
        store.enqueue(task("a"))
        assertEquals("a", store.claimNext(h, now)?.taskId)
    }

    @Test fun pinnedZoneDstAndClockRollbackCannotResetBudget() {
        assertEquals("2025-03-08", budgetDay(Instant.parse("2025-03-09T07:30:00Z").toEpochMilli(), "America/Los_Angeles"))
        assertNull(budgetDay(now, "invalid-zone"))
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        assertNull(store.reserveTurn("a", host(), now - 86_400_000))
        val changedZone = host().let { it.copy(setting = it.setting.copy(
            globalPolicy = it.setting.globalPolicy.copy(budgetZoneId = "Asia/Tokyo"))) }
        assertNull(store.reserveTurn("a", changedZone, now))
    }

    @Test fun durablePayloadSurvivesCancelAndRestartUntilAnchorInvalidation() {
        val disk = MemoryStorage()
        val store = ProactiveTaskStore(disk)
        store.enqueue(task("a")); store.claimNext(host(), now)
        val payload = "serialized-final-messages"
        assertTrue(store.beginCommit("a", host(), now, listOf("id-1"), payload))
        val restarted = ProactiveTaskStore(disk)
        assertEquals(setOf("a"), restarted.interruptRunning())
        assertEquals(payload, restarted.pendingCommits().single().commitPayload)
        assertEquals(listOf("id-1"), restarted.pendingCommits().single().commitMessageIds)
        restarted.cancel()
        assertNotNull(restarted.startCommit("a"))
        assertEquals(payload, restarted.pendingCommits().single().commitPayload)
        assertTrue(restarted.abandonCommit("a", ProactiveTaskStatus.Interrupted, "anchor_changed"))
        assertTrue(restarted.pendingCommits().isEmpty())
        assertEquals(ProactiveTaskStatus.Interrupted, restarted.snapshot().tasks.single().status)
    }

    @Test fun commitMarkSentOccursOnlyAfterRoomCallbackSucceeds() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        assertTrue(store.beginCommit("a", host(), now, listOf("stable-id"), "payload"))
        assertFalse(store.commitPayload("a") { false })
        assertEquals(ProactiveTaskStatus.CommitPending, store.snapshot().tasks.single().status)
        assertEquals("payload", store.snapshot().tasks.single().commitPayload)
        assertTrue(store.commitPayload("a") { it.commitMessageIds == listOf("stable-id") })
        assertEquals(ProactiveTaskStatus.Sent, store.snapshot().tasks.single().status)
        assertNull(store.snapshot().tasks.single().commitPayload)
    }

    @Test fun sentPreferenceFailureAfterRoomCommitRetainsExactRecoveryPayload() {
        val disk = MemoryStorage()
        val store = ProactiveTaskStore(disk)
        store.enqueue(task("a")); store.claimNext(host(), now)
        assertTrue(store.beginCommit("a", host(), now, listOf("stable-id"), "same-final-payload"))
        assertNotNull(store.startCommit("a"))
        // Simulate Room commit already succeeded, then the Sent prefs write fails.
        disk.fail = true
        assertTrue(runCatching { store.markCommitSent("a") }.isFailure)
        disk.fail = false
        val restarted = ProactiveTaskStore(disk)
        restarted.interruptRunning()
        assertEquals("same-final-payload", restarted.pendingCommits().single().commitPayload)
        assertEquals(listOf("stable-id"), restarted.pendingCommits().single().commitMessageIds)
        // Recovery verifies those exact Room IDs instead of generating or appending again.
        assertTrue(restarted.markCommitSent("a"))
        assertEquals(ProactiveTaskStatus.Sent, restarted.snapshot().tasks.single().status)
        assertTrue(restarted.pendingCommits().isEmpty())
    }

    @Test fun commitPayloadLimitIsFailClosedAndNoTaskStateTransition() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        assertFalse(store.beginCommit("a", host(), now, listOf("x"),
            "x".repeat(ProactiveTaskStore.PROACTIVE_COMMIT_MAX_BYTES + 1)))
        assertEquals(ProactiveTaskStatus.Running, store.snapshot().tasks.single().status)
    }

    @Test fun publicationIsSingleShotAndRevocationCannotWriteLateOutput() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        var publications = 0
        assertTrue(store.beginCommit("a", host(), now, listOf("id"), "payload").also { if (it) { publications++; store.startCommit("a"); store.markCommitSent("a") } })
        assertFalse(store.beginCommit("a", host(), now, listOf("id"), "payload").also { if (it) { publications++; store.startCommit("a"); store.markCommitSent("a") } })
        assertEquals(1, publications)
        assertEquals(ProactiveTaskStatus.Sent, store.snapshot().tasks.single().status)
        store.enqueue(task("b", "b")); store.claimNext(host(), now)
        store.cancel("b")
        assertFalse(store.beginCommit("b", host(), now, listOf("id-b"), "payload"))
        assertEquals(1, publications)
    }

    @Test fun outstandingFinalizerAndFreshPauseBlockPublication() {
        val store = ProactiveTaskStore(MemoryStorage())
        store.enqueue(task("a")); store.claimNext(host(), now)
        val token = requireNotNull(store.reserveTurn("a", host(), now))
        assertFalse(store.beginCommit("a", host(), now, listOf("id"), "payload"))
        store.markDispatched(token, host(), now)
        store.settleTurn(token)
        val paused = host().let { h -> h.copy(setting = h.setting.copy(
            globalPolicy = h.setting.globalPolicy.copy(paused = true))) }
        assertFalse(store.beginCommit("a", paused, now, listOf("id"), "payload"))
        assertTrue(store.beginCommit("a", host(), now, listOf("id"), "payload"))
    }

    @Test fun assistantQuietHoursCannotBypassGlobalQuietHours() {
        val h = host().let { it.copy(setting = it.setting.copy(assistantConfigs = listOf(
            configs[0].copy(customEnabled = true, activeHoursEnabled = true, activeStartHour = 13, activeEndHour = 14), configs[1],
        ))) }
        assertFalse(proactiveTaskAllowed(task("a"), h, now)) // UTC noon
        assertTrue(proactiveTaskAllowed(task("a").copy(trigger = "manual"), h, now))
        val globalQuiet = h.copy(setting = h.setting.copy(activeHoursEnabled = true,
            activeStartHour = 15, activeEndHour = 16))
        assertFalse(proactiveTaskAllowed(task("b", "b"), globalQuiet, now))
    }

    @Test fun terminalHistoryRetentionDoesNotPermanentlyFillScheduler() {
        val store = ProactiveTaskStore(MemoryStorage())
        repeat(600) { index ->
            assertTrue(store.enqueue(task("task-$index")))
            assertNotNull(store.claimNext(host().let { h -> h.copy(setting = h.setting.copy(
                assistantConfigs = configs.map { it.copy(dailyLimit = 1000) })) }, now))
            assertTrue(store.finish("task-$index", ProactiveTaskStatus.Failed, "pass"))
        }
        assertTrue(store.snapshot().tasks.size <= 257)
    }

    @Test fun corruptOrFailedStorageNeverResetsToFreshBudget() {
        val disk = MemoryStorage()
        val store = ProactiveTaskStore(disk)
        store.enqueue(task("a")); store.claimNext(host(), now)
        disk.fail = true
        assertTrue(runCatching { store.reserveTurn("a", host(), now) }.isFailure)
        disk.fail = false
        assertTrue(runCatching { store.reserveTurn("a", host(), now) }.isFailure)
        disk.value = "invalid json"
        assertTrue(runCatching { ProactiveTaskStore(disk).snapshot() }.isFailure)
    }

    @Test fun stalledSlotWithoutUserAnchorDefersToLongHorizon() {
        val store = ProactiveTaskStore(MemoryStorage())
        val h = host().copy(userAnchors = mapOf("cb" to "anchor")) // "ca" has no user anchor
        store.setNextSlot(task("a"), now) // due now, stalled
        store.setNextSlot(task("b", "b"), now)
        store.deferStalledSlots(h, now, claimedAssistantIds = emptySet(),
            noAnchorRetryAt = now + 240 * 60_000, retryAt = now + 30 * 60_000)
        assertEquals(now + 240 * 60_000, store.snapshot().nextSlots.getValue("a").dueAtMillis)
        // "b" has an anchor, is not capped and within active hours -> generic 30 min retry.
        assertEquals(now + 30 * 60_000, store.snapshot().nextSlots.getValue("b").dueAtMillis)
        assertEquals("anchor", store.snapshot().nextSlots.getValue("a").anchorUserMessageId)
    }

    @Test fun stalledSlotAtAssistantDailyLimitDefersToNextBudgetDayStart() {
        val store = ProactiveTaskStore(MemoryStorage())
        val h = host().let { it.copy(setting = it.setting.copy(
            assistantConfigs = listOf(configs[0].copy(customEnabled = true, dailyLimit = 1), configs[1]))) }
        // Charge assistant "a" its single daily task so the cap is hit.
        store.enqueue(task("a1")); store.claimNext(h, now)
        store.finish("a1", ProactiveTaskStatus.Failed, "pass")
        store.setNextSlot(task("a"), now)
        store.setNextSlot(task("b", "b"), now)
        store.deferStalledSlots(h, now, claimedAssistantIds = emptySet(),
            noAnchorRetryAt = now + 240 * 60_000, retryAt = now + 30 * 60_000)
        // now = 2026-06-01T12:00Z in UTC -> next budget day starts 2026-06-02T00:00Z + 60 s.
        val nextDayStart = Instant.parse("2026-06-02T00:00:00Z").toEpochMilli() + 60_000
        assertEquals(nextDayStart, store.snapshot().nextSlots.getValue("a").dueAtMillis)
        assertEquals(now + 30 * 60_000, store.snapshot().nextSlots.getValue("b").dueAtMillis)
    }

    @Test fun stalledSlotAtGlobalModelTurnLimitDefersToNextBudgetDayStart() {
        val store = ProactiveTaskStore(MemoryStorage())
        val h = host(limit = 1)
        store.enqueue(task("a")); store.claimNext(h, now)
        val token = requireNotNull(store.reserveTurn("a", h, now))
        store.markDispatched(token, h, now); store.settleTurn(token)
        store.finish("a", ProactiveTaskStatus.Failed, "pass")
        store.setNextSlot(task("a"), now)
        store.deferStalledSlots(h, now, claimedAssistantIds = emptySet(),
            noAnchorRetryAt = now + 240 * 60_000, retryAt = now + 30 * 60_000)
        val nextDayStart = Instant.parse("2026-06-02T00:00:00Z").toEpochMilli() + 60_000
        assertEquals(nextDayStart, store.snapshot().nextSlots.getValue("a").dueAtMillis)
    }

    @Test fun claimedAssistantAndBusyConversationAreNotDeferred() {
        val store = ProactiveTaskStore(MemoryStorage())
        val h = host().copy(userAnchors = emptyMap()) // both lack anchors
        store.setNextSlot(task("a"), now)
        store.setNextSlot(task("b", "b"), now)
        store.deferStalledSlots(h, now, claimedAssistantIds = setOf("a"),
            noAnchorRetryAt = now + 240 * 60_000, retryAt = now + 30 * 60_000)
        // Claimed "a" untouched; "b" deferred.
        assertEquals(now, store.snapshot().nextSlots.getValue("a").dueAtMillis)
        assertEquals(now + 240 * 60_000, store.snapshot().nextSlots.getValue("b").dueAtMillis)
        // A busy conversation is left for ChatService to reset after the reply.
        store.setNextSlot(task("a"), now)
        store.deferStalledSlots(h.copy(busyConversations = setOf("ca")), now,
            claimedAssistantIds = emptySet(), noAnchorRetryAt = now + 240 * 60_000,
            retryAt = now + 30 * 60_000)
        assertEquals(now, store.snapshot().nextSlots.getValue("a").dueAtMillis)
        // A future slot is not stalled at all.
        store.setNextSlot(task("a"), now + 999_000)
        store.deferStalledSlots(h, now, claimedAssistantIds = emptySet(),
            noAnchorRetryAt = now + 240 * 60_000, retryAt = now + 30 * 60_000)
        assertEquals(now + 999_000, store.snapshot().nextSlots.getValue("a").dueAtMillis)
    }

    @Test fun futureAppointmentAwaitedSlotIsNotDeferred() {
        val store = ProactiveTaskStore(MemoryStorage())
        // Slot in the past is stalled, but a still-Running task must not be pushed.
        store.enqueue(task("live")); store.claimNext(host(), now)
        store.setNextSlot(task("a"), now)
        store.deferStalledSlots(host(), now, claimedAssistantIds = emptySet(),
            noAnchorRetryAt = now + 240 * 60_000, retryAt = now + 30 * 60_000)
        assertEquals(now, store.snapshot().nextSlots.getValue("a").dueAtMillis)
    }
}
