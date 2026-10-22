package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.summaryParagraphs
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class CompressionHistoryDividerTest {
    private fun base() = Conversation(assistantId = Uuid.random(), messageNodes = (1..10).map {
        MessageNode.of(if (it % 2 == 1) UIMessage.user("message $it") else UIMessage.assistant("reply $it"))
    })
    private fun compress(c: Conversation, keep: Int) = c.withCompressionResult(
        c, c.compressionCandidates(keep), summaryParagraphs("summary"),
    )
    private fun labels(c: Conversation, show: Boolean = true) = c.compressionDividerLabels(c.messageNodes, show)

    @Test fun successiveCompressionsMarkTheirOwnFinalMessages() {
        val first = compress(base(), 6)
        val second = compress(first, 2)
        assertEquals(mapOf(
            second.messageNodes[3].id to listOf("以上为第 1 次压缩前对话"),
            second.messageNodes[7].id to listOf("以上为第 2 次压缩前对话"),
        ), labels(second))
        assertTrue(labels(second, false).isEmpty())
        assertTrue(labels(base()).isEmpty())
    }

    @Test fun undoRemovesLatestDividerAndSummaryEditsDoNotAddOne() {
        val first = compress(base(), 6)
        val second = compress(first, 2)
        val edited = second.withSummaryEdits(second.compressionRevision, summaryParagraphs("edited"), false)
        assertEquals(labels(second), labels(edited))
        assertEquals(labels(first), labels(edited.undoLatestCompression(edited.compressionRevision)))
        assertTrue(labels(second.withoutCompression(second.compressionRevision)).isEmpty())
    }

    @Test fun legacyHistoryDoesNotInventCompressionNumber() {
        val legacy = compress(base(), 6).copy(compressionHistory = emptyList())
        assertEquals(listOf("以上为压缩前对话"), labels(legacy).values.single())
        val next = compress(legacy, 2)
        assertEquals(2, labels(next).size)
        assertTrue(labels(next).values.all { it == listOf("以上为压缩前对话") })
    }

    @Test fun hiddenBoundaryMessagePlacesDividerAfterPreviousVisibleMessage() {
        val c = compress(base(), 6)
        val displayed = c.messageNodes.filterIndexed { i, _ -> i != 3 }
        assertEquals(mapOf(c.messageNodes[2].id to listOf("以上为第 1 次压缩前对话")),
            c.compressionDividerLabels(displayed, true))
    }

    @Test fun forkKeepsBoundariesUsingCopiedMessageIds() {
        val c = compress(compress(base(), 6), 2)
        val copies = c.messageNodes.map { it.copy(id = Uuid.random()) }
        val fork = c.compressionStateForFork(c.messageNodes, copies)
        assertEquals(setOf(copies[3].id, copies[7].id), labels(fork).keys)
        assertEquals(labels(c).values.toList(), labels(fork).values.toList())
    }

    @Test fun damagedSnapshotCannotPlaceMarkerAtAnUnrelatedNode() {
        val c = compress(compress(base(), 6), 2)
        val damaged = c.copy(compressionHistory = c.compressionHistory.mapIndexed { i, snapshot ->
            if (i == 1) snapshot.copy(lastCompressedNodeId = Uuid.random()) else snapshot
        })
        assertEquals(mapOf(c.messageNodes[7].id to listOf("以上为压缩前对话")), labels(damaged))
    }
}
