package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MarkdownParseCacheTest {
    @Test fun uiLookupOnlyReturnsPreviouslyParsedValues() {
        val cache = MarkdownParseCache<Any>(maxEntries = 2)
        assertNull(cache.get("missing"))
        val a = cache.getOrParse("a") { Any() }
        cache.getOrParse("b") { Any() }
        assertSame(a, cache.get("a"))
        cache.getOrParse("c") { Any() }
        assertNull(cache.get("b"))
        assertSame(a, cache.get("a"))
    }

    @Test fun repeatedViewportEntryParsesCompletedTextOnce() {
        val cache = MarkdownParseCache<Any>()
        var parses = 0
        val first = cache.getOrParse("# 同一条消息") { parses++; Any() }
        repeat(1000) { assertSame(first, cache.getOrParse("# 同一条消息") { parses++; Any() }) }
        assertEquals(1, parses)
    }

    @Test fun streamingChangesAndReturningToAnOldBranchUseExactText() {
        val cache = MarkdownParseCache<String>()
        var parses = 0
        fun render(text: String) = cache.getOrParse(text) { parses++; "parsed:$it" }
        assertEquals("parsed:one", render("one"))
        assertEquals("parsed:one two", render("one two"))
        assertEquals("parsed:one", render("one"))
        assertEquals(2, parses)
    }

    @Test fun leastRecentlyUsedEntryIsEvicted() {
        val cache = MarkdownParseCache<Any>(maxEntries = 2)
        val a = cache.getOrParse("a") { Any() }
        val b = cache.getOrParse("b") { Any() }
        assertSame(a, cache.getOrParse("a") { Any() })
        cache.getOrParse("c") { Any() }
        assertSame(a, cache.getOrParse("a") { Any() })
        assertNotSame(b, cache.getOrParse("b") { Any() })
    }

    @Test fun characterBudgetAndOversizedMessagesDoNotGrowTheCache() {
        val cache = MarkdownParseCache<Any>(maxEntries = 10, maxSourceChars = 5)
        val small = cache.getOrParse("a") { Any() }
        val oversized = cache.getOrParse("123456") { Any() }
        assertNotSame(oversized, cache.getOrParse("123456") { Any() })
        assertSame(small, cache.getOrParse("a") { Any() })
        val old = cache.getOrParse("bbb") { Any() }
        cache.getOrParse("ccc") { Any() }
        assertNotSame(old, cache.getOrParse("bbb") { Any() })
    }

    @Test fun slowBackgroundParseDoesNotBlockCachedUiReads() {
        val cache = MarkdownParseCache<Any>()
        val cached = cache.getOrParse("cached") { Any() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val parsing = workers.submit<Any> { cache.getOrParse("large") { entered.countDown(); release.await(); Any() } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val uiRead = workers.submit<Any> { cache.getOrParse("cached") { error("Cache hit must not parse") } }
            assertSame(cached, uiRead.get(3, TimeUnit.SECONDS))
            release.countDown(); parsing.get(3, TimeUnit.SECONDS)
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun concurrentParsesPublishOneStableCachedResult() {
        val cache = MarkdownParseCache<Any>()
        val entered = CountDownLatch(4)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = (1..4).map { workers.submit<Any> {
                cache.getOrParse("same") { entered.countDown(); check(entered.await(3, TimeUnit.SECONDS)); Any() }
            } }.map { it.get(3, TimeUnit.SECONDS) }
            results.forEach { assertSame(results.first(), it) }
        } finally { workers.shutdownNow() }
    }
}
