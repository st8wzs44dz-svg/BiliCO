package erl.bilico.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadKind {
    VIDEO,
    AUDIO_ONLY,
}

@Serializable
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    DONE,
    FAILED,
}

@Serializable
data class VideoDownloadTask(
    val id: String,
    val bvid: String,
    val cid: Long,
    val title: String,
    val upName: String = "",
    val coverUrl: String = "",
    val qualityLabel: String = "",
    val kind: DownloadKind = DownloadKind.VIDEO,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Int = 0,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val videoUrls: List<String> = emptyList(),
    val audioUrls: List<String> = emptyList(),
    val videoPath: String? = null,
    val audioPath: String? = null,
    val errorMessage: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
)
