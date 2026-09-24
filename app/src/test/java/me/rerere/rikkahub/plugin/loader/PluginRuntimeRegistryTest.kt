package me.rerere.rikkahub.plugin.loader

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class PluginRuntimeRegistryTest {
    private fun CountDownLatch.awaitOrFail() {
        assertTrue("Runtime operation did not complete", await(3, TimeUnit.SECONDS))
    }

    @Test fun blockedLoadCanBeRemovedAndDoesNotBlockAnotherPlugin() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val owner = AtomicLong()
        val destroyThread = AtomicLong()
        val registry = PluginRuntimeRegistry<String> {
            if (it == "slow") {
                destroyThread.set(Thread.currentThread().id)
                destroyed.countDown()
            }
        }
        try {
            registry.load("slow", {
                owner.set(Thread.currentThread().id)
                started.countDown()
                release.awaitOrFail()
                "slow"
            }) { published.set(true) }
            started.awaitOrFail()
            registry.remove("slow")
            assertFalse(registry.ids().contains("slow"))
            registry.load("healthy", { "healthy" }) {}
            assertEquals("healthy", registry.call("healthy", 1000) { it }.getOrThrow())
            assertNull(registry.get("slow"))
            release.countDown()
            destroyed.awaitOrFail()
            assertFalse(published.get())
            assertEquals(owner.get(), destroyThread.get())
        } finally {
            release.countDown()
            registry.close()
        }
    }

    @Test fun oldLoadCannotOverwriteReplacementOrPublishItsResult() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val staleResult = AtomicBoolean(false)
        val registry = PluginRuntimeRegistry<String> { if (it == "old") destroyed.countDown() }
        try {
            registry.load("plugin", {
                started.countDown()
                release.awaitOrFail()
                "old"
            }) { staleResult.set(true) }
            started.awaitOrFail()
            registry.load("plugin", { "new-config" }) {}
            assertEquals("new-config", registry.call("plugin", 1000) { it }.getOrThrow())
            release.countDown()
            destroyed.awaitOrFail()
            assertEquals("new-config", registry.get("plugin"))
            assertFalse(staleResult.get())
        } finally {
            release.countDown()
            registry.close()
        }
    }

    @Test fun toolCallWaitsForItsOwnLoadAndUsesTheSameThread() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = AtomicLong()
        val registry = PluginRuntimeRegistry<String> {}
        try {
            registry.load("plugin", {
                owner.set(Thread.currentThread().id)
                started.countDown()
                release.awaitOrFail()
                "configured"
            }) {}
            started.awaitOrFail()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                registry.call("plugin", 1000) { it to Thread.currentThread().id }
            }
            assertFalse(pending.isCompleted)
            release.countDown()
            assertEquals("configured" to owner.get(), pending.await().getOrThrow())
        } finally {
            release.countDown()
            registry.close()
        }
    }

    @Test fun queuedCallCannotExecuteAfterDisable() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executed = AtomicBoolean(false)
        val registry = PluginRuntimeRegistry<String> {}
        try {
            registry.load("plugin", { started.countDown(); release.awaitOrFail(); "ready" }) {}
            started.awaitOrFail()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                registry.call("plugin", 1000) { executed.set(true) }
            }
            registry.remove("plugin")
            release.countDown()
            assertTrue(pending.await().isFailure)
            assertFalse(executed.get())
        } finally {
            release.countDown()
            registry.close()
        }
    }

    @Test fun synchronousCallTimeoutDoesNotWaitForNativeWorkToReturn() = runBlocking {
        val release = CountDownLatch(1)
        val registry = PluginRuntimeRegistry<String> {}
        try {
            registry.load("plugin", { "ready" }) {}
            registry.call("plugin", 1000) { it }.getOrThrow()
            val result = registry.call("plugin", 100) { release.awaitOrFail() }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("timed out"))
            registry.remove("plugin")
            assertTrue(registry.ids().isEmpty())
        } finally {
            release.countDown()
            registry.close()
        }
    }

    @Test fun failedPluginCanBeRetriedWithoutBlockingHealthyPlugin() = runBlocking {
        val failed = CountDownLatch(1)
        val registry = PluginRuntimeRegistry<String> {}
        try {
            registry.load("broken", { error("bad script") }) { if (it.isFailure) failed.countDown() }
            failed.awaitOrFail()
            assertFalse(registry.isLoading("broken"))
            assertNull(registry.get("broken"))
            registry.load("healthy", { "ok" }) {}
            assertEquals("ok", registry.call("healthy", 1000) { it }.getOrThrow())
            registry.load("broken", { "fixed" }) {}
            assertEquals("fixed", registry.call("broken", 1000) { it }.getOrThrow())
        } finally {
            registry.close()
        }
    }

    @Test fun closedRegistryRejectsLoadsAndCalls() = runBlocking {
        val registry = PluginRuntimeRegistry<String> {}
        registry.close()
        assertTrue(runCatching { registry.load("plugin", { "ready" }) {} }.isFailure)
        assertTrue(registry.call("plugin", 1000) { it }.isFailure)
    }
}
