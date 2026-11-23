package me.rerere.rikkahub.ui.components.message.listen

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import me.rerere.rikkahub.R

@Composable
internal fun ShareDisc(cover: Bitmap?, palette: MusicPalette, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension * .465f
            val under = center + Offset(.031f * r, .079f * r)
            drawCircle(Brush.radialGradient(
                0f to Color.Transparent, .49f to palette.ink.copy(alpha = .035f),
                .72f to palette.ink.copy(alpha = .065f), .83f to palette.ink.copy(alpha = .044f),
                .915f to palette.ink.copy(alpha = .022f), 1f to Color.Transparent,
                center = under, radius = r * 1.14f,
            ), r * 1.14f, under)
        }
        Image(painterResource(R.drawable.listen_record_absorption), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(palette.recordTint))
        Image(painterResource(R.drawable.listen_record_light), null, Modifier.fillMaxSize())
        Image(painterResource(R.drawable.listen_record_flecks_small), null, Modifier.fillMaxSize())
        val center = Modifier.fillMaxSize(.518f).clip(CircleShape)
        if (cover != null) Image(cover.asImageBitmap(), null, center, contentScale = ContentScale.Crop)
        else Canvas(center) {
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .67f), palette.glow.copy(alpha = .35f))))
            // A spindle, not the demo's non-functional choose-cover button.
            drawCircle(palette.accent.copy(alpha = .7f), size.minDimension * .065f)
        }
    }
}

@Composable
internal fun PlayGlyph(playing: Boolean, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        if (!playing) drawPath(Path().apply { moveTo(w*.34f,h*.19f); lineTo(w*.82f,h*.5f); lineTo(w*.34f,h*.81f); close() },color)
        else for (x in listOf(.28f, .57f)) drawRoundRect(color,Offset(w*x,h*.19f),Size(w*.15f,h*.62f),CornerRadius(w*.035f))
    }
}
