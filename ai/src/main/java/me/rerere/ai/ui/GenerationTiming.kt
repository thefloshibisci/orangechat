package me.rerere.ai.ui

import kotlinx.serialization.Serializable

/** Read compatibility for records saved by the earlier trial build; no new timing is collected. */
@Serializable
data class ModelTurnTiming(
    val startedAtMs: Long,
    val firstResponseMs: Long? = null,
    val durationMs: Long = 0,
    val finished: Boolean = false,
    val interrupted: Boolean = false,
)

@Serializable
data class ToolTiming(
    val approvalStartedAtMs: Long? = null,
    val approvalFinishedAtMs: Long? = null,
    val executionStartedAtMs: Long? = null,
    val executionFinishedAtMs: Long? = null,
    val executionDurationMs: Long? = null,
)
