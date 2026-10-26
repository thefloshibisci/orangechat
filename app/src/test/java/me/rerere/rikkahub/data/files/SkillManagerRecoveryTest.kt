package me.rerere.rikkahub.data.files

import android.app.Application
import io.pebbletemplates.pebble.PebbleEngine
import java.security.Security
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.testing.TestAndroidKeyStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class SkillManagerRecoveryTest {
    private fun content(name: String) = "---\nname: $name\ndescription: test skill\n---\nbody"

    @Test fun `mismatched directory skill can be read edited and deleted without touching namesake`() = runBlocking {
        fixture { manager, store ->
            val skill = manager.saveSkill("actual-folder", content("declared-name"))!!
            val namesake = manager.getSkillsDir().resolve("declared-name").apply { mkdirs() }
            namesake.resolve("keep.txt").writeText("keep")
            assertEquals("body", manager.readSkillBody("actual-folder"))
            assertEquals("body", manager.readSkillBody("declared-name"))
            assertTrue(manager.saveSkillFile("actual-folder", "notes.md", "note"))
            assertEquals("note", skill.skillDir.resolve("notes.md").readText())
            val assistant = Assistant(enabledSkills = setOf(skill.name, "other-skill"))
            store.update { it.copy(assistants = listOf(assistant)) }
            assertTrue(manager.deleteSkill(skill))
            assertFalse(skill.skillDir.exists())
            assertEquals("keep", namesake.resolve("keep.txt").readText())
            assertEquals(setOf("other-skill"), store.settingsFlow.value.assistants.single { it.id == assistant.id }.enabledSkills)
            assertFalse(manager.deleteSkill(skill))
        }
    }

    @Test fun `declared name lookup handles renamed directory and duplicate deletion retains enablement`() = runBlocking {
        fixture { manager, store ->
            val first = manager.saveSkill("folder-one", content("shared"))!!
            manager.saveSkill("folder-two", content("shared"))!!
            assertEquals("body", manager.readSkillBody("shared"))
            assertEquals(first.skillDir.canonicalFile, manager.getSkillDir("folder-one"))
            val assistant = Assistant(enabledSkills = setOf("shared"))
            store.update { it.copy(assistants = listOf(assistant)) }
            assertTrue(manager.deleteSkill(first))
            assertEquals(setOf("shared"), store.settingsFlow.value.assistants.single { it.id == assistant.id }.enabledSkills)
            assertEquals(listOf("folder-two"), manager.listSkills().map { it.skillDir.name })
        }
    }

    @Test fun `invalid cleanup lists damaged directories and rechecks repaired skills`() = runBlocking {
        fixture { manager, _ ->
            val root = manager.getSkillsDir()
            root.resolve("empty").mkdirs()
            root.resolve("broken").apply { mkdirs(); resolve("SKILL.md").writeText("no frontmatter") }
            root.resolve(".install.staging.0.tmp").mkdirs()
            manager.saveSkill("valid", content("valid"))
            manager.saveSkill(".legacy-valid", content("legacy-valid"))
            assertEquals(setOf("broken", "empty"), manager.listInvalidSkillDirs().map { it.dirName }.toSet())
            assertEquals(setOf("valid", "legacy-valid"), manager.listSkills().map { it.name }.toSet())
            manager.saveSkill("broken", content("repaired"))
            assertFalse(manager.deleteInvalidSkillDir("broken"))
            assertTrue(root.resolve("broken/SKILL.md").isFile)
            assertTrue(manager.deleteInvalidSkillDir("empty"))
            assertFalse(root.resolve("empty").exists())
            assertFalse(manager.deleteInvalidSkillDir(".install.staging.0.tmp"))
        }
    }

    @Test fun `deletion rejects escaping paths and metadata from outside skill root`() = runBlocking {
        fixture { manager, _ ->
            val outside = manager.getSkillsDir().parentFile!!.resolve("outside-skill").apply { mkdirs() }
            try {
                outside.resolve("keep.txt").writeText("keep")
                manager.saveSkill("outside-skill", content("local"))
                assertFalse(manager.deleteInvalidSkillDir("../outside-skill"))
                assertFalse(manager.deleteInvalidSkillDir(".."))
                assertFalse(manager.deleteSkill(SkillMetadata("forged", "forged", skillDir = outside)))
                assertTrue(manager.getSkillsDir().resolve("outside-skill").isDirectory)
                assertEquals("keep", outside.resolve("keep.txt").readText())
            } finally {
                outside.deleteRecursively()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun fixture(block: suspend (SkillManager, SettingsStore) -> Unit) {
        Dispatchers.setMain(Dispatchers.Default)
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        val provider = TestAndroidKeyStore.TestProvider()
        Security.addProvider(provider)
        val scope = AppScope().also { it.cancel() }
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsStore(context, scope)
        val manager = SkillManager(context, store)
        manager.getSkillsDir().deleteRecursively()
        try {
            block(manager, store)
        } finally {
            manager.getSkillsDir().deleteRecursively()
            Security.removeProvider(provider.name)
            stopKoin()
            Dispatchers.resetMain()
        }
    }
}
