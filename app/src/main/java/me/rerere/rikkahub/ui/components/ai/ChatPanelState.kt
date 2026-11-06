package me.rerere.rikkahub.ui.components.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Start both sides of the handoff together; ignore the outgoing keyboard until it has hidden. */
internal class ChatPanelState {
    var expanded by mutableStateOf(false)
        private set
    var waitingForKeyboard by mutableStateOf(false)
        private set
    fun toggle(keyboardVisible: Boolean) {
        if (expanded || waitingForKeyboard) close()
        else {
            expanded = true
            waitingForKeyboard = keyboardVisible
        }
    }

    fun keyboardChanged(visible: Boolean, childDialogOpen: Boolean, heightPx: Int = 0) {
        if (childDialogOpen) return
        if (waitingForKeyboard) {
            if (!visible && heightPx == 0) {
                waitingForKeyboard = false
            }
        } else if (visible) close()
    }

    fun close() {
        expanded = false
        waitingForKeyboard = false
    }
}
