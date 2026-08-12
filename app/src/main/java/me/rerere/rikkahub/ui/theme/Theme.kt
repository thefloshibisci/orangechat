/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.VisualThemePalette
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }

private val AMOLED_DARK_BACKGROUND = Color(0xFF000000)

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun RikkahubTheme(
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val colorMode by rememberColorMode()
    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    val colorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val theme = findThemeById(settings.themeId, settings.customThemes)
                ?: findPresetTheme(settings.themeId)
            theme.getColorScheme(dark = darkTheme)
        }
    }
    val colorSchemeConverted = remember(darkTheme, amoledDarkMode, colorScheme) {
        if (darkTheme && amoledDarkMode) {
            colorScheme.copy(
                background = AMOLED_DARK_BACKGROUND,
                surface = AMOLED_DARK_BACKGROUND,
            )
        } else {
            colorScheme
        }
    }
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors

    // 颜色自定义覆盖
    val finalColorScheme = remember(
        colorSchemeConverted,
        settings.displaySetting.primaryColor,
        settings.displaySetting.globalTextColor,
        settings.displaySetting.visualThemePalette,
        settings.themeId,
    ) {
        var scheme = colorSchemeConverted
        scheme = when (settings.displaySetting.visualThemePalette) {
            VisualThemePalette.ORIGINAL -> scheme
            VisualThemePalette.SEA_SALT -> scheme.copy(
                primary = if (darkTheme) Color(0xFF9ACFD1) else Color(0xFF4F858A),
                onPrimary = if (darkTheme) Color(0xFF003739) else Color.White,
                primaryContainer = if (darkTheme) Color(0xFF215054) else Color(0xFFD9EEEE),
                onPrimaryContainer = if (darkTheme) Color(0xFFB7EAEB) else Color(0xFF173F42),
                secondary = if (darkTheme) Color(0xFFB1CCCA) else Color(0xFF627C7B),
                secondaryContainer = if (darkTheme) Color(0xFF334B4A) else Color(0xFFDDE9E8),
                tertiary = if (darkTheme) Color(0xFFAEC8DF) else Color(0xFF607A91),
                background = if (darkTheme) Color(0xFF101919) else Color(0xFFF5F9F9),
                surface = if (darkTheme) Color(0xFF101919) else Color(0xFFF8FBFB),
                surfaceContainerLow = if (darkTheme) Color(0xFF172222) else Color(0xFFF1F6F6),
                surfaceContainer = if (darkTheme) Color(0xFF1B2727) else Color(0xFFEBF2F2),
                surfaceContainerHigh = if (darkTheme) Color(0xFF243030) else Color(0xFFE4EDED),
                surfaceContainerHighest = if (darkTheme) Color(0xFF2D3939) else Color(0xFFDDE7E7),
            )
            VisualThemePalette.SAKURA_MIST -> scheme.copy(
                primary = Color(0xFFA75E78),
                secondary = Color(0xFF8C6974),
                tertiary = Color(0xFF80658F),
                primaryContainer = Color(0xFFFFD9E4),
                secondaryContainer = Color(0xFFF4DDE4),
            )
            VisualThemePalette.AURORA -> scheme.copy(
                primary = Color(0xFF3D7E68),
                secondary = Color(0xFF52736B),
                tertiary = Color(0xFF536C8D),
                primaryContainer = Color(0xFFBDEED7),
                secondaryContainer = Color(0xFFCFE9DF),
            )
            VisualThemePalette.MIDNIGHT -> scheme.copy(
                primary = Color(0xFF7D91D4),
                secondary = Color(0xFF707A9A),
                tertiary = Color(0xFF8B70A9),
                primaryContainer = Color(0xFFDCE1FF),
                secondaryContainer = Color(0xFFE0E2F2),
            )
        }
        settings.displaySetting.primaryColor?.let { pc ->
            val primaryColor = pc.toComposeColor()
            val luminance = 0.299f * primaryColor.red + 0.587f * primaryColor.green + 0.114f * primaryColor.blue
            val onPrimary = if (luminance > 0.5f) Color.Black else Color.White
            scheme = scheme.copy(
                primary = primaryColor,
                onPrimary = onPrimary,
            )
        }
        settings.displaySetting.globalTextColor?.let { gtc ->
            val textColor = gtc.toComposeColor()
            scheme = scheme.copy(
                onBackground = textColor,
                onSurface = textColor,
                onSurfaceVariant = textColor,
            )
        }
        if (settings.themeId == "pearltide") {
            // 珍珠潮汐主题专属:统一让默认读取这几个 token 的容器变透明/半透明,
            // 这样 Scaffold、Card、TopAppBar、ModalDrawerSheet、ModalBottomSheet 等
            // 全部自动生效,不需要逐个页面单独改。
            // 这个 if 分支只在 themeId 精确等于 "pearltide" 时才会执行,
            // 其余六个官方预设(id 分别是 ocean/sakura/spring/autumn/black/claude)
            // 以及用户自定义主题(id 是随机 UUID,不会等于这个字符串)完全不受影响。
            val glassAlpha = 0.6f
            scheme = scheme.copy(
                background = scheme.background.copy(alpha = 0f),
                surface = scheme.surface.copy(alpha = glassAlpha),
                surfaceBright = scheme.surfaceBright.copy(alpha = glassAlpha),
                surfaceDim = scheme.surfaceDim.copy(alpha = glassAlpha),
                surfaceContainerLowest = scheme.surfaceContainerLowest.copy(alpha = glassAlpha),
                surfaceContainerLow = scheme.surfaceContainerLow.copy(alpha = glassAlpha),
                surfaceContainer = scheme.surfaceContainer.copy(alpha = glassAlpha),
                surfaceContainerHigh = scheme.surfaceContainerHigh.copy(alpha = glassAlpha),
                surfaceContainerHighest = scheme.surfaceContainerHighest.copy(alpha = glassAlpha),
                surfaceVariant = scheme.surfaceVariant.copy(alpha = glassAlpha),
            )
        }
        scheme
    }

    // 更新状态栏图标颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalExtendColors provides extendColors,
        LocalOverscrollFactory provides null
    ) {
        MaterialExpressiveTheme(
            colorScheme = finalColorScheme,
            typography = Typography,
            content = content,
            motionScheme = MotionScheme.expressive()
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current
