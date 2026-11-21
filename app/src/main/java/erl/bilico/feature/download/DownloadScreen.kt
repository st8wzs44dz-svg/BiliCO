package erl.bilico.feature.download

import android.net.Uri
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import erl.bilico.data.model.DownloadKind
import erl.bilico.data.model.DownloadStatus
import erl.bilico.data.model.VideoDownloadTask
import erl.bilico.data.util.Formatters
import erl.bilico.di.AppContainer
import erl.bilico.player.buildConfiguredExoPlayer
import erl.bilico.ui.NavState
import erl.bilico.ui.Route
import erl.bilico.ui.components.ConfirmDialog
import erl.bilico.ui.components.CoverImage
import erl.bilico.ui.components.EmptyState
import erl.bilico.ui.components.SecondaryPage
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.HighlightedIconButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Music
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun DownloadScreen(
    container: AppContainer,
    nav: NavState,
) {
    val tasks by container.downloads.tasks.collectAsStateWithLifecycle()
    val decoderMode by container.settings.decoderMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportingProgress = remember { mutableStateMapOf<String, Int>() }
    var playingTask by remember { mutableStateOf<VideoDownloadTask?>(null) }
    var pendingDelete by remember { mutableStateOf<VideoDownloadTask?>(null) }

    SecondaryPage(
        title = "离线缓存",
        onBack = nav::pop,
    ) {
        if (tasks.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState("还没有离线缓存\n在视频播放页右上角「更多 → 离线缓存」中可添加")
                }
            }
        } else {
            items(tasks, key = { it.id }) { task ->
                val exportPct = exportingProgress[task.id]
                DownloadTaskCard(
                    task = task,
                    exportProgress = exportPct,
                    onPlayOffline = { playingTask = task },
                    onOpenOnline = { nav.push(Route.Video(task.bvid, cid = task.cid)) },
                    onExport = {
                        if (exportingProgress.containsKey(task.id)) return@DownloadTaskCard
                        exportingProgress[task.id] = 1
                        scope.launch {
                            container.downloads.exportToDownloads(task) { pct ->
                                exportingProgress[task.id] = pct
                            }.onSuccess { fileName ->
                                Toast.makeText(
                                    context,
                                    "已导出至 Downloads/BiliCO/$fileName",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }.onFailure { err ->
                                Toast.makeText(
                                    context,
                                    err.message ?: "导出失败",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            exportingProgress.remove(task.id)
                        }
                    },
                    onRetry = {
                        scope.launch { container.downloads.retry(task.id) }
                    },
                    onDelete = { pendingDelete = task },
                )
            }
        }
    }

    playingTask?.let { task ->
        OfflinePlayerDialog(
            task = task,
            decoderMode = decoderMode,
            onDismiss = { playingTask = null },
        )
    }

    pendingDelete?.let { task ->
        ConfirmDialog(
            title = "删除缓存",
            message = "确定删除「${task.title}」的本地缓存文件吗？",
            confirmText = "删除",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                val id = task.id
                pendingDelete = null
                scope.launch { container.downloads.delete(id) }
            },
        )
    }
}

@Composable
private fun DownloadTaskCard(
    task: VideoDownloadTask,
    exportProgress: Int?,
    onPlayOffline: () -> Unit,
    onOpenOnline: () -> Unit,
    onExport: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = COUITheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        cornerRadius = CardDefaults.GroupCornerRadius,
        insideMargin = PaddingValues(12.dp),
        weight = CardDefaults.GroupWeight,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (task.status == DownloadStatus.DONE) onPlayOffline() else onOpenOnline()
                    },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(116.dp)
                        .aspectRatio(16f / 10f)
                        .clip(SquircleShape(10.dp))
                        .background(scheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (task.coverUrl.isNotBlank()) {
                        CoverImage(
                            url = Formatters.sizedImageUrl(task.coverUrl, 360, 225),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = if (task.kind == DownloadKind.AUDIO_ONLY) COUIIcons.Music else COUIIcons.Play,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = scheme.onSurfaceSecondary,
                        )
                    }
                    val badge = if (task.kind == DownloadKind.AUDIO_ONLY) "M4A" else task.qualityLabel.ifBlank { "MP4" }
                    Text(
                        text = badge,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(SquircleShape(4.dp))
                            .background(Color(0xB3000000))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = task.title,
                        style = COUITheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (task.upName.isNotBlank()) {
                        Text(
                            text = task.upName,
                            style = COUITheme.textStyles.footnote2,
                            color = scheme.onSurfaceSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val statusLine = when (task.status) {
                        DownloadStatus.QUEUED -> "排队等待缓存…"
                        DownloadStatus.DOWNLOADING -> {
                            val sizePart = if (task.totalBytes > 0L) {
                                " · ${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}"
                            } else if (task.downloadedBytes > 0L) {
                                " · ${formatBytes(task.downloadedBytes)}"
                            } else {
                                ""
                            }
                            "正在缓存 ${task.progress}%$sizePart"
                        }
                        DownloadStatus.DONE -> {
                            val size = if (task.totalBytes > 0L) " · ${formatBytes(task.totalBytes)}" else ""
                            "已完成$size"
                        }
                        DownloadStatus.FAILED -> task.errorMessage?.ifBlank { "缓存失败" } ?: "缓存失败"
                    }
                    Text(
                        text = statusLine,
                        style = COUITheme.textStyles.footnote2,
                        color = if (task.status == DownloadStatus.FAILED) {
                            Color(0xFFD93025)
                        } else if (task.status == DownloadStatus.DONE) {
                            scheme.primary
                        } else {
                            scheme.onSurfaceSecondary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.QUEUED) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(SquircleShape(2.dp))
                        .background(scheme.surfaceContainerHigh),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((task.progress.coerceIn(0, 100) / 100f).coerceAtLeast(0.02f))
                            .height(4.dp)
                            .background(scheme.primary),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (task.status == DownloadStatus.DONE) {
                    ActionPill(
                        label = "离线播放",
                        icon = COUIIcons.Play,
                        primary = true,
                        onClick = onPlayOffline,
                    )
                    ActionPill(
                        label = if (exportProgress != null) {
                            "导出中 ${exportProgress}%"
                        } else if (task.kind == DownloadKind.AUDIO_ONLY) {
                            "导出 M4A"
                        } else {
                            "导出 MP4"
                        },
                        icon = COUIIcons.Download,
                        primary = false,
                        enabled = exportProgress == null,
                        onClick = onExport,
                    )
                } else if (task.status == DownloadStatus.FAILED) {
                    ActionPill(
                        label = "重试",
                        icon = COUIIcons.Refresh,
                        primary = true,
                        onClick = onRetry,
                    )
                }
                Spacer(Modifier.weight(1f))
                HighlightedIconButton(onClick = onDelete) {
                    Icon(
                        imageVector = COUIIcons.Delete,
                        contentDescription = "删除缓存",
                        modifier = Modifier.size(20.dp),
                        tint = scheme.onSurfaceSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = COUITheme.colorScheme
    val bg = if (primary) scheme.primary.copy(alpha = 0.14f) else scheme.surfaceContainerHigh
    val fg = if (primary) scheme.primary else scheme.onSurface
    Row(
        modifier = Modifier
            .clip(SquircleShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = fg)
        Text(
            text = label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun OfflinePlayerDialog(
    task: VideoDownloadTask,
    decoderMode: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(task.id, decoderMode) {
        buildConfiguredExoPlayer(context, decoderMode = decoderMode).apply {
            val factory = DefaultDataSource.Factory(context)
            val videoFile = task.videoPath?.let(::File)?.takeIf(File::isFile)
            val audioFile = task.audioPath?.let(::File)?.takeIf(File::isFile)
            val mediaSource = when {
                task.kind == DownloadKind.VIDEO && videoFile != null && audioFile != null -> {
                    val v = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.fromFile(videoFile)))
                    val a = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.fromFile(audioFile)))
                    MergingMediaSource(true, v, a)
                }
                videoFile != null -> {
                    ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.fromFile(videoFile)))
                }
                audioFile != null -> {
                    ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.fromFile(audioFile)))
                }
                else -> null
            }
            if (mediaSource != null) {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val scheme = COUITheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .clip(SquircleShape(20.dp))
                .background(scheme.surfaceContainer)
                .padding(bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = task.title,
                    style = COUITheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                HighlightedIconButton(onClick = onDismiss) {
                    Icon(COUIIcons.Close, "关闭", Modifier.size(22.dp))
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                        }
                    },
                    update = { view ->
                        if (view.player !== player) view.player = player
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (task.kind == DownloadKind.AUDIO_ONLY) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (task.coverUrl.isNotBlank()) {
                            CoverImage(
                                url = Formatters.sizedImageUrl(task.coverUrl, 320, 200),
                                modifier = Modifier
                                    .width(120.dp)
                                    .aspectRatio(16f / 10f)
                                    .clip(SquircleShape(10.dp)),
                            )
                        }
                        Text(
                            text = "纯音频离线播放",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
