package erl.bilico.feature.pgc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import erl.bilico.data.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.cbrt
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val PosterBlurScale = 0.25f
private const val PosterBlurCacheBytes = 3 * 1024 * 1024

private data class PosterBlurKey(
    val coverUrl: String,
    val width: Int,
    val posterHeight: Int,
    val blurHeight: Int,
    val fadeHeight: Int,
    val blurRadius: Int,
)

private val posterBlurCache = object : LruCache<PosterBlurKey, ImageBitmap>(PosterBlurCacheBytes) {
    override fun sizeOf(key: PosterBlurKey, value: ImageBitmap): Int = value.width * value.height * 4
}

private val posterBlurWorker = Semaphore(1)

@Composable
internal actual fun rememberBangumiPosterBlur(
    originalUrl: String,
    coverUrl: String,
    coverReady: Boolean,
    cardSize: IntSize,
    infoHeight: Int,
    generateBlur: Boolean,
    pauseBlur: () -> Boolean,
): ImageBitmap? {
    val context = LocalContext.current
    val density = LocalDensity.current
    val latestPauseBlur by rememberUpdatedState(pauseBlur)
    val key = remember(coverUrl, cardSize, infoHeight, density) {
        if (coverUrl.isBlank() || cardSize.width <= 0 || cardSize.height <= 0 || infoHeight <= 0) {
            null
        } else {
            val fadePx = with(density) { BangumiPosterBlurFade.roundToPx() }.coerceAtLeast(1)
            val blurPx = (infoHeight + fadePx).coerceAtMost(cardSize.height)
            val sigma = with(density) { 16.dp.toPx() } * PosterBlurScale
            val radius = ((sqrt(4 * sigma * sigma + 1) - 1) / 2).roundToInt().coerceAtLeast(1)
            val scaledPosterHeight = scaledPosterPixels(cardSize.height)
            val scaledBlurHeight = scaledPosterPixels(blurPx).coerceAtMost(scaledPosterHeight)
            val scaledFadeHeight = scaledPosterPixels(fadePx).coerceAtMost(scaledBlurHeight)
            PosterBlurKey(
                coverUrl = coverUrl,
                width = scaledPosterPixels(cardSize.width),
                posterHeight = scaledPosterHeight,
                blurHeight = scaledBlurHeight,
                fadeHeight = scaledFadeHeight,
                blurRadius = radius,
            )
        }
    }
    var blur by remember(key) { mutableStateOf(key?.let { posterBlurCache.get(it) }) }
    LaunchedEffect(key, coverReady, generateBlur) {
        if (key == null || !coverReady || !generateBlur || blur != null) return@LaunchedEffect
        if (latestPauseBlur()) {
            snapshotFlow { latestPauseBlur() }.first { !it }
        }
        val thumbnailWidth = with(density) { 48.dp.roundToPx() }.coerceAtLeast(1)
        val thumbnailUrl = Formatters.sizedImageUrl(
            originalUrl,
            thumbnailWidth,
            (thumbnailWidth * 3 / 2).coerceAtLeast(1),
        )
        blur = posterBlurWorker.withPermit {
            posterBlurCache.get(key) ?: loadPosterBlur(context, key, thumbnailUrl)?.also {
                posterBlurCache.put(key, it)
            }
        }
    }
    return blur.takeIf { coverReady }
}

private fun scaledPosterPixels(pixels: Int): Int = (pixels * PosterBlurScale).roundToInt().coerceAtLeast(1)

private suspend fun loadPosterBlur(
    context: Context,
    key: PosterBlurKey,
    thumbnailUrl: String,
): ImageBitmap? {
    val loader = SingletonImageLoader.get(context)
    val request = ImageRequest.Builder(context)
        .size(key.width, key.posterHeight)
        .scale(Scale.FILL)
        .allowHardware(false)
        .memoryCachePolicy(CachePolicy.DISABLED)

    val cached = loader.execute(
        request.data(key.coverUrl).networkCachePolicy(CachePolicy.DISABLED).build(),
    ) as? SuccessResult
    val result = cached ?: (loader.execute(
        request.data(thumbnailUrl).networkCachePolicy(CachePolicy.ENABLED).build(),
    ) as? SuccessResult) ?: return null

    return withContext(Dispatchers.Default) {
        renderPosterBlur(result.image.toBitmap(), key)
    }
}

/**
 * 仅截取海报底部 `blurHeight` 区域（加顶部 `padTop` 行真实海报像素参与 3 轮 Box Blur，避免断层），
 * 再叠加 C² 连续的 `smootherStep` 渐变透明遮罩、平滑深色 scrim 与三次方 soft-knee 亮度压缩。
 */
private fun renderPosterBlur(source: Bitmap, key: PosterBlurKey): ImageBitmap {
    val width = key.width
    val posterHeight = key.posterHeight
    val poster = Bitmap.createBitmap(width, posterHeight, Bitmap.Config.ARGB_8888)
    val scale = maxOf(width.toFloat() / source.width, posterHeight.toFloat() / source.height)
    val left = (width - source.width * scale) / 2f
    val top = (posterHeight - source.height * scale) / 2f
    Canvas(poster).apply {
        drawColor(android.graphics.Color.BLACK)
        drawBitmap(
            source,
            null,
            RectF(left, top, width - left, posterHeight - top),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
    }
    val posterPixels = IntArray(width * posterHeight)
    poster.getPixels(posterPixels, 0, width, 0, 0, width, posterHeight)
    poster.recycle()

    val blurHeight = key.blurHeight
    val startRow = (posterHeight - blurHeight).coerceAtLeast(0)
    val padTop = startRow.coerceAtMost(key.blurRadius * 2)
    val paddedHeight = padTop + blurHeight
    val blurred = IntArray(width * paddedHeight)
    val copyStartRow = startRow - padTop
    posterPixels.copyInto(
        destination = blurred,
        destinationOffset = 0,
        startIndex = copyStartRow * width,
        endIndex = (copyStartRow + paddedHeight) * width,
    )

    val transposed = IntArray(blurred.size)
    repeat(3) {
        blurPosterRows(blurred, transposed, width, paddedHeight, key.blurRadius)
        blurPosterRows(transposed, blurred, paddedHeight, width, key.blurRadius)
    }
    val pixels = if (padTop == 0) blurred else blurred.copyOfRange(padTop * width, paddedHeight * width)

    val fadeSpan = (key.fadeHeight - 2).coerceAtLeast(1)
    val maxRow = (blurHeight - 1).coerceAtLeast(1)
    for (y in 0 until blurHeight) {
        val progress = y.toFloat() / maxRow
        val blend = posterSmootherStep(y.toFloat() / fadeSpan)
        val alpha = (255f * blend).roundToInt()
        val smoothProgress = posterSmootherStep(progress)
        val scrim = 1f - 0.34f * smoothProgress
        val softCap = 104f - 28f * smoothProgress
        val compressWeight = 0.35f + 0.65f * posterSmootherStep(progress / 0.4f)
        for (x in 0 until width) {
            val index = y * width + x
            val color = pixels[index]
            val r = color shr 16 and 255
            val g = color shr 8 and 255
            val b = color and 255
            val scrimmedLuma = (0.2126f * r + 0.7152f * g + 0.0722f * b) * scrim
            val ratio = scrimmedLuma / softCap
            val softGain = 1f / cbrt(1f + ratio * ratio * ratio)
            val multiplier = scrim * (1f - compressWeight * (1f - softGain))
            pixels[index] = (alpha shl 24) or ((r * multiplier).roundToInt() shl 16) or
                ((g * multiplier).roundToInt() shl 8) or (b * multiplier).roundToInt()
        }
    }
    return Bitmap.createBitmap(pixels, width, blurHeight, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun posterSmootherStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

private fun posterMirrorIndex(index: Int, length: Int): Int {
    val period = 2 * length
    val wrapped = ((index % period) + period) % period
    return if (wrapped < length) wrapped else period - 1 - wrapped
}

private fun blurPosterRows(source: IntArray, destination: IntArray, width: Int, height: Int, radius: Int) {
    val windowSize = 2 * radius + 1
    val initial = IntArray(windowSize) { posterMirrorIndex(it - radius, width) }
    val entering = IntArray(width) { posterMirrorIndex(it + radius + 1, width) }
    val leaving = IntArray(width) { posterMirrorIndex(it - radius, width) }
    for (y in 0 until height) {
        val start = y * width
        var r = 0
        var g = 0
        var b = 0
        for (x in initial) {
            val color = source[start + x]
            r += color shr 16 and 255
            g += color shr 8 and 255
            b += color and 255
        }
        for (x in 0 until width) {
            destination[x * height + y] = ((r / windowSize) shl 16) or ((g / windowSize) shl 8) or (b / windowSize)
            val add = source[start + entering[x]]
            val remove = source[start + leaving[x]]
            r += (add shr 16 and 255) - (remove shr 16 and 255)
            g += (add shr 8 and 255) - (remove shr 8 and 255)
            b += (add and 255) - (remove and 255)
        }
    }
}
