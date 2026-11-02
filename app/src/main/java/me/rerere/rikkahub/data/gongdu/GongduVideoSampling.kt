package me.rerere.rikkahub.data.gongdu

import kotlin.math.ceil

data class GongduVideoRequest(
    val startSeconds: Double? = null,
    val endSeconds: Double? = null,
    val intervalSeconds: Double = 2.0,
)

data class GongduVideoPlan(val startMs: Long, val endMs: Long, val times: List<Long>, val overview: Boolean)

/** A bounded overview of the whole video; explicit windows provide denser reinspection. */
fun gongduVideoPlan(durationMs: Long, request: GongduVideoRequest = GongduVideoRequest()): GongduVideoPlan {
    require(durationMs in 1..180_000) { "支持 3 分钟以内的视频" }
    require(request.intervalSeconds.isFinite() && request.intervalSeconds in 0.5..5.0) { "抽帧间隔应为 0.5～5 秒" }
    require(listOfNotNull(request.startSeconds, request.endSeconds).all { it.isFinite() && it >= 0 }) { "时间必须是非负有限秒数" }
    val overview = request.startSeconds == null && request.endSeconds == null
    val start = ((request.startSeconds ?: 0.0) * 1000).toLong()
    require(start < durationMs) { "起点超过视频时长" }
    val end = request.endSeconds?.let { (it * 1000).toLong().coerceAtMost(durationMs) }
        ?: if (overview) durationMs else (start + 30_000).coerceAtMost(durationMs)
    require(end > start) { "结束时间应晚于起点" }
    require(overview || end - start <= 30_000) { "补看请每次指定 30 秒以内，可分段连续调用" }
    val span = end - start - 1
    val count = (ceil(span / (request.intervalSeconds * 1000)).toInt() + 1).coerceIn(1, 24)
    val times = if (count == 1) listOf(start) else (0 until count).map { start + span * it / (count - 1) }.distinct()
    return GongduVideoPlan(start, end, times, overview)
}
