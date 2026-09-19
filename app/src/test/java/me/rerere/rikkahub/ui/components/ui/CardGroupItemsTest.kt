package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.*
import org.junit.Test

class CardGroupItemsTest {
    @Test fun `build creates a new snapshot instead of retaining initial descriptors`() {
        var received = false
        fun rows(checked: Boolean) = buildCardGroupItems {
            item(onClick = { received = checked }, headlineContent = {})
        }
        val initial = rows(false)
        val loaded = rows(true)
        assertNotSame(initial, loaded)
        loaded.single().onClick!!.invoke()
        assertTrue(received)
        initial.single().onClick!!.invoke()
        assertFalse(received)
    }

    @Test fun `conditional rows appear and disappear without accumulating`() {
        fun rows(showKey: Boolean) = buildCardGroupItems {
            item(headlineContent = {})
            if (showKey) item(headlineContent = {})
        }
        assertEquals(1, rows(false).size)
        repeat(5) {
            assertEquals(2, rows(true).size)
            assertEquals(1, rows(false).size)
        }
    }

    @Test fun `returned snapshot is detached from builder scope`() {
        lateinit var scope: CardGroupScope
        val rows = buildCardGroupItems {
            scope = this
            item(headlineContent = {})
        }
        scope.item(headlineContent = {})
        assertEquals(1, rows.size)
    }
}
