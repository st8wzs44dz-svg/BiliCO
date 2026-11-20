package erl.bilico.feature.feed

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
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.cbrt
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val BackgroundScale = 0.25f
private const val BackgroundCacheBytes = 3 * 1024 * 1024

private data class BackgroundKey(
    val coverUrl: String,
    val width: Int,
    val coverHeight: Int,
    val infoHeight: Int,
    val overlapHeight: Int,
    val blurRadius: Int,
)

private val backgroundCache = object : LruCache<BackgroundKey, ImageBitmap>(BackgroundCacheBytes) {
    override fun sizeOf(key: BackgroundKey, value: ImageBitmap): Int = value.width * value.height * 4
}

// Allow bounded parallel blur work across visible cards without stalling on a single slot.
private val backgroundWorker = Semaphore(3)

internal val FeedCoverOverlap = 44.dp

@Composable
internal fun rememberFeedCoverBackground(
    originalUrl: String,
    coverUrl: String,
    coverReady: Boolean,
    coverBitmap: Bitmap?,
    cardSize: IntSize,
    coverHeight: Int,
    generateBackground: Boolean,
): ImageBitmap? {
    val context = LocalContext.current
    val density = LocalDensity.current
    val key = remember(coverUrl, cardSize, coverHeight, density) {
        val infoHeight = cardSize.height - coverHeight
        if (coverUrl.isBlank() || cardSize.width <= 0 || coverHeight <= 0 || infoHeight <= 0) {
            null
        } else {
            val overlap = with(density) { FeedCoverOverlap.roundToPx() }.coerceAtMost(coverHeight)
            val sigma = with(density) { 20.dp.toPx() } * BackgroundScale
            val radius = ((sqrt(4 * sigma * sigma + 1) - 1) / 2).roundToInt().coerceAtLeast(1)
            BackgroundKey(
                coverUrl = coverUrl,
                width = scaledPixels(cardSize.width),
                coverHeight = scaledPixels(coverHeight),
                infoHeight = scaledPixels(infoHeight),
                overlapHeight = scaledPixels(overlap),
                blurRadius = radius,
            )
        }
    }
    var background by remember(key) { mutableStateOf(key?.let { backgroundCache.get(it) }) }
    LaunchedEffect(key, coverReady, coverBitmap, generateBackground) {
        if (key == null || !coverReady || !generateBackground || background != null) return@LaunchedEffect
        val thumbnailWidth = with(density) { 48.dp.roundToPx() }.coerceAtLeast(1)
        val thumbnailHeight = (thumbnailWidth.toFloat() * key.coverHeight / key.width.coerceAtLeast(1))
            .roundToInt()
            .coerceAtLeast(1)
        val thumbnailUrl = Formatters.sizedImageUrl(originalUrl, thumbnailWidth, thumbnailHeight)
        background = backgroundCache.get(key) ?: loadBackground(context, key, coverBitmap, thumbnailUrl)?.also {
            backgroundCache.put(key, it)
        }
    }
    return background.takeIf { coverReady }
}

private fun scaledPixels(pixels: Int): Int = (pixels * BackgroundScale).roundToInt().coerceAtLeast(1)

private suspend fun loadBackground(
    context: Context,
    key: BackgroundKey,
    coverBitmap: Bitmap?,
    thumbnailUrl: String,
): ImageBitmap? {
    // 优先直接复用 AsyncImage 已解码好的内存位图，避免二次读盘或走网络拖慢整屏卡片。
    if (coverBitmap != null && !coverBitmap.isRecycled) {
        val rendered = backgroundWorker.withPermit {
            withContext(Dispatchers.Default) {
                runCatching {
                    val isHardware = coverBitmap.config == Bitmap.Config.HARDWARE
                    val source = if (isHardware) {
                        coverBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        coverBitmap
                    } ?: return@runCatching null
                    try {
                        renderBackground(source, key)
                    } finally {
                        if (isHardware) source.recycle()
                    }
                }.getOrNull()
            }
        }
        if (rendered != null) return rendered
    }

    val loader = SingletonImageLoader.get(context)
    val request = ImageRequest.Builder(context)
        .size(key.width, key.coverHeight)
        .scale(Scale.FILL)
        .allowHardware(false)
        .memoryCachePolicy(CachePolicy.DISABLED)

    // 兜底：先读已展示封面的磁盘缓存，再回退到小缩略图（网络请求不占 CPU 模糊信号量）。
    val cachedCover = loader.execute(
        request.data(key.coverUrl).networkCachePolicy(CachePolicy.DISABLED).build(),
    ) as? SuccessResult
    val cover = cachedCover ?: (loader.execute(
        request.data(thumbnailUrl).networkCachePolicy(CachePolicy.ENABLED).build(),
    ) as? SuccessResult) ?: return null

    return backgroundWorker.withPermit {
        withContext(Dispatchers.Default) {
            renderBackground(cover.image.toBitmap(), key)
        }
    }
}

private fun renderBackground(source: Bitmap, key: BackgroundKey): ImageBitmap {
    val width = key.width
    val coverHeight = key.coverHeight
    val cover = Bitmap.createBitmap(width, coverHeight, Bitmap.Config.ARGB_8888)
    val scale = maxOf(width.toFloat() / source.width, coverHeight.toFloat() / source.height)
    val left = (width - source.width * scale) / 2
    val top = (coverHeight - source.height * scale) / 2
    Canvas(cover).apply {
        drawColor(android.graphics.Color.BLACK)
        drawBitmap(source, null, RectF(left, top, width - left, coverHeight - top), Paint(Paint.FILTER_BITMAP_FLAG))
    }
    val coverPixels = IntArray(width * coverHeight)
    cover.getPixels(coverPixels, 0, width, 0, 0, width, coverHeight)
    cover.recycle()

    val overlap = key.overlapHeight
    val height = overlap + key.infoHeight
    val padTop = (coverHeight - overlap).coerceAtLeast(0).coerceAtMost(key.blurRadius * 2)
    val paddedHeight = padTop + height
    val blurred = IntArray(width * paddedHeight)
    for (y in 0 until paddedHeight) {
        val sourceRow = mirrorIndex(coverHeight + y - overlap - padTop, coverHeight)
        coverPixels.copyInto(blurred, y * width, sourceRow * width, (sourceRow + 1) * width)
    }

    // Three separable box passes approximate sigma 20dp at the card's display scale.
    val transposed = IntArray(blurred.size)
    repeat(3) {
        blurRows(blurred, transposed, width, paddedHeight, key.blurRadius)
        blurRows(transposed, blurred, paddedHeight, width, key.blurRadius)
    }
    val pixels = if (padTop == 0) blurred else blurred.copyOfRange(padTop * width, paddedHeight * width)

    // Smoothly feather the upper ~28dp of the overlap so the lower ~16dp (behind cover-bottom stats)
    // already sits on the unified blurred & luminance-compressed backdrop without any separate dark scrim.
    val fadeHeight = (overlap * 0.64f).roundToInt().coerceIn(1, (overlap - 2).coerceAtLeast(1))
    val maxRow = (height - 1).coerceAtLeast(1)
    for (y in 0 until height) {
        val progress = y.toFloat() / maxRow
        val blend = smootherStep(y.toFloat() / fadeHeight)
        val alpha = (255f * blend).roundToInt()
        val smoothProgress = smootherStep(progress)
        val scrim = 1f - 0.22f * smoothProgress
        // White stats/title text and 70%-white metadata need smooth contrast compression on bright covers.
        val softCap = 114f - 26f * smoothProgress
        val compressWeight = 0.40f + 0.60f * smootherStep(progress / 0.35f)
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
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun smootherStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

private fun mirrorIndex(index: Int, length: Int): Int {
    val period = 2 * length
    val wrapped = ((index % period) + period) % period
    return if (wrapped < length) wrapped else period - 1 - wrapped
}

private fun blurRows(source: IntArray, destination: IntArray, width: Int, height: Int, radius: Int) {
    val windowSize = 2 * radius + 1
    val initial = IntArray(windowSize) { mirrorIndex(it - radius, width) }
    val entering = IntArray(width) { mirrorIndex(it + radius + 1, width) }
    val leaving = IntArray(width) { mirrorIndex(it - radius, width) }
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
