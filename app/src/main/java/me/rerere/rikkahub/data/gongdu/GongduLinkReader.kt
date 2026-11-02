package me.rerere.rikkahub.data.gongdu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class GongduLinkRedirect(val status: Int, val location: String? = null)

/** Resolve only public Xiaohongshu share links. Never downloads a page body or runs shell commands. */
internal class GongduLinkReader(
    private val fetch: (HttpUrl) -> GongduLinkRedirect = ::fetchRedirect,
) {
    suspend fun resolve(input: String): GongduFeed = withContext(Dispatchers.IO) {
        var url = inputUrl(input)
        noteReference(url)?.let { return@withContext it }
        repeat(5) {
            currentCoroutineContext().ensureActive()
            val response = fetch(url)
            require(response.status in setOf(301, 302, 303, 307, 308)) {
                "分享链接未返回包含笔记标识的跳转，请使用小红书重新分享的完整链接"
            }
            val location = response.location?.takeIf { it.isNotBlank() && it.length <= 8192 }
                ?: error("分享链接缺少有效跳转地址")
            url = checkedUrl(url.resolve(location) ?: error("分享链接跳转地址无效"))
            noteReference(url)?.let { return@withContext it }
        }
        error("分享链接跳转超过5次，请重新分享笔记")
    }

    companion object {
        private val shareUrl = Regex("https?://[^\\s<>\"“”‘’，。；！？（）【】《》]+", RegexOption.IGNORE_CASE)
        private val noteId = Regex("[A-Za-z0-9_-]{6,128}")

        fun inputUrl(input: String): HttpUrl {
            require(input.isNotBlank() && input.length <= 16000) { "请提供一条小红书分享链接或分享文字" }
            val urls = shareUrl.findAll(input).map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }.toList()
            require(urls.size == 1) { "请每次提供一条完整的小红书分享链接" }
            return checkedUrl(urls.single().toHttpUrlOrNull() ?: error("分享链接格式无效"))
        }

        fun checkedUrl(url: HttpUrl): HttpUrl {
            require(url.scheme in setOf("http", "https") && url.username.isEmpty() && url.password.isEmpty() &&
                url.port == (if (url.scheme == "https") 443 else 80)) { "仅支持不含账号和自定义端口的公开分享链接" }
            require(allowedHost(url.host)) { "只支持 xhslink.com 和 xiaohongshu.com 的小红书链接" }
            return if (url.scheme == "http") url.newBuilder().scheme("https").port(443).build() else url
        }

        private fun allowedHost(host: String): Boolean =
            host == "xhslink.com" || host.endsWith(".xhslink.com") ||
                host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com")

        fun noteReference(url: HttpUrl): GongduFeed? {
            checkedUrl(url)
            if (!(url.host == "xiaohongshu.com" || url.host.endsWith(".xiaohongshu.com"))) return null
            val segments = url.pathSegments.filter(String::isNotBlank)
            val id = when {
                segments.size == 2 && segments[0] == "explore" -> segments[1]
                segments.size == 3 && segments[0] == "discovery" && segments[1] == "item" -> segments[2]
                else -> return null
            }
            require(noteId.matches(id)) { "分享链接中的笔记标识无效" }
            val tokens = url.queryParameterValues("xsec_token")
            require(tokens.size == 1 && !tokens.single().isNullOrBlank()) {
                "分享链接缺少完整 xsec_token，请在小红书重新复制分享链接；不会编造访问令牌"
            }
            val token = tokens.single()!!
            require(token.length <= 2048 && token.none { it.isWhitespace() || it.isISOControl() }) {
                "分享链接中的访问令牌格式无效，请重新复制"
            }
            return GongduFeed(id, "分享的笔记", token)
        }

        internal fun publicAddress(address: InetAddress): Boolean {
            val bytes = address.address
            val ipv6Private = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
            val first = bytes[0].toInt() and 255
            val second = bytes[1].toInt() and 255
            val ipv4Private = bytes.size == 4 &&
                (first == 0 || first >= 224 || (first == 100 && second in 64..127) ||
                    (first == 198 && second in 18..19))
            return !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress && !address.isMulticastAddress && !ipv6Private && !ipv4Private
        }
    }
}

private val linkHttp by lazy {
    OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .dns(Dns { hostname ->
            Dns.SYSTEM.lookup(hostname).also { addresses ->
                require(addresses.isNotEmpty() && addresses.all { GongduLinkReader.publicAddress(it) }) {
                    "分享链接不能访问本地或内网地址"
                }
            }
        }).build()
}

private fun fetchRedirect(url: HttpUrl): GongduLinkRedirect = try {
    linkHttp.newCall(Request.Builder().url(url).get().build()).execute().use {
        GongduLinkRedirect(it.code, it.header("Location"))
    }
} catch (_: IOException) {
    throw IOException("小红书分享链接读取失败，请重试或重新复制完整分享链接")
}
