package erl.bilico.feature.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntSize
import coil3.Image
import erl.bilico.data.settings.FeedCoverBlurStyle

@Composable
internal actual fun rememberFeedCoverBackground(
    originalUrl: String,
    coverUrl: String,
    coverReady: Boolean,
    coverImage: Image?,
    cardSize: IntSize,
    coverHeight: Int,
    generateBackground: Boolean,
    blurStyle: FeedCoverBlurStyle,
    appDarkTheme: Boolean,
): FeedCoverBackdrop? = null
