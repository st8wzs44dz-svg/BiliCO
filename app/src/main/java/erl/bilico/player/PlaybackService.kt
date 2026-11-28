package erl.bilico.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.legacy.MediaMetadataCompat
import androidx.media3.session.legacy.MediaSessionCompat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import erl.bilico.BiliCoApplication
import erl.bilico.MainActivity
import erl.bilico.R
import erl.bilico.data.util.Formatters
import erl.bilico.platform.PlatformContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 后台播放的系统媒体条。进度、播放和暂停都交给 [MediaSession]，系统按播放器的位置自己画进度条。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private data class ButtonState(
        val enabled: Boolean,
        val liked: Boolean,
        val favorited: Boolean,
    )

    private var mediaSession: MediaSession? = null
    private var boundPlayer: Player? = null
    private var sessionPlayer: ForwardingPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var artworkBytes: ByteArray? = null
    private var loadedArtwork: String = ""
    private var publishedButtonState: ButtonState? = null
    private var likeInFlight = false
    private var favoriteInFlight = false

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PlaybackBridge.ACTION_STOP_BG) {
            PlaybackBridge.stopPlayback(PlatformContext(this))
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_PROMOTE, false) == true) holdForeground()
        ensureSession()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun holdForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "后台播放", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(PlaybackBridge.activeTitle.ifBlank { "正在后台播放" })
            .setContentText(PlaybackBridge.activeSubtitle.ifBlank { "BiliCO" })
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(HOLD_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(HOLD_NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        PlaybackBridge.interactionListener = null
        mediaSession?.release()
        mediaSession = null
        sessionPlayer = null
        boundPlayer = null
        publishedButtonState = null
        super.onDestroy()
    }

    private fun ensureSession() {
        val player: Player = PlaybackBridge.activePlayer?.exoPlayer ?: return
        PlaybackBridge.interactionListener = {
            mainHandler.post { syncMediaButtons(mediaSession) }
        }
        if (boundPlayer === player && mediaSession != null) {
            syncMediaButtons(mediaSession)
            attachArtwork(mediaSession)
            return
        }
        mediaSession?.release()
        boundPlayer = player
        val wrapped = object : ForwardingPlayer(player) {
            override fun getMediaMetadata(): MediaMetadata {
                val builder = super.getMediaMetadata().buildUpon()
                    .setTitle(PlaybackBridge.activeTitle.ifBlank { "正在后台播放" })
                    .setArtist(PlaybackBridge.activeSubtitle.ifBlank { "BiliCO" })
                artworkBytes?.let { builder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                return builder.build()
            }

            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands()
                    .buildUpon()
                    .remove(COMMAND_SEEK_TO_NEXT)
                    .remove(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .remove(COMMAND_SEEK_TO_PREVIOUS)
                    .remove(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()

            override fun isCommandAvailable(command: Int): Boolean =
                command != COMMAND_SEEK_TO_NEXT &&
                    command != COMMAND_SEEK_TO_NEXT_MEDIA_ITEM &&
                    command != COMMAND_SEEK_TO_PREVIOUS &&
                    command != COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM &&
                    super.isCommandAvailable(command)
        }
        sessionPlayer = wrapped
        val openIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            201,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val initialButtons = currentButtonState()
        val session = MediaSession.Builder(this, wrapped)
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity)
            .setCallback(Callback())
            .setMediaButtonPreferences(buildMediaButtonPreferences(initialButtons))
            .build()
        mediaSession = session
        publishedButtonState = initialButtons
        attachArtwork(session)
    }

    private fun currentButtonState(): ButtonState {
        val interaction = PlaybackBridge.activeVideoInteraction
        return if (interaction != null && interaction.aid > 0L) {
            ButtonState(enabled = true, liked = interaction.liked, favorited = interaction.favorited)
        } else {
            ButtonState(enabled = false, liked = false, favorited = false)
        }
    }

    private fun buildMediaButtonPreferences(state: ButtonState): List<CommandButton> {
        if (!state.enabled) return emptyList()
        val likeButton = CommandButton.Builder(
            if (state.liked) CommandButton.ICON_THUMB_UP_FILLED else CommandButton.ICON_THUMB_UP_UNFILLED,
        )
            .setCustomIconResId(
                if (state.liked) R.drawable.ic_media_like_filled else R.drawable.ic_media_like_outline,
            )
            .setDisplayName(if (state.liked) "取消点赞" else "点赞")
            .setSessionCommand(COMMAND_TOGGLE_LIKE)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        val favoriteButton = CommandButton.Builder(
            if (state.favorited) CommandButton.ICON_STAR_FILLED else CommandButton.ICON_STAR_UNFILLED,
        )
            .setCustomIconResId(
                if (state.favorited) R.drawable.ic_media_favorite_filled else R.drawable.ic_media_favorite_outline,
            )
            .setDisplayName(if (state.favorited) "取消收藏" else "收藏")
            .setSessionCommand(COMMAND_TOGGLE_FAVORITE)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        return listOf(likeButton, favoriteButton)
    }

    private fun syncMediaButtons(session: MediaSession?) {
        val target = session ?: return
        val next = currentButtonState()
        if (next == publishedButtonState) return
        publishedButtonState = next
        runCatching {
            target.setMediaButtonPreferences(buildMediaButtonPreferences(next))
            onUpdateNotification(target, true)
        }.onFailure {
            Log.w(TAG, "实时通知按钮更新失败 ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun handleToggleLike() {
        val current = PlaybackBridge.activeVideoInteraction ?: return
        if (current.aid <= 0L || likeInFlight) return
        val container = (applicationContext as BiliCoApplication).container
        if (!container.session.isLoggedIn()) {
            showToast("登录后可用")
            return
        }
        val aid = current.aid
        val previous = current.liked
        val target = !previous
        likeInFlight = true
        PlaybackBridge.updateVideoInteraction(aid = aid, liked = target)
        container.scope.launch {
            val result = container.interactions.like(aid, target)
            mainHandler.post {
                likeInFlight = false
                result.onSuccess {
                    PlaybackBridge.updateVideoInteraction(aid = aid, liked = target)
                    PlaybackBridge.emitExternalInteraction(aid = aid, liked = target)
                }.onFailure { error ->
                    Log.w(TAG, "实时通知点赞失败 ${error.message}")
                    PlaybackBridge.updateVideoInteraction(aid = aid, liked = previous)
                    showToast(error.message ?: "操作失败")
                }
            }
        }
    }

    private fun handleToggleFavorite() {
        val current = PlaybackBridge.activeVideoInteraction ?: return
        if (current.aid <= 0L || favoriteInFlight) return
        val container = (applicationContext as BiliCoApplication).container
        if (!container.session.isLoggedIn()) {
            showToast("登录后可用")
            return
        }
        val aid = current.aid
        val previous = current.favorited
        val target = !previous
        favoriteInFlight = true
        PlaybackBridge.updateVideoInteraction(aid = aid, favorited = target)
        container.scope.launch {
            val result = container.interactions.toggleDefaultFavorite(aid, target)
            mainHandler.post {
                favoriteInFlight = false
                result.onSuccess { finalFavorited ->
                    PlaybackBridge.updateVideoInteraction(aid = aid, favorited = finalFavorited)
                    PlaybackBridge.emitExternalInteraction(aid = aid, favorited = finalFavorited)
                    if (!target && finalFavorited) {
                        showToast("已移出默认收藏夹，仍存在于其他收藏夹")
                    }
                }.onFailure { error ->
                    Log.w(TAG, "实时通知收藏失败 ${error.message}")
                    PlaybackBridge.updateVideoInteraction(aid = aid, favorited = previous)
                    showToast(error.message ?: "操作失败")
                }
            }
        }
    }

    private fun showToast(message: String) {
        if (message.isBlank()) return
        runCatching { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    private fun attachArtwork(session: MediaSession?) {
        val source = PlaybackBridge.activeArtworkUrl
        if (source.isBlank() || source == loadedArtwork) return
        val isLocal = source.startsWith("file://")
        val url = if (isLocal) source else Formatters.sizedImageUrl(source, 480, 270)
        if (!isLocal && !url.startsWith("http")) return
        artworkBytes = null
        loadedArtwork = source
        Thread {
            val bytes = runCatching {
                if (isLocal) java.io.File(java.net.URI(source)).readBytes() else download(url)
            }.getOrElse { error ->
                Log.w(TAG, "封面下载失败 ${error.message}")
                null
            } ?: run {
                mainHandler.post {
                    if (PlaybackBridge.activeArtworkUrl == source) loadedArtwork = ""
                }
                return@Thread
            }
            if (mediaSession !== session || PlaybackBridge.activeArtworkUrl != source) return@Thread
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            mainHandler.post {
                if (mediaSession !== session || PlaybackBridge.activeArtworkUrl != source) return@post
                artworkBytes = bytes
                publishArtwork(bytes)
                if (bitmap != null) publishLegacyArtwork(bitmap)
                val target = mediaSession ?: return@post
                runCatching { onUpdateNotification(target, true) }
            }
        }.start()
    }

    private fun publishArtwork(bytes: ByteArray) {
        val player = boundPlayer ?: return
        val index = player.currentMediaItemIndex
        val current = player.currentMediaItem ?: return
        if (current.localConfiguration == null) return
        val metadata = current.mediaMetadata.buildUpon()
            .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        val updated = current.buildUpon().setMediaMetadata(metadata).build()
        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
        runCatching { player.replaceMediaItem(index, updated) }
    }

    private fun publishLegacyArtwork(bitmap: Bitmap) {
        val session = mediaSession ?: return
        val compat = runCatching { legacySession(session) }.getOrNull() ?: return
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, PlaybackBridge.activeTitle.ifBlank { "正在后台播放" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, PlaybackBridge.activeSubtitle.ifBlank { "BiliCO" })
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
            .build()
        runCatching { compat.setMetadata(metadata) }
    }

    private fun legacySession(session: MediaSession): MediaSessionCompat {
        val impl = MediaSession::class.java.getDeclaredMethod("getImpl").apply { isAccessible = true }
            .invoke(session)
        val stub = impl.javaClass.getDeclaredMethod("getMediaSessionLegacyStub").apply { isAccessible = true }
            .invoke(impl)
        return stub.javaClass.getMethod("getSessionCompat").invoke(stub) as MediaSessionCompat
    }

    private fun download(url: String): ByteArray = runBlocking {
        (applicationContext as BiliCoApplication).container.client.fetchBytes(url)
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val hints = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(COMMAND_TOGGLE_LIKE)
                .add(COMMAND_TOGGLE_FAVORITE)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(hints)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                ACTION_TOGGLE_LIKE -> {
                    handleToggleLike()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_TOGGLE_FAVORITE -> {
                    handleToggleFavorite()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
    }

    companion object {
        private const val TAG = "BiliPlayback"
        private const val SESSION_ID = "bilico_playback"
        private const val CHANNEL_ID = "bilico_playback"
        private const val HOLD_NOTIFICATION_ID = 1001
        private const val EXTRA_PROMOTE = "promote"
        private const val ACTION_TOGGLE_LIKE = "erl.bilico.session.TOGGLE_LIKE"
        private const val ACTION_TOGGLE_FAVORITE = "erl.bilico.session.TOGGLE_FAVORITE"
        private val COMMAND_TOGGLE_LIKE = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)
        private val COMMAND_TOGGLE_FAVORITE = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)

        fun start(context: Context) {
            val appCtx = context.applicationContext
            runCatching { appCtx.startService(Intent(appCtx, PlaybackService::class.java)) }
        }

        fun promote(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, PlaybackService::class.java).putExtra(EXTRA_PROMOTE, true)
            runCatching { appCtx.startForegroundService(intent) }
        }

        fun refreshIfRunning(context: Context) {
            val appCtx = context.applicationContext
            runCatching { appCtx.startService(Intent(appCtx, PlaybackService::class.java)) }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runCatching { appCtx.stopService(Intent(appCtx, PlaybackService::class.java)) }
        }
    }
}
