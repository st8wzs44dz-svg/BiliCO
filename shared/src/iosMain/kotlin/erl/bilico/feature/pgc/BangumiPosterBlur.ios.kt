package erl.bilico.feature.pgc

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize

@Composable
internal actual fun rememberBangumiPosterBlur(
    originalUrl: String,
    coverUrl: String,
    coverReady: Boolean,
    cardSize: IntSize,
    infoHeight: Int,
    generateBlur: Boolean,
    pauseBlur: () -> Boolean,
): ImageBitmap? = null
