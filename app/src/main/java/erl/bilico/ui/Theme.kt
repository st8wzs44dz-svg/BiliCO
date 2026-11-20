package erl.bilico.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.Colors
import io.github.suqi8.coui.kmp.theme.ThemeController
import io.github.suqi8.coui.kmp.theme.lightColorScheme

/** 浅色页面色。COUI 的浅色卡片是白的，底面留白会让卡片边界消失。 */
private val LightPage = Color(0xFFF0F1F2)

/** 番剧影院深色背景，顶栏与内容区共用同一色阶，避免出现色差断层。 */
internal val BangumiCinemaBackground = Color(0xFF141414)

/** 深色模式设置：0 跟随系统，1 浅色，2 深色。 */
@Composable
fun isDarkTheme(darkMode: Int): Boolean = when (darkMode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun BiliCoTheme(darkMode: Int, content: @Composable () -> Unit) {
    val controller = remember(darkMode) {
        ThemeController(
            colorSchemeMode = when (darkMode) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            },
            lightColors = lightColorScheme(background = LightPage),
        )
    }
    COUITheme(controller = controller, content = content)
}

/** 在两套 COUI 配色之间按连续进度插值，供顶栏/底栏外壳跟手平滑切换明暗。 */
internal fun lerpColors(start: Colors, stop: Colors, fraction: Float): Colors {
    val t = fraction.coerceIn(0f, 1f)
    if (t <= 0.001f) return start
    if (t >= 0.999f) return stop
    return Colors(
        primary = lerp(start.primary, stop.primary, t),
        onPrimary = lerp(start.onPrimary, stop.onPrimary, t),
        primaryVariant = lerp(start.primaryVariant, stop.primaryVariant, t),
        onPrimaryVariant = lerp(start.onPrimaryVariant, stop.onPrimaryVariant, t),
        error = lerp(start.error, stop.error, t),
        onError = lerp(start.onError, stop.onError, t),
        errorContainer = lerp(start.errorContainer, stop.errorContainer, t),
        onErrorContainer = lerp(start.onErrorContainer, stop.onErrorContainer, t),
        disabledPrimary = lerp(start.disabledPrimary, stop.disabledPrimary, t),
        disabledOnPrimary = lerp(start.disabledOnPrimary, stop.disabledOnPrimary, t),
        disabledPrimaryButton = lerp(start.disabledPrimaryButton, stop.disabledPrimaryButton, t),
        disabledOnPrimaryButton = lerp(start.disabledOnPrimaryButton, stop.disabledOnPrimaryButton, t),
        disabledPrimarySlider = lerp(start.disabledPrimarySlider, stop.disabledPrimarySlider, t),
        primaryContainer = lerp(start.primaryContainer, stop.primaryContainer, t),
        onPrimaryContainer = lerp(start.onPrimaryContainer, stop.onPrimaryContainer, t),
        secondary = lerp(start.secondary, stop.secondary, t),
        onSecondary = lerp(start.onSecondary, stop.onSecondary, t),
        secondaryVariant = lerp(start.secondaryVariant, stop.secondaryVariant, t),
        onSecondaryVariant = lerp(start.onSecondaryVariant, stop.onSecondaryVariant, t),
        disabledSecondary = lerp(start.disabledSecondary, stop.disabledSecondary, t),
        disabledOnSecondary = lerp(start.disabledOnSecondary, stop.disabledOnSecondary, t),
        disabledSecondaryVariant = lerp(start.disabledSecondaryVariant, stop.disabledSecondaryVariant, t),
        disabledOnSecondaryVariant = lerp(start.disabledOnSecondaryVariant, stop.disabledOnSecondaryVariant, t),
        secondaryContainer = lerp(start.secondaryContainer, stop.secondaryContainer, t),
        onSecondaryContainer = lerp(start.onSecondaryContainer, stop.onSecondaryContainer, t),
        secondaryContainerVariant = lerp(start.secondaryContainerVariant, stop.secondaryContainerVariant, t),
        onSecondaryContainerVariant = lerp(start.onSecondaryContainerVariant, stop.onSecondaryContainerVariant, t),
        tertiaryContainer = lerp(start.tertiaryContainer, stop.tertiaryContainer, t),
        onTertiaryContainer = lerp(start.onTertiaryContainer, stop.onTertiaryContainer, t),
        tertiaryContainerVariant = lerp(start.tertiaryContainerVariant, stop.tertiaryContainerVariant, t),
        background = lerp(start.background, stop.background, t),
        onBackground = lerp(start.onBackground, stop.onBackground, t),
        onBackgroundVariant = lerp(start.onBackgroundVariant, stop.onBackgroundVariant, t),
        surface = lerp(start.surface, stop.surface, t),
        onSurface = lerp(start.onSurface, stop.onSurface, t),
        surfaceVariant = lerp(start.surfaceVariant, stop.surfaceVariant, t),
        onSurfaceSecondary = lerp(start.onSurfaceSecondary, stop.onSurfaceSecondary, t),
        onSurfaceVariantSummary = lerp(start.onSurfaceVariantSummary, stop.onSurfaceVariantSummary, t),
        onSurfaceVariantActions = lerp(start.onSurfaceVariantActions, stop.onSurfaceVariantActions, t),
        disabledOnSurface = lerp(start.disabledOnSurface, stop.disabledOnSurface, t),
        surfaceContainer = lerp(start.surfaceContainer, stop.surfaceContainer, t),
        onSurfaceContainer = lerp(start.onSurfaceContainer, stop.onSurfaceContainer, t),
        onSurfaceContainerVariant = lerp(start.onSurfaceContainerVariant, stop.onSurfaceContainerVariant, t),
        surfaceContainerHigh = lerp(start.surfaceContainerHigh, stop.surfaceContainerHigh, t),
        onSurfaceContainerHigh = lerp(start.onSurfaceContainerHigh, stop.onSurfaceContainerHigh, t),
        surfaceContainerHighest = lerp(start.surfaceContainerHighest, stop.surfaceContainerHighest, t),
        onSurfaceContainerHighest = lerp(start.onSurfaceContainerHighest, stop.onSurfaceContainerHighest, t),
        outline = lerp(start.outline, stop.outline, t),
        dividerLine = lerp(start.dividerLine, stop.dividerLine, t),
        windowDimming = lerp(start.windowDimming, stop.windowDimming, t),
        sliderKeyPoint = lerp(start.sliderKeyPoint, stop.sliderKeyPoint, t),
        sliderKeyPointForeground = lerp(start.sliderKeyPointForeground, stop.sliderKeyPointForeground, t),
        sliderBackground = lerp(start.sliderBackground, stop.sliderBackground, t),
        bar = lerp(start.bar, stop.bar, t),
        labelPrimaryVariant = lerp(start.labelPrimaryVariant, stop.labelPrimaryVariant, t),
        controls = lerp(start.controls, stop.controls, t),
        labelTertiary = lerp(start.labelTertiary, stop.labelTertiary, t),
        pressBackground = lerp(start.pressBackground, stop.pressBackground, t),
        quarternaryNeutral = lerp(start.quarternaryNeutral, stop.quarternaryNeutral, t),
        containerTheme = lerp(start.containerTheme, stop.containerTheme, t),
        focus = lerp(start.focus, stop.focus, t),
        focusOutline = lerp(start.focusOutline, stop.focusOutline, t),
    )
}
