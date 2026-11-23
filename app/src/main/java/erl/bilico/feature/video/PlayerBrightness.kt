package erl.bilico.feature.video

import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.pow

/**
 * 播放器与评论区/全局 UI 的双轨亮度分离控制器：
 * - **往下调暗（目标亮度 <= 系统基准）**：不改宿主窗口的 `screenBrightness`（系统/评论区/返回首页零影响），
 *   仅在视频层之上、弹幕与播控之下叠加按 Gamma 2.2 映射的纯黑柔光遮罩（保留 SurfaceView 硬件直出零 GPU 功耗）。
 * - **往上调亮（目标亮度 > 系统基准）**：临时提升宿主窗口 `screenBrightness`，同时对竖屏下方的评论/简介区
 *   叠加按物理光强反算的补偿压暗，让评论区观感保持在原系统亮度；离开播放页、开始侧滑返回、收起播放器、
 *   切后台或进小窗时立即归还 `BRIGHTNESS_OVERRIDE_NONE`，绝不污染首页及整个 App 亮度。
 */
@Stable
internal class PlayerBrightnessController {
    /** 用户手势设定的目标视频亮度（0.02..1.0）；`null` 表示跟随系统亮度。 */
    var targetBrightness: Float? by mutableStateOf(null)
        private set

    /** 进入调节时采样的系统归一化亮度基准（0.02..1.0）。 */
    var systemBaseline: Float by mutableFloatStateOf(0.5f)
        private set

    /** 仅作用于视频画面的暗化遮罩透明度（往下调暗时生效，不碰窗口物理亮度）。 */
    val videoDimAlpha: Float
        get() {
            val target = targetBrightness ?: return 0f
            val base = systemBaseline.coerceAtLeast(0.02f)
            if (target >= base) return 0f
            val ratio = (target / base).coerceIn(0f, 1f)
            return (1f - ratio.pow(1f / GAMMA)).coerceIn(0f, MAX_VIDEO_DIM_ALPHA)
        }

    /** 仅当需要超过当前系统亮度时才借用宿主窗口物理亮度；否则返回 `null`（跟随系统）。 */
    val windowOverrideBrightness: Float?
        get() {
            val target = targetBrightness ?: return null
            val base = systemBaseline.coerceAtLeast(0.02f)
            return if (target > base + SNAP_TO_SYSTEM_EPSILON) target else null
        }

    /** 借用窗口物理提亮时，对竖屏下方评论区/信息页叠加的反向亮度补偿，抵消物理背光升高。 */
    val detailCompensationAlpha: Float
        get() {
            val override = windowOverrideBrightness ?: return 0f
            val base = systemBaseline.coerceAtLeast(0.02f)
            val ratio = (base / override).coerceIn(0.02f, 1f)
            return (1f - ratio.pow(1f / GAMMA)).coerceIn(0f, MAX_DETAIL_COMPENSATION_ALPHA)
        }

    /** 开始一次亮度手势：若尚未自定义则采样当前系统亮度，否则从上次目标亮度连续调节。 */
    fun beginGesture(context: Context): Float {
        val current = targetBrightness
        if (current != null) return current
        val baseline = readSystemBrightness(context)
        systemBaseline = baseline
        return baseline
    }

    /** 手势滑动过程中更新目标亮度；接近系统基准时平滑吸附回“跟随系统”。 */
    fun updateTarget(value: Float) {
        val clamped = value.coerceIn(MIN_BRIGHTNESS, 1f)
        targetBrightness = if (abs(clamped - systemBaseline) <= SNAP_TO_SYSTEM_EPSILON) {
            null
        } else {
            clamped
        }
    }

    fun reset() {
        targetBrightness = null
    }

    private companion object {
        const val GAMMA = 2.2f
        const val MIN_BRIGHTNESS = 0.02f
        const val SNAP_TO_SYSTEM_EPSILON = 0.015f
        const val MAX_VIDEO_DIM_ALPHA = 0.90f
        const val MAX_DETAIL_COMPENSATION_ALPHA = 0.78f
    }
}

internal val LocalPlayerBrightnessController =
    staticCompositionLocalOf<PlayerBrightnessController?> { null }

/**
 * 读取归一化系统亮度（0.02..1.0）：
 * 优先读 Android 11+ 标准 `screen_brightness_float`（精确适配 ColorOS / HyperOS / Pixel 高阶亮度屏）；
 * 回退读整型 `SCREEN_BRIGHTNESS` 并动态匹配系统最大亮度上限，避免在 0..4095/0..10000 机型上误除以 255 直接爆冲 100%。
 */
internal fun readSystemBrightness(context: Context): Float {
    val resolver = context.contentResolver
    val floatVal = runCatching {
        Settings.System.getFloat(resolver, "screen_brightness_float", -1f)
    }.getOrDefault(-1f)
    if (floatVal in 0.005f..1f) {
        return floatVal.coerceIn(0.02f, 1f)
    }

    val rawInt = runCatching {
        Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrNull() ?: return 0.5f
    if (rawInt <= 0) return 0.02f

    val configuredMax = runCatching {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        if (id != 0) res.getInteger(id) else 255
    }.getOrDefault(255).coerceAtLeast(255)

    val effectiveMax = when {
        rawInt <= configuredMax -> configuredMax.toFloat()
        rawInt <= 1023 -> 1023f
        rawInt <= 2047 -> 2047f
        rawInt <= 4095 -> 4095f
        rawInt <= 8191 -> 8191f
        rawInt <= 10000 -> 10000f
        else -> 16383f
    }
    return (rawInt / effectiveMax).coerceIn(0.02f, 1f)
}

internal fun applyWindowBrightnessOverride(window: Window?, brightness: Float?) {
    if (window == null) return
    val target = brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    val attrs = window.attributes
    if (attrs.screenBrightness == target) return
    attrs.screenBrightness = target
    window.attributes = attrs
}

internal fun clearWindowBrightnessOverride(window: Window?) {
    applyWindowBrightnessOverride(window, null)
}

/**
 * 根节点全局兜底：栈顶一旦不再是活跃播放页（含开始侧滑返回预览、退回首页、被其他页面盖住、
 * 收进融合球、进入画中画），立即归还系统亮度，防止任何情况下影响整个 App 亮度。
 */
@Composable
internal fun PlayerWindowBrightnessEffect(active: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(activity, active) {
        if (!active) {
            clearWindowBrightnessOverride(activity?.window)
        }
    }
}
