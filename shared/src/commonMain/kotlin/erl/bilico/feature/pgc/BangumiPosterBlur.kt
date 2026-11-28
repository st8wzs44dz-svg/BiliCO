package erl.bilico.feature.pgc

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** 底部文字区上方紧贴的渐变模糊过渡带高度，尽量多保留清晰海报画面。 */
internal val BangumiPosterBlurFade = 16.dp

@Composable
internal expect fun rememberBangumiPosterBlur(
    originalUrl: String,
    coverUrl: String,
    coverReady: Boolean,
    cardSize: IntSize,
    infoHeight: Int,
    generateBlur: Boolean,
    pauseBlur: () -> Boolean = { false },
): ImageBitmap?
