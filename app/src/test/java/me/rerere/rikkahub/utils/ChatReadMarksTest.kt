package me.rerere.rikkahub.utils

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ChatReadMarksTest {
    @Test fun branchBuildTimestampReceiptsDoNotCrashOrMarkUnseenHistoryRead() {
        val context = RuntimeEnvironment.getApplication()
        val conversation = Uuid.random()
        val legacy = context.getSharedPreferences("chat_read_marks", Context.MODE_PRIVATE)
        legacy.edit().putLong(conversation.toString(), Long.MAX_VALUE).commit()
        assertTrue(ChatReadMarks.get(context, conversation).isEmpty())
        val visible = Uuid.random().toString()
        assertEquals(setOf(visible), ChatReadMarks.record(context, conversation, setOf(visible)))
        ChatReadMarks.delete(context, conversation)
        assertFalse(legacy.contains(conversation.toString()))
    }

    @Test fun onlySeenMessagesAreReadAcrossHistoryBranchesAndReopening() {
        val context = RuntimeEnvironment.getApplication()
        val conversation = Uuid.random()
        val other = Uuid.random()
        val oldSkipped = Uuid.random().toString()
        val unseenBranch = Uuid.random().toString()
        val visible = Uuid.random().toString()
        assertTrue(ChatReadMarks.get(context, conversation).isEmpty())
        ChatReadMarks.record(context, conversation, setOf(visible))
        val reopened = ChatReadMarks.get(context, conversation)
        assertEquals(setOf(visible), reopened)
        assertFalse(oldSkipped in reopened)
        assertFalse(unseenBranch in reopened)
        assertTrue(ChatReadMarks.get(context, other).isEmpty())
        assertEquals(setOf(visible, unseenBranch), ChatReadMarks.record(context, conversation, setOf(unseenBranch)))
        ChatReadMarks.delete(context, conversation)
        assertTrue(ChatReadMarks.get(context, conversation).isEmpty())
    }
}
