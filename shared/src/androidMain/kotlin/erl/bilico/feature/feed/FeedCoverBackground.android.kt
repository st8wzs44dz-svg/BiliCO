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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.Image
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import erl.bilico.data.settings.FeedCoverBlurStyle
import erl.bilico.data.util.Formatters
import erl.bilico.platform.LocalPlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

private const val BackgroundScale = 0.25f
private const val BackgroundCacheBytes = 3 * 1024 * 1024

private data class BackgroundKey(
    val coverUrl: String,
    val width: Int,
    val coverHeight: Int,
    val infoHeight: Int,
    val overlapHeight: Int,
    val blurRadius: Int,
    val blurStyle: FeedCoverBlurStyle,
    val appDarkTheme: Boolean,
)

private val backgroundCache = object : LruCache<BackgroundKey, FeedCoverBackdrop>(BackgroundCacheBytes) {
    override fun sizeOf(key: BackgroundKey, value: FeedCoverBackdrop): Int =
        value.bitmap.width * value.bitmap.height * 4
}

// Allow bounded parallel blur work across visible cards without stalling on a single slot.
private val backgroundWorker = Semaphore(3)

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
): FeedCoverBackdrop? {
    val context = LocalPlatformContext.current.androidContext
    val coverBitmap = (coverImage as? BitmapImage)?.bitmap
    val density = LocalDensity.current
    val key = remember(coverUrl, cardSize, coverHeight, density, blurStyle, appDarkTheme) {
        val infoHeight = cardSize.height - coverHeight
        if (coverUrl.isBlank() || cardSize.width <= 0 || coverHeight <= 0 || infoHeight <= 0) {
            null
        } else {
            val overlap = with(density) { FeedCoverOverlap.roundToPx() }.coerceAtMost(coverHeight)
            val sigma = with(density) { 15.dp.toPx() } * BackgroundScale
            val radius = ((sqrt(4 * sigma * sigma + 1) - 1) / 2).roundToInt().coerceAtLeast(1)
            BackgroundKey(
                coverUrl = coverUrl,
                width = scaledPixels(cardSize.width),
                coverHeight = scaledPixels(coverHeight),
                infoHeight = scaledPixels(infoHeight),
                overlapHeight = scaledPixels(overlap),
                blurRadius = radius,
                blurStyle = blurStyle,
                appDarkTheme = appDarkTheme,
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
): FeedCoverBackdrop? {
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

private data class ToneStats(
    val avgLuma: Float,
    val brightFraction: Float,
    val domHue: Float,
    val domSat: Float,
    val avgSat: Float,
)

private fun renderBackground(source: Bitmap, key: BackgroundKey): FeedCoverBackdrop {
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

    // Three separable box passes approximate sigma 15dp at the card's display scale.
    val transposed = IntArray(blurred.size)
    repeat(3) {
        blurRows(blurred, transposed, width, paddedHeight, key.blurRadius)
        blurRows(transposed, blurred, paddedHeight, width, key.blurRadius)
    }
    val pixels = if (padTop == 0) blurred else blurred.copyOfRange(padTop * width, paddedHeight * width)

    val tone = analyzeInfoTone(pixels, width, overlap, height)
    val useLightSurface = key.blurStyle == FeedCoverBlurStyle.ADAPTIVE_TONE &&
        !key.appDarkTheme &&
        (tone.avgLuma >= 152f || (tone.avgLuma >= 138f && tone.brightFraction >= 0.45f))

    val fadeHeight = (overlap * 0.64f).roundToInt().coerceIn(1, (overlap - 2).coerceAtLeast(1))
    val maxRow = (height - 1).coerceAtLeast(1)

    if (useLightSurface) {
        applyLightPastelTone(pixels, width, height, fadeHeight, maxRow)
        val inkColor = if (tone.avgSat >= 0.08f) {
            val (ir, ig, ib) = hsvToRgb(
                hue = tone.domHue,
                sat = (tone.domSat * 0.52f).coerceIn(0.10f, 0.40f),
                value = 0.14f,
            )
            Color(ir / 255f, ig / 255f, ib / 255f)
        } else {
            Color(0xFF16181C)
        }
        return FeedCoverBackdrop(
            bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap(),
            isLightSurface = true,
            titleColor = inkColor,
            secondaryTextColor = inkColor.copy(alpha = 0.68f),
            statsColor = inkColor.copy(alpha = 0.88f),
        )
    }

    applyVibrantDarkTone(pixels, width, height, fadeHeight, maxRow, tone)
    return FeedCoverBackdrop(
        bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap(),
        isLightSurface = false,
        titleColor = Color.White,
        secondaryTextColor = Color.White.copy(alpha = 0.80f),
        statsColor = Color.White,
    )
}

private fun analyzeInfoTone(pixels: IntArray, width: Int, overlap: Int, height: Int): ToneStats {
    val startY = overlap.coerceIn(0, (height - 1).coerceAtLeast(0))
    var count = 0
    var lumaSum = 0f
    var brightCount = 0
    var satSum = 0f
    var domWeight = 0f
    var domR = 0f
    var domG = 0f
    var domB = 0f
    for (y in startY until height) {
        val row = y * width
        for (x in 0 until width) {
            val c = pixels[row + x]
            val r = (c shr 16 and 255).toFloat()
            val g = (c shr 8 and 255).toFloat()
            val b = (c and 255).toFloat()
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val maxC = maxOf(r, maxOf(g, b))
            val minC = minOf(r, minOf(g, b))
            val sat = if (maxC > 1f) (maxC - minC) / maxC else 0f
            lumaSum += luma
            if (luma >= 158f) brightCount++
            satSum += sat
            val w = sat * sat + 0.01f
            domWeight += w
            domR += r * w
            domG += g * w
            domB += b * w
            count++
        }
    }
    val safeCount = count.coerceAtLeast(1)
    val safeWeight = domWeight.coerceAtLeast(0.01f)
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (domR / safeWeight).roundToInt().coerceIn(0, 255),
        (domG / safeWeight).roundToInt().coerceIn(0, 255),
        (domB / safeWeight).roundToInt().coerceIn(0, 255),
        hsv,
    )
    return ToneStats(
        avgLuma = lumaSum / safeCount,
        brightFraction = brightCount.toFloat() / safeCount,
        domHue = hsv[0],
        domSat = hsv[1],
        avgSat = satSum / safeCount,
    )
}

/** 方案一：高彩度深色玻璃——保留暗区鲜活色相、按膝点软压高光，并用同色相深宝石色替代纯黑压暗。 */
private fun applyVibrantDarkTone(
    pixels: IntArray,
    width: Int,
    height: Int,
    fadeHeight: Int,
    maxRow: Int,
    tone: ToneStats,
) {
    val hasChroma = tone.avgSat >= 0.08f
    val (anchorR, anchorG, anchorB) = if (hasChroma) {
        val shiftedHue = warmShiftYellowHue(tone.domHue)
        hsvToRgb(
            hue = shiftedHue,
            sat = (tone.domSat * 1.18f).coerceIn(0.40f, 0.86f),
            value = 0.30f,
        )
    } else {
        Triple(32f, 34f, 38f)
    }

    for (y in 0 until height) {
        val progress = y.toFloat() / maxRow
        val blend = smootherStep(y.toFloat() / fadeHeight)
        val alpha = (255f * blend).roundToInt()
        val smoothProgress = smootherStep(progress)
        // WCAG AA 4.5:1 对白字允许 sRGB ~115；信息区收束在 104 以内，兼顾副文字 80% 白的清晰度。
        val cap = 114f - 10f * smoothProgress
        val knee = cap * 0.70f
        val headRoom = (cap - knee).coerceAtLeast(1f)
        for (x in 0 until width) {
            val index = y * width + x
            val color = pixels[index]
            var r = (color shr 16 and 255).toFloat()
            var g = (color shr 8 and 255).toFloat()
            var b = (color and 255).toFloat()

            // 明黄区域压暗时容易发绿发脏，轻微向琥珀暖色偏移。
            if (r > 130f && g > 115f && b < minOf(r, g) * 0.72f) {
                val yellowWeight = ((minOf(r, g) - b) / 255f).coerceIn(0f, 1f) * smoothProgress
                g -= yellowWeight * 16f
            }

            val luma0 = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val maxC = maxOf(r, maxOf(g, b))
            val minC = minOf(r, minOf(g, b))
            val sat = if (maxC > 1f) (maxC - minC) / maxC else 0f

            // 智能彩度提纯（Vibrance）：仅拉升有色像素，纯灰白不强行着色。
            val chromaGate = (sat / 0.12f).coerceIn(0f, 1f)
            val boost = 1f + (0.50f * smoothProgress) * (1f - 0.42f * sat) * chromaGate
            r = (luma0 + (r - luma0) * boost).coerceIn(0f, 255f)
            g = (luma0 + (g - luma0) * boost).coerceIn(0f, 255f)
            b = (luma0 + (b - luma0) * boost).coerceIn(0f, 255f)

            val luma1 = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val scale = if (luma1 > knee) {
                val newLuma = knee + headRoom * tanh(((luma1 - knee) / headRoom).toDouble()).toFloat()
                newLuma / luma1
            } else {
                1f
            }
            r *= scale
            g *= scale
            b *= scale

            // 对被大幅压暗的高亮彩色像素，向同色相深宝石锚点轻微融合，保住色相层次。
            if (hasChroma && scale < 0.90f) {
                val mix = ((1f - scale) * 0.26f * smoothProgress).coerceIn(0f, 0.22f)
                r += (anchorR - r) * mix
                g += (anchorG - g) * mix
                b += (anchorB - b) * mix
            }

            pixels[index] = (alpha shl 24) or
                (r.roundToInt().coerceIn(0, 255) shl 16) or
                (g.roundToInt().coerceIn(0, 255) shl 8) or
                b.roundToInt().coerceIn(0, 255)
        }
    }
}

/** 方案二亮色分流：粉彩毛玻璃亮底，温和抬升局部暗斑以保证深墨色文字高对比清晰可读。 */
private fun applyLightPastelTone(
    pixels: IntArray,
    width: Int,
    height: Int,
    fadeHeight: Int,
    maxRow: Int,
) {
    for (y in 0 until height) {
        val progress = y.toFloat() / maxRow
        val blend = smootherStep(y.toFloat() / fadeHeight)
        val alpha = (255f * blend).roundToInt()
        val smoothProgress = smootherStep(progress)
        val minLuma = 202f + 14f * smoothProgress
        for (x in 0 until width) {
            val index = y * width + x
            val color = pixels[index]
            var r = (color shr 16 and 255).toFloat()
            var g = (color shr 8 and 255).toFloat()
            var b = (color and 255).toFloat()

            val luma0 = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val maxC = maxOf(r, maxOf(g, b))
            val minC = minOf(r, minOf(g, b))
            val sat = if (maxC > 1f) (maxC - minC) / maxC else 0f
            val chromaGate = (sat / 0.10f).coerceIn(0f, 1f)
            val boost = 1f + (0.24f * smoothProgress) * (1f - 0.35f * sat) * chromaGate
            r = (luma0 + (r - luma0) * boost).coerceIn(0f, 255f)
            g = (luma0 + (g - luma0) * boost).coerceIn(0f, 255f)
            b = (luma0 + (b - luma0) * boost).coerceIn(0f, 255f)

            val luma1 = 0.2126f * r + 0.7152f * g + 0.0722f * b
            if (luma1 < minLuma) {
                val lift = ((minLuma - luma1) / (248f - luma1).coerceAtLeast(1f)).coerceIn(0f, 0.92f)
                r += (247f - r) * lift
                g += (247f - g) * lift
                b += (249f - b) * lift
            }

            pixels[index] = (alpha shl 24) or
                (r.roundToInt().coerceIn(0, 255) shl 16) or
                (g.roundToInt().coerceIn(0, 255) shl 8) or
                b.roundToInt().coerceIn(0, 255)
        }
    }
}

private fun warmShiftYellowHue(hue: Float): Float {
    if (hue !in 42f..74f) return hue
    val weight = (1f - abs(hue - 56f) / 18f).coerceIn(0f, 1f)
    return (hue - 10f * weight).coerceAtLeast(32f)
}

private fun hsvToRgb(hue: Float, sat: Float, value: Float): Triple<Float, Float, Float> {
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))
    return Triple(
        (argb shr 16 and 255).toFloat(),
        (argb shr 8 and 255).toFloat(),
        (argb and 255).toFloat(),
    )
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
