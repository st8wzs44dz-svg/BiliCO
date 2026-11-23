package erl.bilico.feature.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import erl.bilico.data.model.ImSessionUi
import erl.bilico.data.model.MessageCursor
import erl.bilico.data.model.MessageEntry
import erl.bilico.data.model.MessageJumpTarget
import erl.bilico.data.model.MessageKind
import erl.bilico.data.model.MessageUnreadSummary
import erl.bilico.data.repository.ImRepository
import erl.bilico.data.repository.ImSessionEvent
import erl.bilico.data.repository.MessageRepository
import erl.bilico.data.session.SessionRepository
import erl.bilico.ui.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 单个通知消息分类的一页内容；各分类各自独立，左右滑动互不打断。 */
data class MessageKindState(
    val items: List<MessageEntry> = emptyList(),
    val highlightedKeys: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isEnd: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

data class MessageUiState(
    val kinds: Map<MessageKind, MessageKindState> = emptyMap(),
    val unread: MessageUnreadSummary = MessageUnreadSummary(),
    // 私信会话
    val sessions: List<ImSessionUi> = emptyList(),
    val isSessionsLoading: Boolean = false,
    val isSessionsLoadingMore: Boolean = false,
    val isSessionsEnd: Boolean = false,
    val sessionsLoaded: Boolean = false,
    val sessionsEndTs: Long? = null,
    val sessionsError: String? = null,
) {
    fun of(kind: MessageKind): MessageKindState = kinds[kind] ?: MessageKindState()
}

class MessageViewModel(
    private val repository: MessageRepository,
    private val imRepository: ImRepository,
    private val session: SessionRepository,
) : ViewModel() {

    private val initialUnread = repository.unread.value
    val initialKind: MessageKind = initialUnread.preferredInitialKind()

    private val _state = MutableStateFlow(MessageUiState(unread = initialUnread))
    val state: StateFlow<MessageUiState> = _state.asStateFlow()

    private val pendingHighlightCounts = mutableMapOf(
        MessageKind.REPLY to initialUnread.reply,
        MessageKind.AT to initialUnread.at,
        MessageKind.LIKE to initialUnread.like,
        MessageKind.SYSTEM to initialUnread.system,
    )

    private val cursors = mutableMapOf<MessageKind, MessageCursor?>()
    private val loading = mutableSetOf<MessageKind>()
    private var resolvingJump = false

    init {
        viewModelScope.launch {
            repository.unread.collect { summary ->
                _state.update { it.copy(unread = summary) }
            }
        }
        viewModelScope.launch {
            imRepository.sessionEvents.collect { event ->
                when (event) {
                    is ImSessionEvent.SessionRead -> {
                        var cleared = 0
                        _state.update { cur ->
                            val updated = cur.sessions.map { item ->
                                if (item.talkerId == event.talkerId) {
                                    cleared = item.unreadCount
                                    if (item.unreadCount != 0) item.copy(unreadCount = 0) else item
                                } else {
                                    item
                                }
                            }
                            cur.copy(sessions = updated)
                        }
                        if (cleared > 0) {
                            repository.consumeSessionUnread(cleared)
                        }
                        if (cleared > 0 || repository.unread.value.session > 0) {
                            repository.refreshUnread(force = true)
                        }
                    }
                    is ImSessionEvent.MessageSent -> {
                        _state.update { cur ->
                            val idx = cur.sessions.indexOfFirst { it.talkerId == event.talkerId }
                            if (idx < 0) return@update cur
                            val updatedItem = cur.sessions[idx].copy(
                                lastMessage = event.preview,
                                timeText = "刚刚",
                                timestamp = event.timestamp,
                                unreadCount = 0,
                            )
                            val reordered = buildList(cur.sessions.size) {
                                add(updatedItem)
                                cur.sessions.forEachIndexed { i, item ->
                                    if (i != idx) add(item)
                                }
                            }
                            cur.copy(sessions = reordered)
                        }
                    }
                }
            }
        }
        ensureLoaded(initialKind)
    }

    /** 点击回复、@、点赞通知条目后跳转到对应的视频、动态、专栏或用户主页。 */
    fun openEntry(
        entry: MessageEntry,
        onNavigate: (Route) -> Unit,
        onError: (String) -> Unit,
    ) {
        when (val target = entry.target) {
            is MessageJumpTarget.VideoBvid -> onNavigate(
                Route.Video(
                    bvid = target.bvid,
                    commentRootId = target.commentRootId,
                    commentTargetId = target.commentTargetId,
                )
            )
            is MessageJumpTarget.Opus -> onNavigate(
                Route.Opus(
                    dynamicId = target.dynamicId,
                    title = target.title,
                    commentRootId = target.commentRootId,
                    commentTargetId = target.commentTargetId,
                )
            )
            is MessageJumpTarget.Article -> onNavigate(
                Route.Article(
                    cvId = target.cvId,
                    title = target.title,
                    commentRootId = target.commentRootId,
                    commentTargetId = target.commentTargetId,
                )
            )
            is MessageJumpTarget.PgcSeason -> onNavigate(Route.Pgc(seasonId = target.seasonId))
            is MessageJumpTarget.PgcEpisode -> onNavigate(Route.Pgc(seasonId = 0L, epId = target.epId))
            is MessageJumpTarget.LiveRoom -> onNavigate(Route.LiveRoom(target.roomId))
            is MessageJumpTarget.VideoAid -> {
                if (resolvingJump) return
                resolvingJump = true
                viewModelScope.launch {
                    repository.resolveBvid(target.aid)
                        .onSuccess { bvid ->
                            onNavigate(
                                Route.Video(
                                    bvid = bvid,
                                    commentRootId = target.commentRootId,
                                    commentTargetId = target.commentTargetId,
                                )
                            )
                        }
                        .onFailure { err -> onError(err.message ?: "无法打开该视频") }
                    resolvingJump = false
                }
            }
            null -> {
                if (entry.senderMid > 0L) {
                    onNavigate(Route.Space(entry.senderMid))
                } else {
                    onError("该通知暂不支持跳转")
                }
            }
        }
    }

    /** 第一次滑到某一类时拉它的第一页；若该类已加载但有新未读到达，则后台静默补拉并清零未读。 */
    fun ensureLoaded(kind: MessageKind) {
        val unreadForKind = repository.unread.value.countOf(kind)
        if (kind == MessageKind.SESSION) {
            val cur = _state.value
            if (cur.isSessionsLoading || loading.contains(kind)) return
            if (!cur.sessionsLoaded) {
                loadSessions(firstPage = true, silent = false)
            } else if (unreadForKind > 0) {
                loadSessions(firstPage = true, silent = true)
            }
            return
        }
        val current = _state.value.of(kind)
        if (current.isLoading || loading.contains(kind)) return
        if (!current.loaded) {
            load(kind, firstPage = true, silent = false)
        } else if (unreadForKind > 0) {
            pendingHighlightCounts[kind] = unreadForKind
            load(kind, firstPage = true, silent = true)
        }
    }

    /** 失败重试：丢掉这一类的游标和已有内容重来。 */
    fun reload(kind: MessageKind) {
        if (loading.contains(kind)) return
        if (kind == MessageKind.SESSION) {
            loadSessions(firstPage = true, silent = false)
        } else {
            load(kind, firstPage = true, silent = false)
        }
    }

    fun loadMore(kind: MessageKind) {
        if (kind == MessageKind.SESSION) {
            val cur = _state.value
            if (loading.contains(kind) || cur.isSessionsEnd || cur.sessions.isEmpty()) return
            loadSessions(firstPage = false, silent = false)
            return
        }
        val current = _state.value.of(kind)
        if (loading.contains(kind) || current.isEnd || current.items.isEmpty()) return
        load(kind, firstPage = false, silent = false)
    }

    private fun load(kind: MessageKind, firstPage: Boolean, silent: Boolean) {
        if (!loading.add(kind)) return
        if (!session.isLoggedIn()) {
            loading -= kind
            set(kind, MessageKindState())
            return
        }
        if (firstPage) {
            cursors[kind] = null
            if (!silent || _state.value.of(kind).items.isEmpty()) {
                set(kind, MessageKindState(isLoading = true))
            }
        } else {
            update(kind) { it.copy(isLoadingMore = true) }
        }
        val pageCursor = if (firstPage) null else cursors[kind]
        viewModelScope.launch {
            repository.page(kind, pageCursor)
                .onSuccess { page ->
                    cursors[kind] = page.cursor
                    val highlightCount = if (firstPage) {
                        (pendingHighlightCounts.remove(kind) ?: 0).coerceAtLeast(0)
                    } else {
                        0
                    }
                    val newHighlights = if (highlightCount > 0) {
                        page.items.take(highlightCount).mapTo(LinkedHashSet()) { it.key }
                    } else {
                        emptySet()
                    }
                    if (firstPage) {
                        repository.markKindViewed(kind)
                    }
                    update(kind) { state ->
                        state.copy(
                            items = if (firstPage) page.items else state.items + page.items,
                            highlightedKeys = if (firstPage) newHighlights else state.highlightedKeys,
                            isLoading = false,
                            isLoadingMore = false,
                            loaded = true,
                            isEnd = page.isEnd || page.items.isEmpty(),
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    update(kind) {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = if (firstPage && !silent) error.message ?: "消息加载失败" else it.error,
                        )
                    }
                }
            loading -= kind
        }
    }

    private fun loadSessions(firstPage: Boolean, silent: Boolean) {
        val kind = MessageKind.SESSION
        if (!loading.add(kind)) return
        if (!session.isLoggedIn()) {
            loading -= kind
            _state.update { it.copy(sessions = emptyList(), isSessionsLoading = false, sessionsLoaded = true) }
            return
        }
        if (firstPage) {
            if (!silent || _state.value.sessions.isEmpty()) {
                _state.update { it.copy(isSessionsLoading = true, sessionsError = null) }
            }
        } else {
            _state.update { it.copy(isSessionsLoadingMore = true) }
        }
        val endTs = if (firstPage) null else _state.value.sessionsEndTs
        viewModelScope.launch {
            imRepository.sessions(endTs)
                .onSuccess { (list, nextTs) ->
                    _state.update {
                        val merged = when {
                            !firstPage -> it.sessions + list
                            silent && it.sessions.isNotEmpty() -> {
                                val freshIds = list.mapTo(HashSet(list.size)) { s -> s.talkerId }
                                list + it.sessions.filter { old -> old.talkerId !in freshIds }
                            }
                            else -> list
                        }
                        it.copy(
                            sessions = merged,
                            isSessionsLoading = false,
                            isSessionsLoadingMore = false,
                            sessionsLoaded = true,
                            sessionsEndTs = if (silent && it.sessions.isNotEmpty()) it.sessionsEndTs else nextTs,
                            isSessionsEnd = if (silent && it.sessions.isNotEmpty()) it.isSessionsEnd else nextTs == null,
                            sessionsError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isSessionsLoading = false,
                            isSessionsLoadingMore = false,
                            sessionsError = if (firstPage && !silent) {
                                error.message ?: "私信加载失败"
                            } else {
                                it.sessionsError
                            },
                        )
                    }
                }
            loading -= kind
        }
    }

    private fun set(kind: MessageKind, state: MessageKindState) {
        _state.update { it.copy(kinds = it.kinds + (kind to state)) }
    }

    private fun update(kind: MessageKind, transform: (MessageKindState) -> MessageKindState) {
        _state.update {
            val current = it.of(kind)
            it.copy(kinds = it.kinds + (kind to transform(current)))
        }
    }
}
