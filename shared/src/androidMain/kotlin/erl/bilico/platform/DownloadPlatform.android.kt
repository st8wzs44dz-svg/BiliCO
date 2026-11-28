package erl.bilico.platform

import android.net.Uri
import erl.bilico.data.repository.DownloadHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

actual class DownloadLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}

private val downloadHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dns(AndroidBiliDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

actual fun downloadsRootPath(context: PlatformContext): String {
    val androidCtx = context.androidContext
    val base = androidCtx.getExternalFilesDir(null) ?: androidCtx.filesDir
    val dir = File(base, "downloads")
    if (!dir.exists()) dir.mkdirs()
    val nomedia = File(dir, ".nomedia")
    if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
    return dir.absolutePath
}

actual fun dlEnsureDir(path: String) {
    val dir = File(path)
    if (!dir.exists()) dir.mkdirs()
}

actual fun dlFileExists(path: String): Boolean = File(path).exists()

actual fun dlFileLength(path: String): Long = File(path).takeIf { it.exists() }?.length() ?: 0L

actual fun dlReadText(path: String): String? {
    val file = File(path)
    if (!file.exists()) return null
    return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
}

actual fun dlReadBytes(path: String): ByteArray? {
    val file = File(path)
    if (!file.exists()) return null
    return runCatching { file.readBytes() }.getOrNull()
}

actual fun dlWriteTextAtomic(path: String, content: String): Boolean {
    val target = File(path)
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    return runCatching {
        tmp.writeText(content, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        } else if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
        true
    }.getOrDefault(false)
}

actual fun dlWriteBytes(path: String, bytes: ByteArray): Boolean {
    val target = File(path)
    target.parentFile?.mkdirs()
    return runCatching {
        target.writeBytes(bytes)
        true
    }.getOrDefault(false)
}

actual fun dlMoveOrReplace(srcPath: String, dstPath: String): Boolean {
    val src = File(srcPath)
    val dst = File(dstPath)
    if (!src.exists()) return false
    dst.parentFile?.mkdirs()
    if (dst.exists()) dst.delete()
    if (src.renameTo(dst)) return true
    return runCatching {
        src.copyTo(dst, overwrite = true)
        src.delete()
        true
    }.getOrDefault(false)
}

actual fun dlDeleteFile(path: String) {
    runCatching { File(path).delete() }
}

actual fun dlDeleteDirectory(path: String) {
    runCatching { File(path).deleteRecursively() }
}

actual fun dlDirectoryDiskUsage(path: String, excludeNames: Set<String>): Long {
    val root = File(path)
    if (!root.exists()) return 0L
    var total = 0L
    fun walk(file: File) {
        if (file.name in excludeNames) return
        if (file.isDirectory) {
            file.listFiles()?.forEach(::walk)
        } else {
            total += file.length()
        }
    }
    walk(root)
    return total
}

actual fun dlPathToFileUri(path: String): String = Uri.fromFile(File(path)).toString()

actual fun dlFileUriToPath(uri: String): String {
    if (!uri.startsWith("file://")) return uri
    return runCatching { Uri.parse(uri).path ?: uri.removePrefix("file://") }.getOrElse { uri.removePrefix("file://") }
}

private fun parseContentRangeTotalAndroid(header: String?): Long? {
    if (header.isNullOrBlank()) return null
    val slash = header.lastIndexOf('/')
    if (slash < 0 || slash == header.lastIndex) return null
    val tail = header.substring(slash + 1).trim()
    if (tail == "*") return null
    return tail.toLongOrNull()
}

actual suspend fun dlProbeContentLength(
    url: String,
    headers: Map<String, String>,
): Long = withContext(ioDispatcher) {
    val req = Request.Builder()
        .url(url)
        .apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("Range", "bytes=0-0")
        }
        .build()
    val call = downloadHttpClient.newCall(req)
    val ctx = currentCoroutineContext()
    val cancelHandle = ctx.job.invokeOnCompletion { cause ->
        if (cause != null) call.cancel()
    }
    try {
        ctx.ensureActive()
        call.execute().use { resp ->
            if (!resp.isSuccessful) return@use -1L
            val contentRangeTotal = parseContentRangeTotalAndroid(resp.header("Content-Range"))
            if (contentRangeTotal != null && contentRangeTotal > 0L) return@use contentRangeTotal
            resp.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: -1L
        }
    } catch (e: CancellationException) {
        call.cancel()
        throw e
    } catch (e: IOException) {
        if (!ctx.isActive || call.isCanceled()) {
            ctx.ensureActive()
            throw CancellationException("探测内容长度已取消", e)
        }
        -1L
    } catch (_: Throwable) {
        -1L
    } finally {
        cancelHandle.dispose()
    }
}

actual suspend fun dlDownloadFileRange(
    url: String,
    headers: Map<String, String>,
    partPath: String,
    finalPath: String,
    onProgress: (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit,
): Long = withContext(ioDispatcher) {
    val partFile = File(partPath)
    val finalFile = File(finalPath)
    partFile.parentFile?.mkdirs()
    finalFile.parentFile?.mkdirs()

    if (finalFile.exists() && finalFile.length() > 0L) {
        val len = finalFile.length()
        onProgress(len, len, 0L)
        return@withContext len
    }

    var existing = partFile.takeIf { it.exists() }?.length() ?: 0L
    val req = Request.Builder()
        .url(url)
        .apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (existing > 0L) header("Range", "bytes=$existing-")
        }
        .build()

    val call = downloadHttpClient.newCall(req)
    val ctx = currentCoroutineContext()
    val cancelHandle = ctx.job.invokeOnCompletion { cause ->
        if (cause != null) call.cancel()
    }
    try {
        ctx.ensureActive()
        call.execute().use { resp ->
            ctx.ensureActive()
            if (resp.code == 416) {
                val total = parseContentRangeTotalAndroid(resp.header("Content-Range"))
                if (total != null && total > 0L && existing >= total) {
                    check(dlMoveOrReplace(partPath, finalPath)) { "无法保存缓存文件" }
                    onProgress(total, total, 0L)
                    return@withContext total
                } else {
                    dlDeleteFile(partPath)
                    throw IllegalStateException("断点范围失效，重试")
                }
            }
            if (resp.code !in 200..299) {
                throw DownloadHttpException(resp.code, "HTTP ${resp.code}")
            }
            val append = resp.code == 206 && existing > 0L
            if (!append) existing = 0L

            val contentRangeTotal = parseContentRangeTotalAndroid(resp.header("Content-Range"))
                ?.takeIf { it > 0L }
            val bodyLen = resp.body.contentLength().takeIf { it > 0L } ?: -1L
            val totalBytes = contentRangeTotal ?: if (bodyLen > 0L) existing + bodyLen else -1L

            var downloaded = existing
            var windowBytes = 0L
            var windowStartNs = System.nanoTime()
            var speed = 0L
            val buffer = ByteArray(64 * 1024)

            resp.body.byteStream().use { input ->
                FileOutputStream(partFile, append).use { output ->
                    while (true) {
                        ctx.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        downloaded += read
                        windowBytes += read
                        val nowNs = System.nanoTime()
                        val elapsedNs = nowNs - windowStartNs
                        if (elapsedNs >= 400_000_000L) {
                            speed = (windowBytes * 1_000_000_000L) / elapsedNs
                            windowBytes = 0L
                            windowStartNs = nowNs
                            onProgress(downloaded, totalBytes, speed)
                        }
                    }
                    output.flush()
                }
            }

            if (totalBytes > 0L && downloaded < totalBytes) {
                error("下载不完整 ($downloaded/$totalBytes)")
            }
            check(dlMoveOrReplace(partPath, finalPath)) { "无法保存缓存文件" }
            onProgress(downloaded, if (totalBytes > 0L) totalBytes else downloaded, speed)
            downloaded
        }
    } catch (e: CancellationException) {
        call.cancel()
        throw e
    } catch (e: IOException) {
        if (!ctx.isActive || call.isCanceled()) {
            ctx.ensureActive()
            throw CancellationException("下载已取消", e)
        }
        throw e
    } finally {
        cancelHandle.dispose()
    }
}

actual suspend fun dlExportMediaToDownloads(
    context: PlatformContext,
    videoPath: String,
    audioPath: String?,
    baseFileName: String,
    audioOnly: Boolean,
    onProgress: (Int) -> Unit,
): String = withContext(ioDispatcher) {
    val androidContext = context.androidContext
    val videoFile = File(videoPath)
    val audioFile = audioPath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
    if (!audioOnly && (!videoFile.exists() || videoFile.length() <= 0L)) {
        error("缓存视频文件不存在")
    }
    if (audioOnly && audioFile == null && (!videoFile.exists() || videoFile.length() <= 0L)) {
        error("缓存音频文件不存在")
    }

    val safeBase = baseFileName.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().take(80)
        .ifBlank { "BiliCO_${System.currentTimeMillis()}" }
    val ext = if (audioOnly) "m4a" else "mp4"
    val mimeType = if (audioOnly) "audio/mp4" else "video/mp4"
    val fileName = "$safeBase.$ext"

    val tmpDir = File(androidContext.cacheDir, "export_tmp").apply { mkdirs() }
    val tmpOut = File(tmpDir, "export_${System.currentTimeMillis()}.$ext")
    try {
        if (audioOnly) {
            if (audioFile != null) {
                audioFile.copyTo(tmpOut, overwrite = true)
                onProgress(90)
            } else {
                muxTracksToFile(
                    videoFile = null,
                    audioFile = videoFile,
                    outputFile = tmpOut,
                    onProgress = onProgress,
                )
            }
        } else if (audioFile == null && videoFile.name.endsWith(".mp4", ignoreCase = true)) {
            videoFile.copyTo(tmpOut, overwrite = true)
            onProgress(90)
        } else {
            muxTracksToFile(
                videoFile = videoFile,
                audioFile = audioFile,
                outputFile = tmpOut,
                onProgress = onProgress,
            )
        }
        saveExportToPublicDownloads(androidContext, tmpOut, fileName, mimeType)
        onProgress(100)
        fileName
    } finally {
        runCatching { tmpOut.delete() }
    }
}

private fun muxTracksToFile(
    videoFile: File?,
    audioFile: File?,
    outputFile: File,
    onProgress: (Int) -> Unit,
) {
    val videoExtractor = videoFile?.let { android.media.MediaExtractor().apply { setDataSource(it.absolutePath) } }
    val audioExtractor = audioFile?.let { android.media.MediaExtractor().apply { setDataSource(it.absolutePath) } }
    val muxer = android.media.MediaMuxer(outputFile.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var muxerStarted = false
    try {
        var videoTrackIndex = -1
        if (videoExtractor != null) {
            val srcVideoTrack = findMediaTrack(videoExtractor, "video/")
            videoExtractor.selectTrack(srcVideoTrack)
            videoTrackIndex = muxer.addTrack(videoExtractor.getTrackFormat(srcVideoTrack))
        }
        var audioTrackIndex = -1
        if (audioExtractor != null) {
            val srcAudioTrack = findMediaTrackOrNull(audioExtractor, "audio/")
            if (srcAudioTrack >= 0) {
                audioExtractor.selectTrack(srcAudioTrack)
                audioTrackIndex = muxer.addTrack(audioExtractor.getTrackFormat(srcAudioTrack))
            }
        }
        if (videoTrackIndex < 0 && audioTrackIndex < 0) error("未找到可导出的音视频轨道")
        muxer.start()
        muxerStarted = true

        val totalBytes = ((videoFile?.length() ?: 0L) + (audioFile?.length() ?: 0L)).coerceAtLeast(1L)
        var writtenBytes = 0L
        var lastPct = 0
        val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()

        fun copyTrack(extractor: android.media.MediaExtractor, targetTrack: Int) {
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(targetTrack, buffer, info)
                writtenBytes += sampleSize
                val pct = ((writtenBytes * 95L) / totalBytes).toInt().coerceIn(1, 95)
                if (pct > lastPct) {
                    lastPct = pct
                    onProgress(pct)
                }
                extractor.advance()
            }
        }

        if (videoExtractor != null && videoTrackIndex >= 0) {
            copyTrack(videoExtractor, videoTrackIndex)
        }
        if (audioExtractor != null && audioTrackIndex >= 0) {
            copyTrack(audioExtractor, audioTrackIndex)
        }
    } finally {
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        runCatching { videoExtractor?.release() }
        runCatching { audioExtractor?.release() }
    }
}

private fun findMediaTrack(extractor: android.media.MediaExtractor, mimePrefix: String): Int =
    findMediaTrackOrNull(extractor, mimePrefix).takeIf { it >= 0 }
        ?: error("未找到${if (mimePrefix == "video/") "视频" else "音频"}轨道")

private fun findMediaTrackOrNull(extractor: android.media.MediaExtractor, mimePrefix: String): Int {
    for (i in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith(mimePrefix)) return i
    }
    return -1
}

private fun saveExportToPublicDownloads(
    appContext: android.content.Context,
    source: File,
    fileName: String,
    mimeType: String,
) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val resolver = appContext.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/BiliCO")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建下载文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                java.io.FileInputStream(source).use { input -> input.copyTo(out) }
            } ?: error("无法写入下载文件")
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    } else {
        @Suppress("DEPRECATION")
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "BiliCO",
        ).apply { mkdirs() }
        val dest = File(dir, fileName)
        source.copyTo(dest, overwrite = true)
    }
}
