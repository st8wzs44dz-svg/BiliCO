package erl.bilico.data.repository

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import erl.bilico.data.model.DownloadKind
import erl.bilico.data.model.DownloadStatus
import erl.bilico.data.model.PlaybackSource
import erl.bilico.data.model.VideoDownloadTask
import erl.bilico.data.network.BiliClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * 应用内离线缓存与原生混流导出仓库：
 * - 流式缓存 DASH 视频轨 / 音频轨到应用私有目录，支持断点清理、失败重试与多线路回退；
 * - 导出时零 FFmpeg 依赖，直接通过 Android 原生 [MediaExtractor] + [MediaMuxer] 将 DASH 音视频无损封装为 MP4
 *   （纯音频直接导出 M4A）并写入系统 Downloads（MediaStore）。
 */
class DownloadRepository(
    context: Context,
    private val client: BiliClient,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, "downloads")
    private val indexFile = File(appContext.filesDir, "downloads_index.json")
    private val mutex = Mutex()
    private val drainMutex = Mutex()
    private var drainJob: Job? = null

    private val _tasks = MutableStateFlow<List<VideoDownloadTask>>(emptyList())
    val tasks: StateFlow<List<VideoDownloadTask>> = _tasks.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            loadIndex()
            ensureDrain()
        }
    }

    /** 将当前正在播放的视频（或纯音频）加入离线缓存队列；同 bvid+cid+kind 已存在时返回 false。 */
    suspend fun enqueue(
        bvid: String,
        cid: Long,
        title: String,
        upName: String,
        coverUrl: String,
        source: PlaybackSource,
        kind: DownloadKind,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val videoUrls = (listOf(source.videoUrl) + source.videoBackupUrls).filter { it.isNotBlank() }.distinct()
            val audioUrls = (listOfNotNull(source.audioUrl) + source.audioBackupUrls).filter { it.isNotBlank() }.distinct()
            when (kind) {
                DownloadKind.VIDEO -> require(videoUrls.isNotEmpty()) { "当前视频暂无可缓存的视频流" }
                DownloadKind.AUDIO_ONLY -> require(audioUrls.isNotEmpty()) { "当前视频没有独立音频轨" }
            }
            val id = "${bvid}_${cid}_${kind.name.lowercase()}"
            val qualityLabel = source.qualities.firstOrNull { it.quality == source.quality }?.label
                ?: if (kind == DownloadKind.AUDIO_ONLY) "纯音频" else "${source.quality}P"

            var added = false
            mutex.withLock {
                val existing = _tasks.value.firstOrNull { it.id == id }
                if (existing != null && existing.status != DownloadStatus.FAILED) {
                    return@withLock
                }
                val task = VideoDownloadTask(
                    id = id,
                    bvid = bvid,
                    cid = cid,
                    title = title.ifBlank { bvid },
                    upName = upName,
                    coverUrl = coverUrl,
                    qualityLabel = if (kind == DownloadKind.AUDIO_ONLY) "纯音频" else qualityLabel,
                    kind = kind,
                    status = DownloadStatus.QUEUED,
                    progress = 0,
                    videoUrls = if (kind == DownloadKind.VIDEO) videoUrls else emptyList(),
                    audioUrls = audioUrls,
                    createdAtMs = System.currentTimeMillis(),
                )
                val next = listOf(task) + _tasks.value.filterNot { it.id == id }
                _tasks.value = next
                persistIndexLocked(next)
                added = true
            }
            if (added) ensureDrain()
            added
        }
    }

    suspend fun retry(taskId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = _tasks.value.map {
                if (it.id == taskId) {
                    it.copy(status = DownloadStatus.QUEUED, progress = 0, errorMessage = null)
                } else {
                    it
                }
            }
            _tasks.value = next
            persistIndexLocked(next)
        }
        ensureDrain()
    }

    suspend fun delete(taskId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = _tasks.value.filterNot { it.id == taskId }
            _tasks.value = next
            persistIndexLocked(next)
        }
        runCatching { File(rootDir, taskId).deleteRecursively() }
    }

    /**
     * 使用 Android 原生 [MediaExtractor] + [MediaMuxer] 无损封装缓存为 MP4 / M4A 并保存到系统 Downloads/BiliCO。
     * 返回保存后的文件名。
     */
    suspend fun exportToDownloads(
        task: VideoDownloadTask,
        onProgress: (Int) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(task.status == DownloadStatus.DONE) { "缓存尚未完成" }
            val videoFile = task.videoPath?.let(::File)?.takeIf(File::isFile)
            val audioFile = task.audioPath?.let(::File)?.takeIf(File::isFile)
            val baseName = sanitizeFileName(task.title).ifBlank { task.bvid }

            if (task.kind == DownloadKind.AUDIO_ONLY || videoFile == null) {
                val src = audioFile ?: error("找不到已缓存的音频文件")
                onProgress(10)
                val fileName = "$baseName.m4a"
                saveToPublicDownloads(src, fileName, "audio/mp4")
                onProgress(100)
                fileName
            } else if (audioFile == null) {
                onProgress(10)
                val fileName = "$baseName.mp4"
                saveToPublicDownloads(videoFile, fileName, "video/mp4")
                onProgress(100)
                fileName
            } else {
                val fileName = "$baseName.mp4"
                val exportDir = File(appContext.cacheDir, "export_tmp").apply { mkdirs() }
                val tmpOut = File(exportDir, "${task.id}.mp4")
                try {
                    if (tmpOut.exists()) tmpOut.delete()
                    muxToMp4(videoFile, audioFile, tmpOut, onProgress)
                    saveToPublicDownloads(tmpOut, fileName, "video/mp4")
                    onProgress(100)
                    fileName
                } finally {
                    tmpOut.delete()
                }
            }
        }
    }

    private suspend fun ensureDrain() {
        drainMutex.withLock {
            if (drainJob?.isActive == true) return
            drainJob = scope.launch(Dispatchers.IO) {
                while (true) {
                    val next = _tasks.value.firstOrNull { it.status == DownloadStatus.QUEUED } ?: break
                    runTask(next)
                }
            }
        }
    }

    private suspend fun runTask(task: VideoDownloadTask) {
        val dir = File(rootDir, task.id).apply { mkdirs() }
        val headers = client.mediaRequestHeaders(task.bvid)
        updateTask(task.id) { it.copy(status = DownloadStatus.DOWNLOADING, progress = 0, errorMessage = null) }

        try {
            val needVideo = task.kind == DownloadKind.VIDEO && task.videoUrls.isNotEmpty()
            val needAudio = task.audioUrls.isNotEmpty()
            val videoOut = if (needVideo) File(dir, "video.m4s") else null
            val audioOut = if (needAudio) File(dir, "audio.m4s") else null

            var videoBytes = 0L
            var audioBytes = 0L
            if (videoOut != null) {
                videoBytes = downloadWithFallback(task.videoUrls, videoOut, headers) { done, total ->
                    val pct = if (total > 0L) {
                        val weight = if (audioOut != null) 80 else 100
                        ((done * weight) / total).toInt().coerceIn(0, weight)
                    } else {
                        10
                    }
                    updateTaskProgress(task.id, pct, done, total)
                }
            }
            if (audioOut != null) {
                audioBytes = downloadWithFallback(task.audioUrls, audioOut, headers) { done, total ->
                    val base = if (videoOut != null) 80 else 0
                    val weight = 100 - base
                    val pct = if (total > 0L) {
                        base + ((done * weight) / total).toInt().coerceIn(0, weight)
                    } else {
                        base + 10
                    }
                    updateTaskProgress(task.id, pct, videoBytes + done, videoBytes + total)
                }
            }

            updateTask(task.id) {
                it.copy(
                    status = DownloadStatus.DONE,
                    progress = 100,
                    downloadedBytes = videoBytes + audioBytes,
                    totalBytes = videoBytes + audioBytes,
                    videoPath = videoOut?.absolutePath,
                    audioPath = audioOut?.absolutePath,
                    errorMessage = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            updateTask(task.id) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = error.message ?: "缓存失败",
                )
            }
        }
    }

    private suspend fun downloadWithFallback(
        urls: List<String>,
        target: File,
        headers: Map<String, String>,
        onProgress: suspend (Long, Long) -> Unit,
    ): Long {
        var lastError: Throwable? = null
        for (url in urls) {
            val tmp = File(target.parentFile, "${target.name}.part")
            try {
                val reqBuilder = Request.Builder().url(url)
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                client.mediaClient().newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body
                    val total = body.contentLength()
                    var downloaded = 0L
                    var lastReportAt = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(tmp).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastReportAt >= 250L) {
                                    lastReportAt = now
                                    onProgress(downloaded, total)
                                }
                            }
                        }
                    }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    onProgress(downloaded, if (total > 0L) total else downloaded)
                    return downloaded
                }
            } catch (cancelled: CancellationException) {
                tmp.delete()
                throw cancelled
            } catch (e: Throwable) {
                tmp.delete()
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("所有缓存线路均不可用")
    }

    private fun muxToMp4(
        videoFile: File,
        audioFile: File?,
        outFile: File,
        onProgress: (Int) -> Unit,
    ) {
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = audioFile?.let { MediaExtractor().apply { setDataSource(it.absolutePath) } }
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            val videoSrcIndex = findTrack(videoExtractor, "video/")
            videoExtractor.selectTrack(videoSrcIndex)
            val videoTrackIndex = muxer.addTrack(videoExtractor.getTrackFormat(videoSrcIndex))

            var audioTrackIndex = -1
            if (audioExtractor != null) {
                val audioSrcIndex = findTrackOrNull(audioExtractor, "audio/")
                if (audioSrcIndex >= 0) {
                    audioExtractor.selectTrack(audioSrcIndex)
                    audioTrackIndex = muxer.addTrack(audioExtractor.getTrackFormat(audioSrcIndex))
                }
            } else {
                // 单文件 MP4 源本身可能已包含音频轨
                val embeddedAudio = findTrackOrNull(videoExtractor, "audio/")
                if (embeddedAudio >= 0) {
                    audioTrackIndex = muxer.addTrack(videoExtractor.getTrackFormat(embeddedAudio))
                }
            }

            muxer.start()
            muxerStarted = true

            val totalBytes = (videoFile.length() + (audioFile?.length() ?: 0L)).coerceAtLeast(1L)
            var writtenBytes = 0L
            var lastPct = 0
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            fun copyTrack(extractor: MediaExtractor, targetTrack: Int) {
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

            copyTrack(videoExtractor, videoTrackIndex)
            if (audioExtractor != null && audioTrackIndex >= 0) {
                copyTrack(audioExtractor, audioTrackIndex)
            }
        } finally {
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor?.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int =
        findTrackOrNull(extractor, mimePrefix).takeIf { it >= 0 }
            ?: error("未找到${if (mimePrefix == "video/") "视频" else "音频"}轨道")

    private fun findTrackOrNull(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun saveToPublicDownloads(source: File, fileName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BiliCO")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建下载文件")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(source).use { input -> input.copyTo(out) }
                } ?: error("无法写入下载文件")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BiliCO")
                .apply { mkdirs() }
            val dest = File(dir, fileName)
            source.copyTo(dest, overwrite = true)
        }
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().take(80)

    private suspend fun updateTask(id: String, transform: (VideoDownloadTask) -> VideoDownloadTask) {
        mutex.withLock {
            val next = _tasks.value.map { if (it.id == id) transform(it) else it }
            _tasks.value = next
            persistIndexLocked(next)
        }
    }

    private suspend fun updateTaskProgress(id: String, pct: Int, downloaded: Long, total: Long) {
        mutex.withLock {
            _tasks.value = _tasks.value.map {
                if (it.id == id) {
                    it.copy(
                        progress = pct.coerceIn(0, 100),
                        downloadedBytes = downloaded,
                        totalBytes = if (total > 0L) total else it.totalBytes,
                    )
                } else {
                    it
                }
            }
        }
    }

    private suspend fun loadIndex() {
        mutex.withLock {
            if (!indexFile.isFile) return
            val loaded = runCatching {
                BiliClient.json.decodeFromString<List<VideoDownloadTask>>(indexFile.readText())
            }.getOrNull().orEmpty().map { task ->
                // 上次运行中未完成就退出的任务恢复为排队状态
                if (task.status == DownloadStatus.DOWNLOADING) {
                    task.copy(status = DownloadStatus.QUEUED, progress = 0)
                } else {
                    task
                }
            }
            _tasks.value = loaded
        }
    }

    private fun persistIndexLocked(list: List<VideoDownloadTask>) {
        runCatching {
            val tmp = File(indexFile.parentFile, "${indexFile.name}.tmp")
            tmp.writeText(BiliClient.json.encodeToString(list))
            if (!tmp.renameTo(indexFile)) {
                indexFile.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
