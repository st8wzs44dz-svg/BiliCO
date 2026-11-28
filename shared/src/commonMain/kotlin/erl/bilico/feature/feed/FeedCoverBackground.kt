package erl.bilico.feature.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.Image
import erl.bilico.data.settings.FeedCoverBlurStyle

internal data class FeedCoverBackdrop(
    val bitmap: ImageBitmap,
    val isLightSurface: Boolean,
    val titleColor: Color,
    val secondaryTextColor: Color,
    val statsColor: Color,
)

internal val FeedCoverOverlap = 44.dp

@Composable
internal expect fun rememberFeedCoverBackground(
    originalUrl: String,
    coverUrl: String,
    coverReady: Boolean,
    coverImage: Image?,
    cardSize: IntSize,
    coverHeight: Int,
    generateBackground: Boolean,
    blurStyle: FeedCoverBlurStyle,
    appDarkTheme: Boolean,
): FeedCoverBackdrop?
