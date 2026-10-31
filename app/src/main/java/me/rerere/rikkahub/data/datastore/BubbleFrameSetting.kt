package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
enum class BubbleStickerCorner { AUTO, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

@Serializable
data class BubbleFrameSetting(
    val path: String = "",
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val corner: BubbleStickerCorner = BubbleStickerCorner.AUTO,
) {
    fun normalized() = copy(
        offsetX = offsetX.takeIf { it.isFinite() }?.coerceIn(-24f, 24f) ?: 0f,
        offsetY = offsetY.takeIf { it.isFinite() }?.coerceIn(-24f, 24f) ?: 0f,
        scale = scale.takeIf { it.isFinite() }?.coerceIn(0.8f, 1.5f) ?: 1f,
    )
}
