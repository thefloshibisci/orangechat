package me.rerere.rikkahub.ui.components.ai

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteModelOrderTest {
    @Test fun `filtered row movement uses model keys in complete favorite list`() {
        val image = Uuid.random()
        val chatA = Uuid.random()
        val embedding = Uuid.random()
        val chatB = Uuid.random()
        val original = listOf(image, chatA, embedding, chatB)
        assertEquals(listOf(image, chatB, chatA, embedding),
            moveFavoriteByKey(original, "favorite:$chatB", "favorite:$chatA"))
        assertEquals(listOf(image, chatA, embedding, chatB), original)
        assertEquals(original, moveFavoriteByKey(original, "header:provider", "favorite:$chatA"))
        assertEquals(original, moveFavoriteByKey(original, "favorite:$chatA", "header:provider"))
    }

    @Test fun `consecutive drag moves use immediate preview instead of stale stored order`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val c = Uuid.random()
        val preview = moveFavoriteByKey(listOf(a, b, c), "favorite:$a", "favorite:$b")
        assertEquals(listOf(b, c, a), moveFavoriteByKey(preview, "favorite:$a", "favorite:$c"))
    }

    @Test fun `save retains concurrent membership changes without reviving removed favorites`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val removed = Uuid.random()
        val added = Uuid.random()
        assertEquals(listOf(b, added, a), mergeFavoriteOrder(listOf(a, added, b), listOf(b, removed, a)))
        assertEquals(emptyList<Uuid>(), mergeFavoriteOrder(emptyList(), listOf(a, b)))
    }
}
