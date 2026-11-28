package erl.bilico.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import erl.bilico.platform.PlatformContext
import erl.bilico.ui.LocalNavEntry
import erl.bilico.ui.LocalNavState
import erl.bilico.ui.LocalRouteIsTop
import erl.bilico.ui.LocalRouteMinimized
import erl.bilico.ui.Route
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.concurrent.Volatile

val LocalInPipMode = compositionLocalOf { false }

data class PlaybackVideoInteraction(
    val aid: Long,
    val liked: Boolean,
    val favorited: Boolean,
)

data class PlaybackInteractionEvent(
    val aid: Long,
    val liked: Boolean? = null,
    val favorited: Boolean? = null,
)

/**
 * 统一管理当前活跃播放器的后台播放与悬浮球状态。
 */
object PlaybackBridge {
    const val ACTION_TOGGLE_PLAY = "erl.bilico.action.TOGGLE_PLAY"
    const val ACTION_STOP_BG = "erl.bilico.action.STOP_BG"

    @Volatile
    var onSessionStart: (() -> Unit)? = null

    @Volatile
    var onSessionPromote: (() -> Unit)? = null

    @Volatile
    var onSessionRefresh: (() -> Unit)? = null

    @Volatile
    var onSessionStop: (() -> Unit)? = null

    @Volatile
    var isPlatformPipActive: () -> Boolean = { false }

    @Volatile
    var backgroundPlayEnabled: Boolean = true
        private set

    @Volatile
    var activePlayer: BiliPlayer? = null
        private set

    @Volatile
    private var activeOwner: Any? = null

    @Volatile
    var activeTitle: String = ""
        private set

    @Volatile
    var activeSubtitle: String = ""
        private set

    @Volatile
    var activeArtworkUrl: String = ""
        private set

    @Volatile
    var autoPipEnabled: Boolean = true
        private set

    @Volatile
    var activeMediaId: String = ""

    @Volatile
    var activeVideoInteraction: PlaybackVideoInteraction? = null
        private set

    @Volatile
    var interactionListener: (() -> Unit)? = null

    private val _externalInteractions = MutableSharedFlow<PlaybackInteractionEvent>(extraBufferCapacity = 8)
    val externalInteractions: SharedFlow<PlaybackInteractionEvent> = _externalInteractions.asSharedFlow()

    var currentTitle by mutableStateOf("")
        private set

    var currentSubtitle by mutableStateOf("")
        private set

    var currentArtworkUrl by mutableStateOf("")
        private set

    var currentIsPlaying by mutableStateOf(false)
        private set

    var stopVersion by mutableIntStateOf(0)
        private set

    @Volatile
    var mutedForRouteSwitch: Boolean = false
        private set

    fun updateVideoInteraction(aid: Long, liked: Boolean? = null, favorited: Boolean? = null) {
        if (aid <= 0L) return
        val current = activeVideoInteraction ?: return
        if (current.aid != aid) return
        val updated = current.copy(
            liked = liked ?: current.liked,
            favorited = favorited ?: current.favorited,
        )
        if (updated != current) {
            activeVideoInteraction = updated
            interactionListener?.invoke()
        }
    }

    fun emitExternalInteraction(aid: Long, liked: Boolean? = null, favorited: Boolean? = null) {
        if (aid <= 0L) return
        _externalInteractions.tryEmit(PlaybackInteractionEvent(aid = aid, liked = liked, favorited = favorited))
    }

    fun bind(
        player: BiliPlayer,
        title: String,
        subtitle: String,
        artworkUrl: String,
        isPlaying: Boolean,
        autoPip: Boolean,
        backgroundPlay: Boolean = true,
        owner: Any? = null,
        videoInteraction: PlaybackVideoInteraction? = null,
    ) {
        val previousInteraction = activeVideoInteraction
        activeOwner = owner
        activePlayer = player
        activeTitle = title
        activeSubtitle = subtitle
        activeArtworkUrl = artworkUrl
        autoPipEnabled = autoPip
        backgroundPlayEnabled = backgroundPlay
        activeVideoInteraction = videoInteraction
        currentTitle = title
        currentSubtitle = subtitle
        currentArtworkUrl = artworkUrl
        currentIsPlaying = isPlaying
        if (previousInteraction != videoInteraction) {
            interactionListener?.invoke()
        }
    }

    fun isOwner(owner: Any): Boolean = activeOwner === owner

    fun unbind(player: BiliPlayer, context: PlatformContext, owner: Any? = null) {
        if (activePlayer === player && (owner == null || activeOwner === owner)) {
            detach(player, owner)
            onSessionStop?.invoke()
        }
    }

    internal fun detach(player: BiliPlayer, owner: Any? = null) {
        if (activePlayer !== player) return
        if (owner != null && activeOwner !== owner) return
        if (mutedForRouteSwitch) {
            mutedForRouteSwitch = false
            player.volume = 1f
        }
        val hadInteraction = activeVideoInteraction != null
        activeOwner = null
        activePlayer = null
        activeMediaId = ""
        activeTitle = ""
        activeSubtitle = ""
        activeArtworkUrl = ""
        activeVideoInteraction = null
        currentTitle = ""
        currentSubtitle = ""
        currentArtworkUrl = ""
        currentIsPlaying = false
        if (hadInteraction) {
            interactionListener?.invoke()
        }
    }

    fun canMinimizeSession(): Boolean {
        val p = activePlayer ?: return false
        return p.playbackState != PlayerStates.STATE_IDLE &&
            p.playbackState != PlayerStates.STATE_ENDED &&
            (p.isPlaying || p.playWhenReady)
    }

    fun refreshSession(context: PlatformContext) {
        currentIsPlaying = activePlayer?.playWhenReady == true
        onSessionRefresh?.invoke()
    }

    fun togglePlayPause(context: PlatformContext) {
        val p = activePlayer ?: return
        if (p.isPlaying) p.pause() else p.play()
        currentIsPlaying = p.playWhenReady
        onSessionRefresh?.invoke()
    }

    fun pauseAndStopSession(context: PlatformContext) {
        val p = activePlayer
        if (mutedForRouteSwitch) {
            mutedForRouteSwitch = false
            p?.volume = 1f
        }
        p?.pause()
        currentIsPlaying = false
        onSessionStop?.invoke()
    }

    fun muteForRouteSwitch() {
        activePlayer?.volume = 0f
        mutedForRouteSwitch = true
        currentIsPlaying = false
    }

    fun consumeRouteSwitchMute(player: BiliPlayer): Boolean {
        if (!mutedForRouteSwitch) return false
        mutedForRouteSwitch = false
        player.volume = 1f
        return true
    }

    fun stopForRouteSwitch(context: PlatformContext) {
        activePlayer?.let {
            if (mutedForRouteSwitch) {
                mutedForRouteSwitch = false
                it.volume = 1f
            }
            it.stop()
            it.clearMediaItems()
            activeMediaId = ""
        }
        currentIsPlaying = false
        onSessionStop?.invoke()
    }

    fun stopPlayback(context: PlatformContext) {
        pauseAndStopSession(context)
        stopVersion++
    }
}

@Composable
fun rememberPlaybackPlayer(key: Any, factory: () -> BiliPlayer): BiliPlayer {
    val registryKey = key.toString()
    val holder: PlaybackPlayerHolder = viewModel(
        key = "playback-player-$registryKey",
    ) {
        PlaybackPlayerHolder(registryKey, factory)
    }
    val lastKey = remember { arrayOfNulls<String>(1) }
    SideEffect {
        val previous = lastKey[0]
        if (previous != null && previous != registryKey) releasePlaybackPlayer(previous)
        lastKey[0] = registryKey
    }
    DisposableEffect(holder, registryKey) {
        playerRegistry[registryKey] = holder
        onDispose { }
    }
    return remember(holder, registryKey) { holder.obtain() }
}

fun releasePlaybackPlayer(key: Any) {
    playerRegistry.remove(key.toString())?.release()
}

private val playerRegistry = HashMap<String, PlaybackPlayerHolder>()

private class PlaybackPlayerHolder(
    private val registryKey: String,
    private val factory: () -> BiliPlayer,
) : ViewModel() {
    private var active: BiliPlayer = factory()
    private var released = false

    fun obtain(): BiliPlayer {
        if (released) {
            released = false
            active = factory()
        }
        return active
    }

    fun release() {
        if (released) return
        released = true
        PlaybackBridge.detach(active)
        active.release()
    }

    override fun onCleared() {
        release()
        playerRegistry.remove(registryKey)
    }
}

fun buildConfiguredBiliPlayer(context: PlatformContext): BiliPlayer = BiliPlayer(context)

@Composable
fun BindPlaybackSession(
    player: BiliPlayer,
    title: String,
    subtitle: String,
    artworkUrl: String = "",
    isPlaying: Boolean,
    backgroundPlay: Boolean,
    autoPip: Boolean,
    videoInteraction: PlaybackVideoInteraction? = null,
) {
    val context = erl.bilico.platform.LocalPlatformContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isTop = LocalRouteIsTop.current
    val isMinimized = LocalRouteMinimized.current
    val activeSession = isTop || isMinimized
    val effectiveAutoPip = autoPip && isTop

    val sessionOwner = remember { Any() }
    val routeEntry = LocalNavEntry.current
    val navState = LocalNavState.current
    val inPipMode = LocalInPipMode.current
    val currentBackgroundPlay = rememberUpdatedState(backgroundPlay)
    val currentInPipMode = rememberUpdatedState(inPipMode)
    val poppingOut = routeEntry?.exiting == true && !routeEntry.minimizingToOrb

    LaunchedEffect(
        activeSession,
        player,
        title,
        subtitle,
        artworkUrl,
        isPlaying,
        backgroundPlay,
        effectiveAutoPip,
        poppingOut,
        videoInteraction,
    ) {
        if (!activeSession || poppingOut) return@LaunchedEffect
        PlaybackBridge.bind(
            player = player,
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            isPlaying = isPlaying,
            autoPip = effectiveAutoPip,
            backgroundPlay = backgroundPlay,
            owner = sessionOwner,
            videoInteraction = videoInteraction,
        )
        PlaybackBridge.onSessionStart?.invoke()
    }

    DisposableEffect(player, lifecycleOwner) {
        var videoDisabledForBg = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (!PlaybackBridge.isOwner(sessionOwner)) return@LifecycleEventObserver
                    if (!currentInPipMode.value && !PlaybackBridge.isPlatformPipActive()) {
                        if (currentBackgroundPlay.value && (player.isPlaying || player.playWhenReady)) {
                            // 退到后台纯音频播放时关闭视频轨，停掉硬件视频解码器并省去视频流拉取。
                            if (!videoDisabledForBg) {
                                player.setVideoTrackDisabled(true)
                                videoDisabledForBg = true
                            }
                            PlaybackBridge.onSessionPromote?.invoke()
                        } else if (player.isPlaying) {
                            player.pause()
                            PlaybackBridge.onSessionStop?.invoke()
                        }
                    }
                }

                Lifecycle.Event.ON_START -> {
                    if (videoDisabledForBg) {
                        videoDisabledForBg = false
                        player.setVideoTrackDisabled(false)
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (videoDisabledForBg) {
                videoDisabledForBg = false
                player.setVideoTrackDisabled(false)
            }
            val entry = routeEntry
            if (entry != null && (entry.minimizingToOrb || navState?.minimizedEntry === entry)) {
                return@onDispose
            }
            val newTop = navState?.stack?.lastOrNull { !it.exiting }
            if (entry != null && entry.route is Route.Video &&
                newTop?.route is Route.Video && newTop !== entry
            ) {
                return@onDispose
            }
            if (!PlaybackBridge.isOwner(sessionOwner)) return@onDispose
            PlaybackBridge.unbind(player, context, owner = sessionOwner)
        }
    }
}
