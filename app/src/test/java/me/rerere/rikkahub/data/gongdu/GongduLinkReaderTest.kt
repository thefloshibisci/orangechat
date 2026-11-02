package me.rerere.rikkahub.data.gongdu

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class GongduLinkReaderTest {
    @Test fun mainlandShortLinksResolveWithTheSameRedirectGuards(): Unit = runBlocking {
        val feed = GongduLinkReader { GongduLinkRedirect(302, longUrl) }
            .resolve("分享 https://xhslink.cn/o/sample，打开小红书")
        assertEquals(id, feed.id)
        assertEquals("AbC+DEF==", feed.token)
        assertThrows(IllegalArgumentException::class.java) {
            GongduLinkReader.inputUrl("https://xhslink.cn.evil.test/o/sample")
        }
    }
    private val id = "69001234567890abcdef1234"
    private val longUrl = "https://www.xiaohongshu.com/explore/$id?xsec_token=AbC%2BDEF%3D%3D&xsec_source=pc_feed"

    @Test fun completeLongLinkDoesNotFetchAndRetainsTheFullDecodedToken() = runBlocking {
        val feed = GongduLinkReader { error("Direct link must not fetch") }.resolve(longUrl)
        assertEquals(id, feed.id)
        assertEquals("AbC+DEF==", feed.token)
    }

    @Test fun shareTextShortLinkUsesHttpsAndFollowsValidatedRelativeRedirects() = runBlocking {
        val visited = mutableListOf<String>()
        val feed = GongduLinkReader { url ->
            visited += url.toString()
            if (visited.size == 1) GongduLinkRedirect(302, "/o/second")
            else GongduLinkRedirect(302, longUrl)
        }.resolve("好有趣的一条笔记 http://xhslink.com/o/first，复制后打开小红书")
        assertEquals(listOf("https://xhslink.com/o/first", "https://xhslink.com/o/second"), visited)
        assertEquals(id, feed.id)
        assertEquals("AbC+DEF==", feed.token)
    }

    @Test fun discoveryItemLinksAndOfficialSubdomainsAreSupported() = runBlocking {
        val feed = GongduLinkReader { error("No request expected") }.resolve(
            "https://www.xiaohongshu.com/discovery/item/$id?xsec_token=complete-token",
        )
        assertEquals(id, feed.id)
        assertEquals("complete-token", feed.token)
        assertEquals("share.xhslink.com", GongduLinkReader.inputUrl("https://share.xhslink.com/o/test").host)
    }

    @Test fun credentialsPortsForeignHostsAndMalformedInputsCannotReachTheFetcher() = runBlocking {
        val reader = GongduLinkReader { error("Forbidden input must not fetch") }
        val invalid = listOf(
            "https://evil.test/o/link", "https://xiaohongshu.com.evil.test/o/link",
            "https://user:password@xhslink.com/o/link", "https://xhslink.com:8443/o/link",
            "http://xhslink.com:443/o/link", "https://127.0.0.1/o/link",
            "https://[::1]/o/link", "file:///private/file", "just a share code",
            "https://xhslink.com/o/one https://xhslink.com/o/two",
        )
        invalid.forEach { input -> assertTrue(input, runCatching { reader.resolve(input) }.isFailure) }
    }

    @Test fun unsafeRedirectIsRejectedBeforeAnotherRequest() = runBlocking {
        listOf("https://evil.test/steal", "http://127.0.0.1/private",
            "https://user:pass@xiaohongshu.com/explore/$id?xsec_token=secret",
            "https://xhslink.com:8443/private").forEach { destination ->
            var requests = 0
            val reader = GongduLinkReader { requests++; GongduLinkRedirect(302, destination) }
            assertTrue(runCatching { reader.resolve("https://xhslink.com/o/start") }.isFailure)
            assertEquals(1, requests)
        }
    }

    @Test fun redirectLoopsMakeAtMostFiveRequests() = runBlocking {
        var requests = 0
        val reader = GongduLinkReader { requests++; GongduLinkRedirect(302, "/o/loop") }
        val error = runCatching { reader.resolve("https://xhslink.com/o/start") }.exceptionOrNull()
        assertEquals(5, requests)
        assertTrue(error!!.message!!.contains("超过5次"))
    }

    @Test fun missingOrDuplicateTokenFailsWithoutGuessingOrFetchingTheDetail() = runBlocking {
        listOf("https://www.xiaohongshu.com/explore/$id",
            "https://www.xiaohongshu.com/explore/$id?xsec_token=",
            "https://www.xiaohongshu.com/explore/$id?xsec_token=one&xsec_token=two").forEach { destination ->
            val reader = GongduLinkReader { GongduLinkRedirect(302, destination) }
            val error = runCatching { reader.resolve("https://xhslink.com/o/start") }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("xsec_token"))
            assertFalse(error.message!!.contains("xsec_token="))
        }
    }

    @Test fun nonRedirectResponseAndMissingLocationAreExplicitFailures() = runBlocking {
        listOf(GongduLinkRedirect(200), GongduLinkRedirect(403), GongduLinkRedirect(302)).forEach { response ->
            assertTrue(runCatching {
                GongduLinkReader { response }.resolve("https://xhslink.com/o/start")
            }.isFailure)
        }
    }

    @Test fun privateDnsAnswersAreRejectedEvenForAnAllowedHostname() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1",
            "169.254.169.254", "100.64.0.1", "0.1.2.3", "198.18.0.1",
            "::1", "fc00::1", "fe80::1").forEach { address ->
            assertFalse(address, GongduLinkReader.publicAddress(InetAddress.getByName(address)))
        }
        assertTrue(GongduLinkReader.publicAddress(InetAddress.getByName("1.1.1.1")))
        assertTrue(GongduLinkReader.publicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
}
