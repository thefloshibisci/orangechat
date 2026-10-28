package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TurnPreparationTest {
    @Test fun `one reply shares a snapshot but a new reply performs fresh preparation`() = runBlocking {
        var calls = 0
        fun newTurn() = TurnPreparation { delay(1); ++calls }
        val turn = newTurn()
        assertEquals(List(8) { 1 }, List(8) { async { turn.get() } }.awaitAll())
        assertEquals(1, turn.get())
        assertEquals(2, newTurn().get())
    }

    @Test fun `failure and cancellation are never cached as a completed snapshot`() = runBlocking {
        var calls = 0
        val turn = TurnPreparation {
            when (++calls) {
                1 -> error("failed")
                2 -> throw CancellationException("cancelled")
                else -> "fresh"
            }
        }
        try { turn.get(); fail("failure expected") } catch (_: IllegalStateException) { }
        try { turn.get(); fail("cancellation expected") } catch (_: CancellationException) { }
        assertEquals("fresh", turn.get())
        assertEquals("fresh", turn.get())
        assertEquals(3, calls)
    }
}
