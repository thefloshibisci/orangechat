package me.rerere.rikkahub.data.listen

import kotlinx.serialization.Serializable

@Serializable
data class ListenSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val cover: String = "",
    val durationMs: Long = 0,
)

@Serializable
data class ListenQueueItem(
    val id: String,
    val song: ListenSong,
    val origin: String = "user",
    val message: String = "",
)

@Serializable
data class ListenLyric(val timeMs: Long, val text: String, val translation: String = "")

@Serializable
data class ListenPlaylist(val id: String, val title: String, val cover: String = "", val count: Int = 0)

@Serializable
data class ListenNotes(
    val notes: String = "",
    val feeling: String = "",
    val favoriteLines: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

data class ListenLogin(val key: String, val qrUrl: String)
data class ListenAccount(val loggedIn: Boolean, val nickname: String = "", val avatarUrl: String = "")

data class ListenConfig(
    val baseUrl: String = "",
    val token: String = "",
    val conversationId: String = "",
    val assistantId: String = "",
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()
    override fun toString() = "ListenConfig(configured=$configured, token=***)"
}

@Serializable
data class ListenState(
    val conversationId: String = "",
    val queue: List<ListenQueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val lyrics: List<ListenLyric> = emptyList(),
    val error: String? = null,
    val mode: String = "order",
    @kotlinx.serialization.Transient val lyricStatus: ListenLyricStatus = ListenLyricStatus.NOT_REQUESTED,
) {
    val current: ListenQueueItem? get() = queue.getOrNull(currentIndex)
    val currentLyricIndex: Int get() = lyrics.indexOfLast { it.timeMs <= positionMs }
}

enum class ListenLyricStatus(val emptyLabel: String) {
    NOT_REQUESTED("尚未读取歌词"), LOADING("正在读取歌词…"), AVAILABLE(""), EMPTY("音乐服务未返回可显示的歌词"), FAILED("歌词读取失败，播放不受影响")
}

internal fun nextListenIndex(state: ListenState, automatic: Boolean = false): Int {
    if (state.queue.isEmpty()) return -1
    if (automatic && state.mode == "repeat") return state.currentIndex.coerceAtLeast(0)
    if (state.mode == "shuffle" && state.queue.size > 1) {
        return state.queue.indices.filter { it != state.currentIndex }.random()
    }
    return (state.currentIndex + 1).mod(state.queue.size)
}

internal fun ListenState.forRestore(): ListenState = copy(
    queue = queue.take(200),
    currentIndex = currentIndex.takeIf { it in queue.take(200).indices } ?: -1,
    playing = false,
    loading = false,
    positionMs = positionMs.coerceAtLeast(0),
    lyrics = emptyList(), lyricStatus = ListenLyricStatus.NOT_REQUESTED,
    error = null,
    mode = mode.takeIf { it in setOf("order", "shuffle", "repeat") } ?: "order",
)

internal fun ListenState.afterPlaybackInterrupted() = copy(playing = false, loading = false,
    lyricStatus = lyricStatus.takeUnless { it == ListenLyricStatus.LOADING } ?: ListenLyricStatus.NOT_REQUESTED)
