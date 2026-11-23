package me.rerere.rikkahub.ui.components.message

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.speechDurationMs
import java.io.File

internal data class SavedSpeechPlaybackState(
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

internal interface SavedSpeechPlayback {
    val state: StateFlow<SavedSpeechPlaybackState>
    fun toggle()
    fun close()
}

internal fun totalSpeechDuration(clips: List<UIMessagePart.Audio>): Long =
    clips.map { it.speechDurationMs() }.takeIf { it.isNotEmpty() && it.all { ms -> ms > 0 } }?.sum() ?: 0

internal fun speechDurationLabel(durationMs: Long): String = if (durationMs <= 0) "—:—" else {
    val seconds = (durationMs + 999) / 1000
    "%d:%02d".format(seconds / 60, seconds % 60)
}

/** Single active voice bubble. Scoped instances never read the global TTS synthesis state. */
private object SavedSpeechFocus {
    var active: SavedSpeechPlayer? = null
    fun claim(player: SavedSpeechPlayer) {
        if (active !== player) active?.pause()
        active = player
    }
}

/** This player has no TTS/provider dependency and only accepts local saved attachments. */
internal class SavedSpeechPlayer(
    private val context: Context,
    private val clips: List<UIMessagePart.Audio>,
    private val createPlayer: () -> Player = {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
    },
) : SavedSpeechPlayback {
    private var player: Player? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private val durations = clips.map { it.speechDurationMs() }.toMutableList()
    private val mutableState = MutableStateFlow(SavedSpeechPlaybackState(durationMs = totalSpeechDuration(clips)))
    override val state = mutableState.asStateFlow()

    override fun toggle() {
        val current = player
        if (current?.playWhenReady == true) { pause(); return }
        try {
            require(clips.isNotEmpty()) { "这条语音没有保存音频，重新生成可能计费。" }
            clips.forEach { clip ->
                val uri = Uri.parse(clip.url)
                require(uri.scheme == "file" || uri.scheme == "content" || uri.scheme == null) { "只播放已保存的本地音频" }
                if (uri.scheme == "file" || uri.scheme == null) {
                    val file = File(uri.path ?: clip.url)
                    require(file.isFile && file.length() > 0) { "语音文件已丢失，未重新生成或请求语音服务。" }
                }
            }
            SavedSpeechFocus.claim(this)
            mutableState.value = state.value.copy(error = null, buffering = current == null)
            val target = current ?: createPlayer().also { created ->
                player = created
                created.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { update() }
                    override fun onPlayerError(error: PlaybackException) {
                        mutableState.value = state.value.copy(playing = false, buffering = false,
                            error = "本地音频播放失败，未重新生成或请求语音服务。")
                    }
                })
                created.setMediaItems(clips.map { MediaItem.fromUri(it.url) })
            }
            if (target.playbackState == Player.STATE_ENDED) target.seekTo(0, 0)
            if (target.playbackState == Player.STATE_IDLE) target.prepare()
            target.play()
            if (ticker?.isActive != true) ticker = scope.launch {
                while (player?.playWhenReady == true && player?.playbackState != Player.STATE_ENDED) {
                    update(); delay(100)
                }
                update()
            }
        } catch (e: Exception) {
            mutableState.value = state.value.copy(playing = false, buffering = false, error = e.message)
        }
    }

    fun pause() { player?.pause(); update() }

    private fun update() {
        val current = player ?: return
        val timeline = current.currentTimeline
        val window = Timeline.Window()
        for (index in durations.indices) {
            if (index < timeline.windowCount) {
                val measured = timeline.getWindow(index, window).durationMs
                if (measured > 0) durations[index] = measured
            }
        }
        val total = if (durations.all { it > 0 }) durations.sum() else 0L
        val ended = current.playbackState == Player.STATE_ENDED
        val index = current.currentMediaItemIndex.coerceIn(0, durations.lastIndex.coerceAtLeast(0))
        val elapsed = if (ended) 0 else durations.take(index).sum() + current.currentPosition.coerceAtLeast(0)
        mutableState.value = state.value.copy(
            playing = current.playWhenReady && !ended && current.playerError == null,
            buffering = current.playbackState == Player.STATE_BUFFERING,
            positionMs = elapsed,
            durationMs = total,
        )
    }

    override fun close() {
        if (SavedSpeechFocus.active === this) SavedSpeechFocus.active = null
        scope.cancel()
        player?.release()
        player = null
    }
}
