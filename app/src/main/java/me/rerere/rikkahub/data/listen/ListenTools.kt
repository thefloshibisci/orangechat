package me.rerere.rikkahub.data.listen

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

fun listenTools(context: ToolInvocationContext): List<Tool> {
    if (context.isHeadless) return emptyList()
    val conversationId = context.callerConversationId ?: return emptyList()
    val assistantId = context.callerAssistantId ?: return emptyList()
    val repository = GlobalContext.getOrNull()?.getOrNull<ListenRepository>() ?: return emptyList()
    if (!repository.isAuthorized(conversationId, assistantId)) return emptyList()
    // Only songs actually returned by this tool or present in the real queue may be played.
    val catalog = linkedMapOf<String, ListenSong>()
    suspend fun checkAccess() {
        check(repository.isAuthorized(conversationId, assistantId)) { "当前聊天的陪听授权已关闭" }
        val conversation = GlobalContext.get().get<ConversationRepository>()
            .getConversationById(Uuid.parse(conversationId))
        check(conversation?.assistantId?.toString() == assistantId) { "当前聊天或助手已改变" }
        // Reading the conversation suspends; revocation can happen while Room is responding.
        check(repository.isAuthorized(conversationId, assistantId)) { "当前聊天的陪听授权已关闭" }
    }
    return listOf(Tool(
        name = "music_tool",
        description = "共度一起听。action 可用 now_playing、search(query)、lyrics(song_id，可省略为当前歌曲)、queue、" +
            "share(song_id,message,lines)、play_now、play_next、queue_add。歌曲ID须来自本轮搜索或当前队列。" +
            "message为点歌留言，lines为最多8句歌词。仅当前聊天显式授权时可用。" +
            "play_now在已播放时切歌，停止时准备歌曲等待用户点播放。声音仅在用户设备播放，你获得的是歌词和进度。",
        parameters = { InputSchema.Obj(buildJsonObject {
            put("action", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    listOf("now_playing", "search", "lyrics", "queue", "share", "play_now", "play_next", "queue_add").forEach { add(it) }
                })
            })
            for (key in listOf("query", "song_id", "message")) {
                put(key, buildJsonObject { put("type", "string") })
            }
            put("lines", buildJsonObject {
                put("type", "array"); put("maxItems", 8)
                put("items", buildJsonObject { put("type", "string") })
            })
        }, required = listOf("action")) },
        execute = { args ->
            try {
                checkAccess()
                val input = args as? JsonObject ?: error("参数必须是对象")
                fun string(key: String) = input[key]?.jsonPrimitive?.contentOrNull.orEmpty()
                val action = string("action")
                val result: String = when (action) {
                    "now_playing" -> buildJsonObject {
                        put("context", repository.nowPlayingContext(conversationId, assistantId))
                        put("audioProvided", false)
                    }.toString()
                    "search" -> {
                        val query = string("query").trim()
                        require(query.length in 1..200) { "query须为1至200字" }
                        val songs = repository.search(query).take(10)
                        checkAccess()
                        catalog.putAll(songs.associateBy { it.id })
                        while (catalog.size > 100) catalog.remove(catalog.keys.first())
                        buildJsonObject { put("songs", buildJsonArray { songs.forEach { add(songJson(it)) } }) }.toString()
                    }
                    "queue" -> buildJsonObject {
                        check(repository.state.value.conversationId == conversationId) { "当前聊天还没有播放队列" }
                        put("currentIndex", repository.state.value.currentIndex)
                        put("songs", buildJsonArray {
                            repository.state.value.queue.take(100).forEach { item ->
                                add(buildJsonObject {
                                    put("song", songJson(item.song)); put("origin", item.origin); put("message", item.message)
                                })
                            }
                        })
                    }.toString()
                    "lyrics" -> {
                        val id = string("song_id").ifBlank {
                            repository.state.value.takeIf { it.conversationId == conversationId }?.current?.song?.id.orEmpty()
                        }
                        require(id.matches(Regex("[0-9]{1,32}"))) { "需要有效的song_id" }
                        val lines = repository.lyrics(id)
                        checkAccess()
                        buildJsonObject { put("song_id", id); put("audioProvided", false)
                            put("lines", buildJsonArray { lines.take(200).forEach { line ->
                                add(buildJsonObject { put("timeMs", line.timeMs); put("text", line.text); put("translation", line.translation) })
                            } })
                        }.toString()
                    }
                    "share", "play_now", "play_next", "queue_add" -> {
                        val id = string("song_id")
                        val song = catalog[id] ?: repository.state.value.takeIf { it.conversationId == conversationId }
                            ?.queue?.firstOrNull { it.song.id == id }?.song
                            ?: error("请先搜索歌曲并使用真实返回的ID")
                        val message = string("message").take(1000)
                        val selected = (input["lines"] as? JsonArray).orEmpty().take(8).map { it.jsonPrimitive.content.take(300) }
                        if (selected.isNotEmpty()) {
                            val actualLyrics = repository.lyrics(song.id).flatMap { listOf(it.text, it.translation) }.toSet()
                            require(selected.all { it.isNotBlank() && it in actualLyrics }) { "分享的歌词须来自该歌曲的真实歌词" }
                        }
                        checkAccess()
                        when (action) {
                            "play_now" -> repository.playForAssistant(song, conversationId, assistantId, message)
                            "play_next" -> repository.enqueueForAssistant(song, conversationId, assistantId, next = true, message = message)
                            "queue_add" -> repository.enqueueForAssistant(song, conversationId, assistantId, message = message)
                        }
                        checkAccess()
                        if (action == "share" || action == "play_now") {
                            listenShareText(song, selected, message.ifBlank {
                                if (action == "play_now" && !repository.state.value.playing) "已准备好，点播放一起听" else ""
                            })
                        } else buildJsonObject { put("ok", true); put("action", action); put("song", songJson(song)) }.toString()
                    }
                    else -> error("不支持的音乐动作")
                }
                buildList {
                    add(UIMessagePart.Text(result))
                    if (action == "play_now") {
                        val playback = repository.state.value
                        add(UIMessagePart.Text(buildJsonObject {
                            put("playing", playback.playing)
                            put("loading", playback.loading)
                            put("status", if (playback.playing) "playing" else if (playback.loading) "buffering" else "prepared_waiting_for_user")
                        }.toString()))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                listOf(UIMessagePart.Text(buildJsonObject { put("error", error.message ?: "音乐操作失败") }.toString()))
            }
        },
    ))
}

private fun songJson(song: ListenSong): JsonObject = buildJsonObject {
    put("id", song.id); put("title", song.title); put("artist", song.artist)
    put("album", song.album); put("cover", song.cover); put("durationMs", song.durationMs)
}
