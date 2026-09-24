package me.rerere.rikkahub.data.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeFileResolverTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun resolvesNestedAndExistingAbsolutePluginFiles() {
        val root = temporary.newFolder("plugin")
        val target = File(root, "music/song.mp3").canonicalFile
        assertEquals(target, SafeFileResolver.resolveInside(root, "music/song.mp3"))
        assertEquals(target, SafeFileResolver.resolveInside(root, target.path))
    }

    @Test fun rejectsTraversalSiblingPrefixAndNullBytes() {
        val root = temporary.newFolder("plugin")
        assertNull(SafeFileResolver.resolveInside(root, "../plugin-other/secret"))
        assertNull(SafeFileResolver.resolveInside(root, "..\\secret"))
        assertNull(SafeFileResolver.resolveInside(root, "a\u0000b"))
        assertNull(SafeFileResolver.resolveInside(root, temporary.root.absolutePath))
    }

    @Test fun allowsRootOnlyWhenExplicitlyRequested() {
        val root = temporary.newFolder("plugin").canonicalFile
        assertNull(SafeFileResolver.resolveInside(root, "."))
        assertEquals(root, SafeFileResolver.resolveInside(root, "", allowRoot = true))
    }
}
