package erl.bilico.feature.feed

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import erl.bilico.data.settings.HomeTabType
import erl.bilico.di.AppContainer
import erl.bilico.feature.live.LiveListScreen
import erl.bilico.feature.pgc.BangumiRankSection
import erl.bilico.feature.pgc.CinemaRankSection
import erl.bilico.feature.rank.RankingSection
import erl.bilico.ui.NavState
import erl.bilico.ui.SegmentSwitchMillis
import io.github.suqi8.coui.kmp.basic.FullPagePullToRefreshState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 推荐 tab 的顶部分类页：默认推荐、热门、排行榜、番剧、影视、直播，支持在设置里开关与排序。
 * 左右滑动翻页，胶囊点击也一样翻。
 *
 * 分段胶囊不在这里画：它要跟着标题行折进顶栏，得画在顶栏那一层才不会被玻璃盖住，
 * 所以 [segment]、[tabs] 与 [onSegment] 都由外壳持有。
 */
class FeedScrollStates(
    val recommend: LazyGridState,
    val hot: LazyGridState,
    val ranking: LazyGridState,
    val bangumi: LazyGridState,
    val cinema: LazyGridState,
    val live: LazyGridState,
) {
    fun stateFor(tab: HomeTabType): LazyGridState = when (tab) {
        HomeTabType.RECOMMEND -> recommend
        HomeTabType.HOT -> hot
        HomeTabType.RANKING -> ranking
        HomeTabType.BANGUMI -> bangumi
        HomeTabType.CINEMA -> cinema
        HomeTabType.LIVE -> live
    }
}

@Composable
fun rememberFeedScrollStates(): FeedScrollStates {
    val recommend = rememberLazyGridState()
    val hot = rememberLazyGridState()
    val ranking = rememberLazyGridState()
    val bangumi = rememberLazyGridState()
    val cinema = rememberLazyGridState()
    val live = rememberLazyGridState()
    return remember(recommend, hot, ranking, bangumi, cinema, live) {
        FeedScrollStates(recommend, hot, ranking, bangumi, cinema, live)
    }
}

@Composable
fun FeedScreen(
    container: AppContainer,
    nav: NavState,
    topInset: Dp,
    tabs: List<HomeTabType>,
    segment: Int,
    onSegment: (Int) -> Unit,
    onCollapse: (Float) -> Unit,
    pagerState: PagerState,
    refreshTick: Int,
    primaryTapTick: Int = 0,
    scrollStates: FeedScrollStates = rememberFeedScrollStates(),
) {
    val recommendState = scrollStates.recommend
    val hotState = scrollStates.hot
    val rankingState = scrollStates.ranking
    val bangumiState = scrollStates.bangumi
    val cinemaState = scrollStates.cinema
    val liveState = scrollStates.live
    val segmentTop = topInset + 8.dp

    // 点胶囊翻页；滑完一页再把选中态写回去。
    // 回写必须等翻页停稳：动画途中 currentPage 会先经过中间那页，写回去会把动画打断在半路。
    LaunchedEffect(segment, tabs.size) {
        val target = segment.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        if (pagerState.currentPage != target || abs(pagerState.currentPageOffsetFraction) > 0.0005f) {
            pagerState.animateScrollToPage(
                page = target,
                animationSpec = tween(durationMillis = SegmentSwitchMillis, easing = FastOutSlowInEasing),
            )
        }
    }
    LaunchedEffect(pagerState, tabs.size) {
        snapshotFlow { pagerState.settledPage.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)) }
            .distinctUntilChanged()
            .collect(onSegment)
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { tabs[it].id },
        ) { page ->
            val tab = tabs.getOrNull(page) ?: return@HorizontalPager
            val active = segment == page
            when (tab) {
                HomeTabType.RECOMMEND -> RecommendFeed(
                    container = container,
                    nav = nav,
                    topInset = segmentTop,
                    listState = recommendState,
                    active = active,
                    onCollapse = onCollapse,
                    refreshTick = refreshTick,
                    primaryTapTick = primaryTapTick,
                )

                HomeTabType.HOT -> HotFeedSection(
                    container = container,
                    nav = nav,
                    topInset = segmentTop,
                    listState = hotState,
                    active = active,
                    onCollapse = onCollapse,
                    refreshTick = refreshTick,
                    primaryTapTick = primaryTapTick,
                )

                HomeTabType.RANKING -> RankingSection(
                    container = container,
                    nav = nav,
                    topInset = segmentTop,
                    listState = rankingState,
                    active = active,
                    onCollapse = onCollapse,
                    refreshTick = refreshTick,
                    primaryTapTick = primaryTapTick,
                )

                HomeTabType.BANGUMI -> BangumiRankSection(
                    container = container,
                    nav = nav,
                    topInset = segmentTop,
                    gridState = bangumiState,
                    active = active,
                    onCollapse = onCollapse,
                    refreshTick = refreshTick,
                    primaryTapTick = primaryTapTick,
                    pagerScrolling = { pagerState.isScrollInProgress },
                )

                HomeTabType.CINEMA -> CinemaRankSection(
                    container = container,
                    nav = nav,
                    topInset = segmentTop,
                    gridState = cinemaState,
                    active = active,
                    onCollapse = onCollapse,
                    refreshTick = refreshTick,
                )

                HomeTabType.LIVE -> LiveListScreen(
                    container = container,
                    nav = nav,
                    topInset = segmentTop,
                    gridState = liveState,
                    active = active,
                    onCollapse = onCollapse,
                    refreshTick = refreshTick,
                    primaryTapTick = primaryTapTick,
                )
            }
        }
    }
}

/**
 * 底栏「推荐」按钮单击响应：
 * 未在顶部时先平滑回到顶部；已在顶部（或正在回顶途中再次点击）时直接触发下拉刷新。
 */
@Composable
fun HandleFeedPrimaryTap(
    primaryTapTick: Int,
    active: Boolean,
    isRefreshing: Boolean,
    gridState: LazyGridState,
    pullState: FullPagePullToRefreshState,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var scrollToTopJob by remember { mutableStateOf<Job?>(null) }
    val baseline = remember { mutableIntStateOf(primaryTapTick) }

    LaunchedEffect(primaryTapTick) {
        if (primaryTapTick == baseline.intValue) return@LaunchedEffect
        baseline.intValue = primaryTapTick
        if (!active || isRefreshing) return@LaunchedEffect
        val wasScrollingToTop = scrollToTopJob?.isActive == true
        val atTop = gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
        if (atTop || wasScrollingToTop) {
            scrollToTopJob?.cancel()
            scrollToTopJob = null
            if (!atTop) gridState.scrollToItem(0)
            pullState.snapToRefreshing { onRefresh() }
        } else {
            scrollToTopJob = scope.launch {
                try {
                    if (gridState.firstVisibleItemIndex > 6) {
                        gridState.scrollToItem(6)
                    }
                    gridState.animateScrollToItem(0)
                } finally {
                    if (scrollToTopJob === coroutineContext[Job]) {
                        scrollToTopJob = null
                    }
                }
            }
        }
    }
}

/** 分类胶囊占掉的一条高度；列表和下拉刷新都要把它让出来。 */
internal val FeedSegmentRowHeight = 44.dp

/** 热门与排行榜的列数：竖屏一律两列，和番剧榜对齐。 */
internal const val FeedColumns = 2
