package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantMemoryNumberingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deletionClosesDisplayGapWithoutChangingRecordIdentity() {
        val memories = mutableStateOf(listOf(
            AssistantMemory(41, "first"), AssistantMemory(43, "second"), AssistantMemory(99, "third"),
        ))
        val deletedIds = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                Column {
                    AssistantMemoryList(memories.value, onEditMemory = {}, onDeleteMemory = { memory ->
                        deletedIds.add(memory.id)
                        memories.value = memories.value.filterNot { it.id == memory.id }
                    })
                }
            }
        }
        compose.onNodeWithText("#1").assertExists()
        compose.onNodeWithText("#2").assertExists()
        compose.onNodeWithText("#3").assertExists()
        compose.onNodeWithText("#41").assertDoesNotExist()
        val deleteLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.assistant_page_delete)
        compose.onAllNodesWithContentDescription(deleteLabel)[1].performClick()
        compose.onNodeWithText("second").assertDoesNotExist()
        compose.onNodeWithText("#2").assertExists()
        compose.onNodeWithText("#3").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(41, 99), memories.value.map { it.id }) }
        // The newly numbered #2 must still delete database record 99, never record 2.
        compose.onAllNodesWithContentDescription(deleteLabel)[1].performClick()
        compose.runOnIdle { assertEquals(listOf(43, 99), deletedIds) }
        compose.onNodeWithText("#2").assertDoesNotExist()
        compose.runOnIdle { memories.value = memories.value + AssistantMemory(200, "new") }
        compose.onNodeWithText("#2").assertExists()
        compose.onNodeWithText("#200").assertDoesNotExist()
    }
}
