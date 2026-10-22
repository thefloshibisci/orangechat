package me.rerere.rikkahub.data.sync

enum class ChatRestoreStep { READING, VALIDATING, WRITING, FINISHING }

/** Counts describe the current operation, not an estimated percentage of total restore time. */
data class ChatRestoreProgress(
    val message: String,
    val step: ChatRestoreStep = ChatRestoreStep.READING,
    val completed: Int? = null,
    val total: Int? = null,
) {
    val fraction: Float? get() = if (completed != null && total != null && total > 0)
        (completed.toFloat() / total).coerceIn(0f, 1f) else null
}
