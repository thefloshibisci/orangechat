package me.rerere.rikkahub.plugin.scanner

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyPluginMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun legacy(root: File, id: String = "example") = File(root, id).apply {
        mkdirs()
        File(this, "manifest.json").writeText("{}")
        File(this, "index.js").writeText("old")
    }

    private fun migrate(source: File, target: File) = LegacyPluginMigration.migrate(source, target) { from, to ->
        check(from.copyRecursively(to))
    }

    @Test fun deletedMigratedPluginDoesNotReturnOnRefresh() {
        val source = temporary.newFolder("legacy")
        val target = temporary.newFolder("private")
        legacy(source)
        migrate(source, target)
        assertTrue(File(target, "example/index.js").isFile)
        assertTrue(File(target, "example").deleteRecursively())
        migrate(source, target)
        assertFalse(File(target, "example").exists())
        assertTrue(File(source, "example/index.js").isFile)
    }

    @Test fun existingPrivateVersionIsPreservedAndNotResurrected() {
        val source = temporary.newFolder("legacy")
        val target = temporary.newFolder("private")
        legacy(source)
        val installed = legacy(target)
        File(installed, "index.js").writeText("new")
        migrate(source, target)
        assertEquals("new", File(installed, "index.js").readText())
        installed.deleteRecursively()
        migrate(source, target)
        assertFalse(installed.exists())
    }

    @Test fun failedCopyCanBeRetriedAndNewLegacyPluginsCanStillMigrate() {
        val source = temporary.newFolder("legacy")
        val target = temporary.newFolder("private")
        legacy(source)
        val result = runCatching {
            LegacyPluginMigration.migrate(source, target) { _, _ -> throw IOException("copy failed") }
        }
        assertTrue(result.isFailure)
        migrate(source, target)
        legacy(source, "later")
        migrate(source, target)
        assertTrue(File(target, "example/index.js").isFile)
        assertTrue(File(target, "later/index.js").isFile)
    }
}
