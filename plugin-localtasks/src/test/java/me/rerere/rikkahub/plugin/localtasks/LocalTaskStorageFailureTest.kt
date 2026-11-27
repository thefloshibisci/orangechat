package me.rerere.rikkahub.plugin.localtasks

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTaskStorageFailureTest {
    @Test
    fun `failed commit poisons all adapters for that preferences namespace`() {
        val values = mutableMapOf<String, String>()
        var commits = 0
        val editor = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                // Android can update its process cache even when disk commit returns false.
                "putString" -> { values[args[0] as String] = args[1] as String; proxy }
                "commit" -> { commits++; false }
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString" -> values[args[0] as String] ?: args[1]
                else -> error("Unexpected preferences method ${method.name}")
            }
        } as SharedPreferences
        val name = "failure-${UUID.randomUUID()}"
        val first = SharedPreferencesLocalTaskStore(prefs, name)
        val second = SharedPreferencesLocalTaskStore(prefs, name)
        val ledger = LocalTaskLedger(second)

        assertThrows(IllegalStateException::class.java) { first.write("ledger", "unconfirmed") }
        assertEquals("unconfirmed", values["ledger"])
        assertThrows(IllegalStateException::class.java) { second.read("ledger") }
        assertThrows(IllegalStateException::class.java) { ledger.snapshot() }
        assertThrows(IllegalStateException::class.java) { second.write("ledger", "replacement") }
        assertEquals(1, commits)
        assertTrue(SharedPreferencesLocalTaskStore(prefs, "$name-other").read("missing") == null)
    }
}
