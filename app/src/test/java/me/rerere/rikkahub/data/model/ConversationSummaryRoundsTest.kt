package me.rerere.rikkahub.data.model

import kotlinx.serialization.decodeFromString
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSummaryRoundsTest {
    @Test fun `paragraphs from one compression share a group`() {
        val parts = summaryParagraphs("a\n\nb\n\nc")
        assertEquals(3, parts.size)
        assertEquals(1, parts.map { it.group }.toSet().size)
        assertEquals(1, parts.summaryRounds().size)
    }

    @Test fun `legacy json without group decodes and resolves rounds from checkpoints`() {
        val first = ConversationSummary(text = "round one")
        val second = ConversationSummary(text = "round two a")
        val third = ConversationSummary(text = "round two b")
        val legacy = JsonInstant.decodeFromString<List<ConversationSummary>>(
            """[{"id":"${first.id}","text":"round one","sendToModel":true},""" +
                """{"id":"${second.id}","text":"round two a","sendToModel":true},""" +
                """{"id":"${third.id}","text":"round two b","sendToModel":false}]"""
        )
        assertTrue(legacy.all { it.group == null })
        val history = listOf(
            ConversationCompressionSnapshot(emptyList(), 0, null, true),
            ConversationCompressionSnapshot(listOf(legacy[0]), 2, null, true),
        )
        val resolved = legacy.withResolvedGroups(history)
        val rounds = resolved.summaryRounds()
        assertEquals(listOf(listOf("round one"), listOf("round two a", "round two b")), rounds.map { r -> r.map { it.text } })
        assertEquals(legacy.map { it.id }, resolved.map { it.id })
        assertEquals(legacy.map { it.sendToModel }, resolved.map { it.sendToModel })
    }

    @Test fun `resolving keeps existing groups untouched`() {
        val parts = summaryParagraphs("x\n\ny")
        assertTrue(parts.withResolvedGroups(emptyList()) === parts)
    }

    @Test fun `whole text edit keeps position ids and flags of unchanged paragraphs`() {
        val r1 = summaryParagraphs("first")
        val r2 = summaryParagraphs("keep\n\nold").let { listOf(it[0].copy(sendToModel = false), it[1]) }
        val r3 = summaryParagraphs("last")
        val group = r2[0].group!!
        val edited = (r1 + r2 + r3).withRoundText(group, "keep\n\nnew one\n\nnew two")
        assertEquals(listOf("first", "keep", "new one", "new two", "last"), edited.map { it.text })
        assertEquals(r2[0].id, edited[1].id)
        assertFalse(edited[1].sendToModel)
        // New paragraphs follow the round's current selection (partially selected -> send).
        assertTrue(edited[2].sendToModel && edited[3].sendToModel)
        assertTrue(edited.subList(1, 4).all { it.group == group })
    }

    @Test fun `deselected round stays deselected after editing`() {
        val round = summaryParagraphs("a").map { it.copy(sendToModel = false) }
        val edited = round.withRoundText(round[0].group!!, "a\n\nb")
        assertTrue(edited.none { it.sendToModel })
    }

    @Test fun `clearing a round leaves a blank entry so save is blocked instead of silently dropping it`() {
        val round = summaryParagraphs("a")
        val edited = round.withRoundText(round[0].group!!, "   ")
        assertEquals(1, edited.size)
        assertTrue(edited.single().text.isBlank())
    }

    @Test fun `split lines keeps the round`() {
        val part = summaryParagraphs("line1\nline2").single()
        val split = part.splitLines()
        assertEquals(2, split.size)
        assertTrue(split.all { it.group == part.group })
    }

    @Test fun `group defaults to null for hand built entries`() {
        assertNull(ConversationSummary(text = "t").group)
        assertEquals(1, listOf(ConversationSummary(text = "a"), ConversationSummary(text = "b")).summaryRounds().size)
    }
}
