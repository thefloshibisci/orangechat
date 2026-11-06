package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.*
import org.junit.Test

class ChatPanelStateTest {
    @Test fun plusOpensMenuImmediatelyWhileAnyKeyboardIsHiding() {
        for (height in listOf(180, 260, 420)) {
            val panel = ChatPanelState()
            panel.toggle(true)
            assertTrue(panel.waitingForKeyboard)
            assertTrue(panel.expanded)
            panel.keyboardChanged(true, false, height)
            panel.keyboardChanged(false, false, height / 2)
            assertTrue(panel.expanded)
            panel.keyboardChanged(false, false, 0)
            assertTrue(panel.expanded)
            assertFalse(panel.waitingForKeyboard)
        }
    }
    @Test fun typingClosesMenuAsSoonAsKeyboardStartsLikeTheReferenceVideo() {
        val panel = ChatPanelState()
        panel.toggle(false)
        assertTrue(panel.expanded)
        panel.keyboardChanged(true, false, 1)
        assertFalse(panel.expanded)
    }
    @Test fun secondTapOrBackCancelsPendingExpansion() {
        val panel = ChatPanelState()
        panel.toggle(true)
        panel.toggle(true)
        panel.keyboardChanged(false, false)
        assertFalse(panel.expanded)
        panel.toggle(true)
        panel.close()
        panel.keyboardChanged(false, false)
        assertFalse(panel.expanded)
    }
    @Test fun childDialogKeyboardDoesNotDismissItsParentMenu() {
        val panel = ChatPanelState()
        panel.toggle(false)
        panel.keyboardChanged(true, true, 260)
        assertTrue(panel.expanded)
        panel.keyboardChanged(true, false, 260)
        assertFalse(panel.expanded)
    }
}
