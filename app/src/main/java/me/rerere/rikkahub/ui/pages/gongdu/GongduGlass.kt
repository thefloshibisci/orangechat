package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

internal val GongduSans = FontFamily(Font(R.font.gongdu_sans_regular, FontWeight.Normal))
private fun TextStyle.gongduRegular() = copy(
    fontFamily = GongduSans, fontWeight = FontWeight.Normal, fontSynthesis = FontSynthesis.None)

/** Full bundled regular face keeps device system-font substitutions out of this room. */
@Composable
internal fun GongduRoomTheme(content: @Composable () -> Unit) {
    val t = MaterialTheme.typography
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val colors = MaterialTheme.colorScheme.copy(
        primary = if (dark) Color(0xFFC7B294) else Color(0xFF7B654C),
        onPrimary = if (dark) Color(0xFF332C25) else Color(0xFFFCF8F4),
        primaryContainer = if (dark) Color(0xFF504438) else Color(0xFFE8DDD1),
        onPrimaryContainer = if (dark) Color(0xFFE8DCCB) else Color(0xFF625346),
        secondaryContainer = if (dark) Color(0xFF4C4339) else Color(0xFFE4D9CC),
        onSecondaryContainer = if (dark) Color(0xFFE8DCCB) else Color(0xFF625346),
        surface = if (dark) Color(0xFF302B26) else Color(0xFFF5EEE9),
        surfaceContainerLow = if (dark) Color(0xFF39322C) else Color(0xFFF2ECE5),
        surfaceContainer = if (dark) Color(0xFF342D27) else Color(0xFFE9E0D6),
        outline = if (dark) Color(0xFFAA9479) else Color(0xFF9A8772),
        outlineVariant = if (dark) Color(0xFF685847) else Color(0xFFCBBBA9),
        onSurface = if (dark) Color(0xFFE6DCCD) else Color(0xFF554B43),
        onSurfaceVariant = if (dark) Color(0xFFC4B49F) else Color(0xFF74695F))
    MaterialTheme(colorScheme = colors, typography = t.copy(
        displayLarge = t.displayLarge.gongduRegular(), displayMedium = t.displayMedium.gongduRegular(),
        displaySmall = t.displaySmall.gongduRegular(), headlineLarge = t.headlineLarge.gongduRegular(),
        headlineMedium = t.headlineMedium.gongduRegular(), headlineSmall = t.headlineSmall.gongduRegular(),
        titleLarge = t.titleLarge.gongduRegular(), titleMedium = t.titleMedium.gongduRegular(),
        titleSmall = t.titleSmall.gongduRegular(), bodyLarge = t.bodyLarge.gongduRegular(),
        bodyMedium = t.bodyMedium.gongduRegular(), bodySmall = t.bodySmall.gongduRegular(),
        labelLarge = t.labelLarge.gongduRegular(), labelMedium = t.labelMedium.gongduRegular(),
        labelSmall = t.labelSmall.gongduRegular()), content = content)
}

/** This room owns its paper background, independent of the app wallpaper and opacity setting. */
@Composable
internal fun GongduWaterBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    GongduRoomTheme {
        val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
        val paper = if (dark) Color(0xFF2F2B27) else Color(0xFFF5EEE9)
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(modifier.fillMaxSize().background(paper)) {
                Image(painterResource(if (dark) R.drawable.gongdu_room_paper_dark else R.drawable.gongdu_room_paper),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                content()
            }
        }
    }
}

/** Two soft directional shadows and a fine bevel, following the user's HTML reference. */
@Composable
internal fun Modifier.gongduGlass(
    shape: Shape = RoundedCornerShape(20.dp), raised: Boolean = false, inset: Boolean = false,
): Modifier {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val top = if (dark) Color(0xFF484037) else Color(0xFFFCF8F4)
    val bottom = if (dark) Color(0xFF362E27) else Color(0xFFE7DDD2)
    val light = if (dark) Color(0xFFB6A089).copy(alpha = .12f) else Color(0xFFFFFCF8).copy(alpha = .9f)
    val shade = if (dark) Color.Black.copy(alpha = .4f) else Color(0xFF8B7761).copy(alpha = .23f)
    val fill = Brush.linearGradient(if (inset) listOf(bottom, top) else listOf(top, bottom))
    val rim = Brush.linearGradient(if (inset) listOf(shade, light) else listOf(light, shade.copy(alpha = .15f)))
    val outer = if (inset) this else
        dropShadow(shape, Shadow(radius = if (raised) 5.dp else 8.dp, color = shade, offset = DpOffset(3.dp, 4.dp)))
            .dropShadow(shape, Shadow(radius = 4.dp, color = light, offset = DpOffset((-2).dp, (-2).dp)))
    val face = outer.background(fill, shape)
    val depth = if (inset) face
        .innerShadow(shape, Shadow(radius = 4.dp, color = shade, offset = DpOffset(2.dp, 3.dp)))
        .innerShadow(shape, Shadow(radius = 2.dp, color = light, offset = DpOffset((-1).dp, (-1).dp))) else face
    return depth.border(.6.dp, rim, shape).clip(shape)
}

@Composable
internal fun GongduButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp), contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The visible face is compact; its surrounding slot retains a 48dp touch area.
    Box(modifier.width(IntrinsicSize.Max).heightIn(min = 48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Button(onClick = onClick, enabled = enabled, shape = shape, interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 32.dp)
                    .gongduGlass(shape, raised = true, inset = pressed),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface, disabledContainerColor = Color.Transparent,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)),
                elevation = null, contentPadding = contentPadding, content = content)
        }
    }
}

@Composable
internal fun GongduSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedTrackColor = MaterialTheme.colorScheme.secondaryContainer,
            checkedThumbColor = MaterialTheme.colorScheme.surface, uncheckedThumbColor = MaterialTheme.colorScheme.surface,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        thumbContent = { Box(Modifier.size(20.dp).gongduGlass(androidx.compose.foundation.shape.CircleShape, raised = true)) })
}
