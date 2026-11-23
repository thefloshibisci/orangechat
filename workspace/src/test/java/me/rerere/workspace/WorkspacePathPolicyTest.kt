package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspacePathPolicyTest {
    @Test fun wrongMachinePathsAndSiblingPrefixesAreRejected() {
        for (path in listOf("/workspace/mail.py", "/srv/chat-other/mail.py", "/srv/chat/../../etc/passwd", "/etc/passwd")) {
            assertTrue(path, runCatching { WorkspacePathPolicy.requireInside("/srv/chat", path) }.isFailure)
        }
        assertEquals("/srv/chat/mail.py", WorkspacePathPolicy.requireInside("/srv/chat/", "/srv/chat/notes/../mail.py"))
    }

    @Test fun absoluteAndRelativeCwdShareTheSameBoundary() {
        assertEquals("letters", WorkspacePathPolicy.cwd("/srv/chat", "/srv/chat/letters"))
        assertEquals("letters", WorkspacePathPolicy.cwd("/srv/chat", "notes/../letters"))
        assertEquals("", WorkspacePathPolicy.cwd("/srv/chat", ""))
        for (path in listOf("..", "nested/../../x", "/workspace", "/srv/chat/../other")) {
            assertTrue(path, runCatching { WorkspacePathPolicy.cwd("/srv/chat", path) }.isFailure)
        }
    }

    @Test fun invalidPathFormsAreRejected() {
        for (path in listOf("relative", "C:/workspace/x", "/srv/chat/../../..", "/srv/chat/\u0000x", "/srv/chat\\escape")) {
            assertTrue(path, runCatching { WorkspacePathPolicy.normalize(path) }.isFailure)
        }
        assertEquals("/note", WorkspacePathPolicy.requireInside("/", "/note"))
    }
}
