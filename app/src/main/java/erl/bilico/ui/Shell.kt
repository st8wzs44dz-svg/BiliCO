package erl.bilico.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import erl.bilico.di.AppContainer
import erl.bilico.player.PlaybackBridge
import erl.bilico.feature.dynamic.DynamicFilters
import erl.bilico.feature.dynamic.DynamicPublishOverlay
import erl.bilico.feature.dynamic.DynamicScreen
import erl.bilico.feature.feed.FEED_SEGMENTS
import erl.bilico.feature.feed.FeedScreen
import erl.bilico.feature.me.MeScreen
import erl.bilico.feature.search.FeedTitleRow
import erl.bilico.feature.search.HomeSearchOverlay
import erl.bilico.feature.search.SearchSortMenuHost
import erl.bilico.feature.search.SearchUiState
import erl.bilico.feature.search.SearchViewModel
import erl.bilico.feature.search.showingResults
import io.github.suqi8.coui.kmp.anim.COUIInEase
import io.github.suqi8.coui.kmp.anim.COUIOutEase
import io.github.suqi8.coui.kmp.basic.LocalEdgeToEdge
import io.github.suqi8.coui.kmp.basic.LocalPlatformBottomBarMaterialEnabled
import io.github.suqi8.coui.kmp.basic.TabRowWithContour
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TopAppBarDefaults
import io.github.suqi8.coui.kmp.basic.rememberSearchResultsTransitionState
import io.github.suqi8.coui.kmp.blur.LocalBackdrop
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

/** 底栏切页的交叉淡入时长。 */
private const val MainTabFadeMillis = 220

/** 顶栏分段胶囊点击切页与番剧深浅色外壳过渡的同步时长。 */
internal const val SegmentSwitchMillis = 360

/** 搜索整屏面板淡入淡出时长（与 Cobo COUI `SearchPanelFadeMillis` 一致）。 */
private const val SearchPanelFadeMillis = 220

/** 顶栏标题行高度；列表滚动时标题就折这么高。 */
internal val TitleRowHeight = 44.dp

/** 底栏三格：推荐、动态、我的。直播收进推荐页顶部的分段胶囊里。 */
private val tabTitles = listOf("推荐", "动态", "我的")

/**
 * 分段胶囊轨道左右各留的边距，和下面卡片列表的 12dp 缩进对齐。
 * 列表跟着系统密度走，顶栏钉在 360dp 密度上，所以这是「页面 dp」，用的时候要换算。
 */
private val CapsuleSideMargin = 12.dp

@Composable
fun MainShell(nav: NavState, container: AppContainer) {
    val tab = nav.mainTab
    val holder = rememberSaveableStateHolder()
    val backdrop = rememberLayerBackdrop()
    val searchBackdrop = rememberLayerBackdrop()
    val topBarBounds = remember { TopBarBounds() }
    var feedCollapse by remember { mutableFloatStateOf(0f) }
    var dynamicCollapse by remember { mutableFloatStateOf(0f) }
    var dynamicFilterIndex by rememberSaveable { mutableIntStateOf(0) }
    var feedRefreshTick by remember { mutableIntStateOf(0) }
    var feedPrimaryTapTick by remember { mutableIntStateOf(0) }
    var dynamicRefreshTick by remember { mutableIntStateOf(0) }
    val userDarkMode by container.settings.darkMode.collectAsStateWithLifecycle()

    val searchViewModel: SearchViewModel = viewModel { SearchViewModel(container.search) }
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()
    val searchHistory by container.settings.searchHistory.collectAsStateWithLifecycle()
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val panelProgress = remember { Animatable(0f) }
    val panelAlpha = remember { Animatable(0f) }
    val showingResults = searchState.showingResults()
    val panelMounted by remember {
        derivedStateOf {
            searchExpanded || panelProgress.value > 0.001f || panelAlpha.value > 0.001f
        }
    }
    val feedPanelCovering = tab == 0 && panelMounted
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val openFeedSearch: () -> Unit = {
        searchViewModel.reset()
        searchExpanded = true
    }
    val cancelFeedSearch: () -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus()
        searchExpanded = false
    }
    val submitHomeSearch: (String) -> Unit = { raw ->
        val keyword = raw.trim()
        if (keyword.isNotEmpty()) {
            keyboard?.hide()
            focusManager.clearFocus()
            container.settings.addSearchHistory(keyword)
            searchViewModel.submit(keyword)
        }
    }

    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            searchViewModel.loadHot()
            panelProgress.snapTo(1f)
            panelAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = SearchPanelFadeMillis, easing = COUIInEase),
            )
        } else if (panelProgress.value > 0f || panelAlpha.value > 0f) {
            panelAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = SearchPanelFadeMillis, easing = COUIOutEase),
            )
            panelProgress.snapTo(0f)
            if (!searchExpanded) {
                searchViewModel.reset()
            }
        }
    }

    LaunchedEffect(tab) {
        if (tab != 0 && (searchExpanded || panelProgress.value > 0f || panelAlpha.value > 0f)) {
            keyboard?.hide()
            focusManager.clearFocus()
            searchExpanded = false
            panelProgress.snapTo(0f)
            panelAlpha.snapTo(0f)
            searchViewModel.reset()
        }
    }

    // 排序菜单只属于结果态：退回发现/建议页或清空提交词时收起，避免漂浮在没有锚点的位置。
    LaunchedEffect(showingResults) {
        if (!showingResults) sortMenuExpanded = false
    }

    val searchTransition = rememberSearchResultsTransitionState(
        showingResults = showingResults,
        loading = searchState.isLoading,
        queryKey = searchState.submitted,
    )

    val topBarHeight = pinnedHeight(TitleRowHeight)
    val account by container.session.account.collectAsStateWithLifecycle()
    var dynamicComposerOpen by remember { mutableStateOf(false) }
    var segment by rememberSaveable { mutableIntStateOf(0) }
    val feedPager = rememberPagerState(initialPage = segment, pageCount = { FEED_SEGMENTS.size })
    val dynamicPager = rememberPagerState(initialPage = dynamicFilterIndex, pageCount = { DynamicFilters.size })

    val titleHeightPx = with(LocalDensity.current) { TitleRowHeight.toPx() }
    val collapsePx = when (tab) {
        0 -> feedCollapse
        1 -> dynamicCollapse
        else -> 0f
    }
    val rawCollapseTarget = (collapsePx / titleHeightPx).coerceIn(0f, 1f)
    // 搜索展开期间锁定标题行不折叠；收起后恢复跟踪列表滚动。
    val collapseTarget = if (tab == 0 && searchExpanded) 0f else rawCollapseTarget
    val collapseShown = remember { Animatable(collapseTarget) }
    var wasSearchExpanded by remember { mutableStateOf(searchExpanded) }
    LaunchedEffect(collapseTarget, searchExpanded) {
        val searchTransition = (wasSearchExpanded != searchExpanded) || (searchExpanded && collapseShown.value > 0f)
        wasSearchExpanded = searchExpanded
        if (abs(collapseTarget - collapseShown.value) > 0.6f || searchTransition) {
            collapseShown.animateTo(collapseTarget, tween(MainTabFadeMillis))
        } else {
            collapseShown.snapTo(collapseTarget)
        }
    }
    val collapsed: () -> Float = { collapseShown.value }

    val onMainTab by remember(nav) {
        derivedStateOf { nav.stack.lastOrNull { !it.exiting }?.route == Route.Main }
    }
    // 首页带着融合球点开新视频时，进场这一秒里底栏不该先把融合球收起来再放回去：
    // 那三个弹簧会把整条底栏连同它背后的模糊层一起拖着重画。
    val rawOrbVisible = nav.minimizedEntry != null || nav.isMinimizingToOrb
    val enteringOverMain by remember(nav) {
        derivedStateOf {
            val topEntry = nav.stack.lastOrNull { !it.exiting }
            nav.stack.count { !it.exiting } == 2 && topEntry != null && topEntry.expand &&
                !topEntry.previewing && topEntry.progress.value < 1f
        }
    }
    var settledOrbVisible by remember { mutableStateOf(rawOrbVisible) }
    SideEffect { if (!enteringOverMain) settledOrbVisible = rawOrbVisible }

    val userIsDark = isDarkTheme(userDarkMode)
    val userPageBg = pageContainerColor()
    val tabBangumiWeight = animateFloatAsState(
        targetValue = if (tab == FEED_TAB_INDEX && !feedPanelCovering) 1f else 0f,
        animationSpec = tween(MainTabFadeMillis),
        label = "bangumiTabWeight",
    )
    val shellScope = rememberCoroutineScope()
    var segmentTapDriven by remember { mutableStateOf(false) }
    var segmentTapCurrent by remember { mutableFloatStateOf(if (segment == 2) 1f else 0f) }
    val segmentTapProgress = remember { Animatable(if (segment == 2) 1f else 0f) }
    var segmentTapJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(feedPager) {
        feedPager.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                segmentTapJob?.cancel()
                segmentTapJob = null
                segmentTapDriven = false
            }
        }
    }

    // 滑动/惯性阶段直接由 visiblePagesInfo 的真实像素位移还原连续页位置，
    // 规避 PagerState 在 animateScrollToPage / 惯性松手瞬间先把 currentPage 跳到目标页并把 offsetFraction 夹在 ±0.5 导致的硬切；
    // 点击顶栏分段胶囊（尤其跨页 0 <-> 2）则由独立的 segmentTapProgress 全程平滑过渡。
    val pagerBangumiProgress: () -> Float = {
        val layoutInfo = feedPager.layoutInfo
        val pageSize = layoutInfo.pageSize
        val firstVisible = layoutInfo.visiblePagesInfo.firstOrNull()
        val pagePos = if (pageSize > 0 && firstVisible != null) {
            firstVisible.index - firstVisible.offset.toFloat() / pageSize.toFloat()
        } else {
            feedPager.currentPage + feedPager.currentPageOffsetFraction
        }
        (1f - abs(pagePos - 2f)).coerceIn(0f, 1f)
    }
    val rawBangumiProgress: () -> Float = {
        if (segmentTapDriven) segmentTapCurrent else pagerBangumiProgress()
    }
    val bangumiProgress: () -> Float = {
        rawBangumiProgress() * tabBangumiWeight.value
    }
    val onCapsuleSegment: (Int) -> Unit = { target ->
        if (target != segment || segmentTapDriven) {
            val startProgress = rawBangumiProgress()
            val targetProgress = if (target == 2) 1f else 0f
            segmentTapJob?.cancel()
            segmentTapCurrent = startProgress
            segmentTapDriven = true
            segment = target
            segmentTapJob = shellScope.launch {
                segmentTapProgress.snapTo(startProgress)
                if (abs(targetProgress - startProgress) > 0.001f) {
                    segmentTapProgress.animateTo(
                        targetValue = targetProgress,
                        animationSpec = tween(
                            durationMillis = SegmentSwitchMillis,
                            easing = FastOutSlowInEasing,
                        ),
                    ) {
                        segmentTapCurrent = value
                    }
                } else {
                    segmentTapCurrent = targetProgress
                }
                snapshotFlow { !feedPager.isScrollInProgress && feedPager.settledPage == target }
                    .first { it }
                segmentTapDriven = false
            }
        }
    }
    val baseChromeVisible = remember(feedPager, userIsDark) {
        derivedStateOf { userIsDark || bangumiProgress() < 0.999f }
    }
    val darkChromeVisible = remember(feedPager, userIsDark) {
        derivedStateOf { !userIsDark && bangumiProgress() > 0.001f }
    }
    // 状态栏图标反色等滑动与胶囊点击过渡停稳后再同步，避免动画最敏感帧触发 SystemUI Binder IPC 与全局重组。
    LaunchedEffect(nav, feedPager, tab, feedPanelCovering) {
        snapshotFlow {
            when {
                tab != FEED_TAB_INDEX || feedPanelCovering -> false
                feedPager.isScrollInProgress || segmentTapDriven -> null
                else -> feedPager.settledPage == 2
            }
        }
            .distinctUntilChanged()
            .collect { darkBars ->
                if (darkBars != null) nav.mainForceDark = darkBars
            }
    }
    DisposableEffect(nav) {
        onDispose { nav.mainForceDark = false }
    }

    val baseChromeAlpha: () -> Float = { if (userIsDark) 1f else (1f - bangumiProgress()) }
    val darkChromeAlpha: () -> Float = bangumiProgress
    // 顶栏/底栏边界与融合球落点仅在分页停稳后切换上报方，拖动与胶囊过渡全程不触发 MainShell 重组。
    val settledBangumi = !userIsDark && tab == FEED_TAB_INDEX && segment == 2 && !segmentTapDriven && !feedPanelCovering
    val baseReportsBounds = !settledBangumi
    val darkReportsBounds = settledBangumi

    Box(Modifier.fillMaxSize()) {
        // Layer 1: 页面主内容。保持用户原有全局主题，底层用纯 GPU 影院暗幕无缝渐暗，消除横滑黑白硬切竖缝。
        CompositionLocalProvider(LocalTopBarBounds provides topBarBounds) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {}
                    .layerBackdropIf(BlurEffectsEnabled, backdrop)
                    .background(userPageBg)
                    .then(if (feedPanelCovering) Modifier.clearAndSetSemantics {} else Modifier),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = bangumiProgress() }
                        .background(BangumiCinemaBackground),
                )
                Crossfade(
                    targetState = tab,
                    animationSpec = tween(MainTabFadeMillis),
                    modifier = Modifier.fillMaxSize(),
                    label = "mainTabPage",
                ) { page ->
                    holder.SaveableStateProvider(page) {
                        when (page) {
                            0 -> FeedScreen(
                                container = container,
                                nav = nav,
                                topInset = topBarHeight,
                                segment = segment,
                                onSegment = { segment = it },
                                onCollapse = { feedCollapse = it },
                                pagerState = feedPager,
                                refreshTick = feedRefreshTick,
                                primaryTapTick = feedPrimaryTapTick,
                            )

                            1 -> DynamicScreen(
                                container = container,
                                nav = nav,
                                topInset = topBarHeight,
                                filterIndex = dynamicFilterIndex,
                                onFilterSelect = { dynamicFilterIndex = it },
                                pagerState = dynamicPager,
                                onCollapse = { dynamicCollapse = it },
                                refreshTick = dynamicRefreshTick,
                            )

                            else -> MeScreen(
                                container = container,
                                nav = nav,
                                topInset = topBarHeight,
                            )
                        }
                    }
                }
            }
        }

        // Layer 2: 顶栏模糊材质层 + 分段胶囊行（浅/深两套常驻热挂载，不可见时跳过 place 零 GPU 与零触摸开销）
        val renderUnderlay: @Composable (Color, State<Boolean>, () -> Float, Boolean) -> Unit =
            { containerColor, visibleState, alphaProvider, reportBounds ->
                CompositionLocalProvider(
                    LocalBackdrop provides if (BlurEffectsEnabled) backdrop else null,
                    LocalEdgeToEdge provides true,
                ) {
                    PinnedBarDensity {
                        MainBarUnderlay(
                            tab = tab,
                            collapsed = collapsed,
                            feedSelectedSegment = if (segmentTapDriven) segment else feedPager.targetPage,
                            onSegment = onCapsuleSegment,
                            dynamicPager = dynamicPager,
                            onDynamicFilter = { dynamicFilterIndex = it },
                            onFeedReselect = { feedRefreshTick++ },
                            onDynamicReselect = { dynamicRefreshTick++ },
                            onDoubleTap = {
                                when (tab) {
                                    0 -> feedRefreshTick++
                                    1 -> dynamicRefreshTick++
                                }
                            },
                            searchExpanded = searchExpanded,
                            panelCovering = feedPanelCovering,
                            bounds = topBarBounds,
                            reportBounds = reportBounds,
                            containerColor = containerColor,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .chromeLayerVisibility(visible = visibleState, alphaProvider = alphaProvider)
                                .then(if (reportBounds) Modifier else Modifier.clearAndSetSemantics {}),
                        )
                    }
                }
            }
        renderUnderlay(userPageBg, baseChromeVisible, baseChromeAlpha, baseReportsBounds)
        if (!userIsDark) {
            BiliCoTheme(darkMode = 2) {
                renderUnderlay(BangumiCinemaBackground, darkChromeVisible, darkChromeAlpha, darkReportsBounds)
            }
        }

        // Layer 3: 悬浮底部导航栏（同样热挂载 + placement 隐藏，消除切入切出首帧冷组合掉帧）
        val context = LocalContext.current
        val renderBottomBar: @Composable (State<Boolean>, () -> Float, Boolean) -> Unit =
            { visibleState, alphaProvider, reportBounds ->
                CompositionLocalProvider(
                    LocalBackdrop provides if (BlurEffectsEnabled) backdrop else null,
                    LocalPlatformBottomBarMaterialEnabled provides false,
                ) {
                    PinnedBarDensity {
                        FloatingBottomBar(
                            tab = tab,
                            onTab = { target -> if (!feedPanelCovering) nav.setTab(target) },
                            onReselect = { index ->
                                if (!feedPanelCovering) {
                                    when (index) {
                                        0 -> feedRefreshTick++
                                        1 -> dynamicRefreshTick++
                                    }
                                }
                            },
                            onPrimaryReselect = {
                                if (!feedPanelCovering) feedPrimaryTapTick++
                            },
                            barBounds = if (!feedPanelCovering && reportBounds) topBarBounds else null,
                            orbVisible = if (enteringOverMain) settledOrbVisible else rawOrbVisible,
                            leftOrbVisible = tab == DYNAMIC_TAB_INDEX,
                            onLeftOrbClick = {
                                if (!feedPanelCovering) {
                                    if (account == null) nav.push(Route.Login) else dynamicComposerOpen = true
                                }
                            },
                            orbOrigin = if (reportBounds) nav.orbOrigin else null,
                            orbArtworkUrl = PlaybackBridge.currentArtworkUrl,
                            orbIsPlaying = PlaybackBridge.currentIsPlaying && onMainTab,
                            onOrbClick = { if (!feedPanelCovering) nav.restoreMinimized() },
                            onOrbTogglePlay = { PlaybackBridge.togglePlayPause(context) },
                            onOrbStop = { nav.dismissMinimized(pausePlayback = true) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .chromeLayerVisibility(visible = visibleState, alphaProvider = alphaProvider)
                                .then(
                                    if (feedPanelCovering || !reportBounds) {
                                        Modifier.clearAndSetSemantics {}
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
        renderBottomBar(baseChromeVisible, baseChromeAlpha, baseReportsBounds)
        if (!userIsDark) {
            BiliCoTheme(darkMode = 2) {
                renderBottomBar(darkChromeVisible, darkChromeAlpha, darkReportsBounds)
            }
        }

        // Layer 4: 首页搜索整屏面板（从屏幕顶 0 铺满到屏幕底部，位于常态顶栏与底栏之上、搜索胶囊之下）
        if (feedPanelCovering) {
            HomeSearchOverlay(
                topBarHeight = topBarHeight,
                searchBackdrop = searchBackdrop,
                searchExpanded = searchExpanded,
                panelProgress = { panelProgress.value },
                panelAlpha = { panelAlpha.value },
                state = searchState,
                transitionState = searchTransition,
                history = searchHistory,
                viewModel = searchViewModel,
                container = container,
                nav = nav,
                barBounds = topBarBounds,
                onSelectKeyword = submitHomeSearch,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Layer 4.5: 搜索面板之上的融合球。面板铺满整屏，会盖住底栏上的那颗，
        // 从搜索结果的视频返回时球就被面板吞掉。这里补一颗，面板收起后交还底栏那颗。
        if (feedPanelCovering && (nav.minimizedEntry != null || nav.isMinimizingToOrb)) {
            nav.orbOrigin?.let { origin ->
                CompositionLocalProvider(
                    LocalBackdrop provides if (BlurEffectsEnabled) backdrop else null,
                ) {
                    SecondaryPlaybackOrbOverlay(nav = nav, orbOrigin = origin)
                }
            }
        }

        // Layer 5: 标题行与展开后的 TopAppBarSearchBar（在最顶层，确保搜索胶囊和“取消”按钮不被面板遮挡）
        val renderTitleRow: @Composable (State<Boolean>, () -> Float, Boolean) -> Unit =
            { visibleState, alphaProvider, reportBounds ->
                CompositionLocalProvider(
                    LocalBackdrop provides if (BlurEffectsEnabled) {
                        if (feedPanelCovering) searchBackdrop else backdrop
                    } else {
                        null
                    },
                    LocalEdgeToEdge provides true,
                ) {
                    PinnedBarDensity {
                        MainTitleRow(
                            tab = tab,
                            nav = nav,
                            collapsed = collapsed,
                            avatarUrl = account?.face,
                            onAvatarClick = { nav.setTab(ME_TAB_INDEX) },
                            searchState = searchState,
                            onQueryChange = searchViewModel::onQueryChange,
                            searchExpanded = searchExpanded,
                            onSearchOpen = openFeedSearch,
                            onSearchCancel = cancelFeedSearch,
                            onSearchSubmit = submitHomeSearch,
                            showingResults = searchTransition.filtersVisible,
                            onTabChange = searchViewModel::setTab,
                            onSortMenuOpen = { sortMenuExpanded = true },
                            panelAlpha = { panelAlpha.value },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .chromeLayerVisibility(visible = visibleState, alphaProvider = alphaProvider)
                                .then(
                                    if (reportBounds && feedPanelCovering) {
                                        Modifier.reportTopBarBottom(topBarBounds)
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(if (reportBounds) Modifier else Modifier.clearAndSetSemantics {}),
                        )
                        // 排序圆钮的菜单宿主：圆钮画在 FeedTitleRow 的胶囊行里，这里只把锚点落到它身上。
                        if (reportBounds && feedPanelCovering && showingResults) {
                            SearchSortMenuHost(
                                expanded = sortMenuExpanded,
                                onExpandedChange = { sortMenuExpanded = it },
                                state = searchState,
                                viewModel = searchViewModel,
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                        }
                    }
                }
            }
        renderTitleRow(baseChromeVisible, baseChromeAlpha, baseReportsBounds)
        if (!userIsDark) {
            BiliCoTheme(darkMode = 2) {
                renderTitleRow(darkChromeVisible, darkChromeAlpha, darkReportsBounds)
            }
        }

        DynamicPublishOverlay(
            container = container,
            composerOpen = dynamicComposerOpen,
            onDismissComposer = { dynamicComposerOpen = false },
            onPublished = { dynamicRefreshTick++ },
        )
    }

    val inHomeSearchResults =
        nav.stack.count { !it.exiting } == 1 && tab == 0 && searchExpanded && searchState.submitted.isNotBlank()
    if (inHomeSearchResults) {
        BackHandler {
            searchViewModel.onQueryChange("")
        }
    }
}

private val ChromeShadowOutsetDp = 56.dp

/**
 * 连同溢出组件布局边界的悬浮外阴影一起做透明度过渡。
 *
 * `graphicsLayer { alpha < 1f }` 即便 `clip = false`，在 `0f < alpha < 1f` 期间也会隐式分配一张
 * 按节点布局边界（如 44dp 顶栏高度）裁切的离屏层，把右上角圆形玻璃按钮向下扩散的外阴影硬截成
 * 半透明灰色矩形色块。用外扩 `56.dp` 阴影余量的 `saveLayer` 替代，静止态 `alpha >= 1f` 直接绘制零离屏开销。
 */
internal fun Modifier.unclippedShadowOpacity(alphaProvider: () -> Float): Modifier = drawWithCache {
    val paint = Paint()
    val outset = ceil(ChromeShadowOutsetDp.toPx())
    val bounds = Rect(-outset, -outset, size.width + outset, size.height + outset)
    onDrawWithContent {
        val opacity = alphaProvider().coerceIn(0f, 1f)
        if (opacity >= 0.999f) {
            drawContent()
        } else if (opacity > 0.001f) {
            paint.alpha = opacity
            drawContext.canvas.saveLayer(bounds, paint)
            try {
                drawContent()
            } finally {
                drawContext.canvas.restore()
            }
        }
    }
}

/**
 * 保持浅/深双层外壳始终热挂载与已测量，避免滑动切入/切出首帧冷组合或卸载 COUI 玻璃组件导致掉帧；
 * 不可见时在 placement 阶段跳过 place，彻底免除 GPU 绘制、Backdrop 模糊采样与触摸命中测试，
 * 且状态读取完全隔离在 placement / draw 阶段，不触发外壳任何重组。
 */
private fun Modifier.chromeLayerVisibility(
    visible: State<Boolean>,
    alphaProvider: () -> Float,
): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            if (visible.value) {
                placeable.place(0, 0)
            }
        }
    }
    .unclippedShadowOpacity(alphaProvider)

@Composable
private fun MainBarUnderlay(
    tab: Int,
    collapsed: () -> Float,
    feedSelectedSegment: Int,
    onSegment: (Int) -> Unit,
    dynamicPager: PagerState,
    onDynamicFilter: (Int) -> Unit,
    onFeedReselect: () -> Unit,
    onDynamicReselect: () -> Unit,
    onDoubleTap: () -> Unit,
    searchExpanded: Boolean,
    panelCovering: Boolean,
    bounds: TopBarBounds,
    reportBounds: Boolean,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val pageBarHeight = statusBarTop + TitleRowHeight
    val titleHeightPx = with(LocalDensity.current) { TitleRowHeight.toPx() }
    val doubleTap = Modifier.pointerInput(onDoubleTap) {
        detectTapGestures(onDoubleTap = { onDoubleTap() })
    }

    Box(modifier) {
        AppBarSurface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, 0) }
                .then(if (panelCovering || !reportBounds) Modifier else Modifier.reportTopBarBottom(bounds)),
            containerColor = containerColor,
            scrollFraction = { 1f },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(pageBarHeight)
                    .then(doubleTap),
            )
        }

        val capsuleLabels = when (tab) {
            0 -> FEED_SEGMENTS
            1 -> DynamicFilters.map { it.second }
            else -> null
        }
        if (capsuleLabels != null) {
            AnimatedVisibility(
                visible = !searchExpanded || tab != 0,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(150)),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = statusBarTop)
                        .then(if (panelCovering) Modifier.clearAndSetSemantics {} else Modifier),
                ) {
                    Spacer(Modifier.height(TitleRowHeight))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .graphicsLayer { translationY = -collapsed() * titleHeightPx },
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        val barDensity = LocalDensity.current.density
                        val pageDensity = LocalContext.current.resources.displayMetrics.density
                        // 高光块跟着翻页的落点走：拖动越过翻页阈值、或者松手后的惯性还没停，
                        // 它就开始滑，不等停稳后回写选中态。点标签时浅/深两套胶囊同步切到目标段。
                        val selectedTabIndex = if (tab == 0) feedSelectedSegment else dynamicPager.targetPage
                        TabRowWithContour(
                            tabs = capsuleLabels,
                            selectedTabIndex = selectedTabIndex.coerceIn(0, capsuleLabels.lastIndex),
                            onTabSelected = if (tab == 0) onSegment else onDynamicFilter,
                            onTabReselected = when (tab) {
                                0 -> { _: Int -> onFeedReselect() }
                                1 -> { _: Int -> onDynamicReselect() }
                                else -> null
                            },
                            commitOnRelease = true,
                            backdrop = LocalBackdrop.current,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = CapsuleSideMargin * (pageDensity / barDensity)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainTitleRow(
    tab: Int,
    nav: NavState,
    collapsed: () -> Float,
    avatarUrl: String?,
    onAvatarClick: () -> Unit,
    searchState: SearchUiState,
    onQueryChange: (String) -> Unit,
    searchExpanded: Boolean,
    onSearchOpen: () -> Unit,
    onSearchCancel: () -> Unit,
    onSearchSubmit: (String) -> Unit,
    showingResults: Boolean,
    onTabChange: (Int) -> Unit,
    onSortMenuOpen: () -> Unit,
    panelAlpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = tab,
        animationSpec = tween(MainTabFadeMillis),
        modifier = modifier,
        label = "mainTitleRow",
    ) { page ->
        if (page == 0) {
            FeedTitleRow(
                searchState = searchState,
                onQueryChange = onQueryChange,
                searchExpanded = searchExpanded,
                onSearchOpen = onSearchOpen,
                onSearchCancel = onSearchCancel,
                onSearchSubmit = onSearchSubmit,
                avatarUrl = avatarUrl,
                onAvatarClick = onAvatarClick,
                nav = nav,
                collapsed = collapsed,
                showingResults = showingResults,
                onTabChange = onTabChange,
                onSortMenuOpen = onSortMenuOpen,
                panelAlpha = panelAlpha,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PageTitleBar(
                title = tabTitles.getOrElse(page) { "" },
                collapsed = collapsed,
                actions = { TabActions(page, nav, onSearch = {}) },
            )
        }
    }
}
