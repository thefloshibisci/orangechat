package me.rerere.rikkahub.ui.pages.extensions.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceSshFormTest {
    @Test fun invalidConnectionCannotProceedToNetworkProbe() {
        for (port in listOf("", "0", "65536", "22.5", "-1", "not-a-port")) {
            assertNotNull(validateSshWorkspaceForm("server", port, "user", "/work"))
        }
        for (host in listOf("", " ", "host name", "host\n", "host\u0000")) {
            assertNotNull(validateSshWorkspaceForm(host, "22", "user", "/work"))
        }
        assertNotNull(validateSshWorkspaceForm("server", "22", "", "/work"))
        assertNotNull(validateSshWorkspaceForm("server", "22", "user", "relative/path"))
        assertNotNull(validateSshWorkspaceForm("server", "22", "user", "/work\u0000"))
    }

    @Test fun validIPv6AndUnicodeDirectoryRemainSupported() {
        assertNull(validateSshWorkspaceForm("2001:db8::1", "22", "user", "/工作区/文件"))
        assertNull(validateSshWorkspaceForm("server.example", "65535", "user", "/"))
    }
}
