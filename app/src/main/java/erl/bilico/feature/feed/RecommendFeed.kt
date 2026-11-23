package erl.bilico.feature.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BlackholeDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import erl.bilico.data.model.FeedVideo
import erl.bilico.data.util.Formatters
import erl.bilico.di.AppContainer
import erl.bilico.ui.LocalTopBarBounds
import erl.bilico.ui.NavState
import erl.bilico.ui.Route
import erl.bilico.ui.TitleRowHeight
import erl.bilico.ui.asRowScrollState
import erl.bilico.ui.attachToRow
import erl.bilico.ui.components.EmptyState
import erl.bilico.ui.components.ErrorState
import erl.bilico.ui.components.FullPageRefreshHoldMillis
import erl.bilico.ui.components.FullPageRefreshTexts
import erl.bilico.ui.components.LoadingState
import erl.bilico.ui.components.navBottomPadding
import erl.bilico.ui.expandOrigin
import erl.bilico.ui.pageContainerColor
import erl.bilico.ui.rememberExpandOrigin
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.BindScrollToTop
import io.github.suqi8.coui.kmp.basic.FullPagePullToRefresh
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.fullPagePullToRefreshColors
import io.github.suqi8.coui.kmp.basic.rememberFullPagePullToRefreshState
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 只预取视口前方两行，限制后台请求与当前可见图片的竞争。 */
private const val CoverPrefetchRows = 2
private const val CoverPrefetchParallelism = 2
private const val FeedCardContentType = "feed-card"

private fun LazyGridLayoutInfo.visibleVideoCards() = visibleItemsInfo.filter { item ->
    item.contentType == FeedCardContentType &&
        item.offset.y < viewportEndOffset && item.offset.y + item.size.height > viewportStartOffset
}

/** 刷新拼接线的固定 key，重排时不会和视频卡片抢位置。 */
private const val RefreshDividerKey = "feed-refresh-divider"

/** 刷新换批时旧列表淡出、新列表淡入的时长。 */
private const val SwapFadeOutMillis = 90
private const val SwapFadeInMillis = 150

/** 列表当前真正画出来的那一批；刷新结果要等淡出完才换进来。 */
private data class ShownFeed(
    val items: List<FeedVideo>,
    val inserted: Int,
    val serial: Int,
)

/** 推荐分段：原来的首页推荐列表，外面那层分段胶囊归 [FeedScreen]。 */
@Composable
@OptIn(ExperimentalCoilApi::class)
internal fun RecommendFeed(
    container: AppContainer,
    nav: NavState,
    topInset: Dp,
    listState: LazyGridState,
    active: Boolean,
    onCollapse: (Float) -> Unit,
    refreshTick: Int,
    primaryTapTick: Int = 0,
) {
    BindScrollToTop(listState, enabled = active)
    val viewModel: FeedViewModel = viewModel { FeedViewModel(container.feed, container.settings) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val blocks by container.blocks.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pullState = rememberFullPagePullToRefreshState()
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val titleHeightPx = with(density) { TitleRowHeight.toPx() }
    val context = LocalContext.current

    val coverAspect by container.settings.feed.coverAspect.collectAsStateWithLifecycle()
    val columns by container.settings.feed.columns.collectAsStateWithLifecycle()
    val gridSpacing = 5.dp
    val prefetchDistance = maxOf(6, columns * 3)
    val swapCoverCount = maxOf(8, columns * 4)
    val coverWidthPx = remember(screenWidthDp, density, columns, gridSpacing) {
        val totalGap = 10.dp + gridSpacing * (columns - 1)
        val maxPx = if (columns <= 1) 1080 else 600
        with(density) { ((screenWidthDp.dp - totalGap) / columns.toFloat()).roundToPx() }
            .coerceIn(1, maxPx)
    }
    val coverHeightPx = remember(coverWidthPx, coverAspect) {
        (coverWidthPx / coverAspect.ratio).toInt()
    }

    // 刷新结果不直接换上：旧列表原地淡出，看不见时换成新批次并回到第一条，再淡入。
    // 这样不会先在顶上冒出拼接占位、再从中间闪回顶部。
    var shown by remember {
        mutableStateOf(ShownFeed(state.items, state.refreshInsertedCount, state.refreshSerial))
    }
    val listAlpha = remember { Animatable(1f) }
    LaunchedEffect(state.items, state.refreshSerial) {
        val next = ShownFeed(state.items, state.refreshInsertedCount, state.refreshSerial)
        if (next.serial == shown.serial || shown.items.isEmpty()) {
            shown = next
            listAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        val loader = SingletonImageLoader.get(context)
        next.items.take(swapCoverCount).forEach { video ->
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(Formatters.sizedImageUrl(video.cover, coverWidthPx, coverHeightPx))
                    .size(coverWidthPx, coverHeightPx)
                    .build(),
            )
        }
        listAlpha.animateTo(0f, tween(SwapFadeOutMillis))
        shown = next
        listState.scrollToItem(0)
        listAlpha.animateTo(1f, tween(SwapFadeInMillis))
    }

    val imageWorkActive = active && nav.stack.lastOrNull()?.route == Route.Main
    val batchSettled = shown.serial == state.refreshSerial
    val settledCovers = remember { mutableStateMapOf<String, String>() }
    val visibleKeys by remember(listState) {
        derivedStateOf(structuralEqualityPolicy()) {
            listState.layoutInfo.visibleVideoCards().mapTo(HashSet()) { it.key }
        }
    }
    LaunchedEffect(imageWorkActive, batchSettled, listState, context, coverWidthPx, coverHeightPx, columns) {
        if (!imageWorkActive || !batchSettled || coverWidthPx <= 0 || coverHeightPx <= 0) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        val permits = Semaphore(CoverPrefetchParallelism)
        val requests = mutableMapOf<String, Job>()
        val count = CoverPrefetchRows * columns
        snapshotFlow {
            val feed = shown
            val visible = listState.layoutInfo.visibleVideoCards()
            val hasDivider = feed.inserted in 1 until feed.items.size
            fun cardIndex(index: Int) = if (hasDivider && index > feed.inserted) index - 1 else index
            val visibleCoversSettled = visible.all { row ->
                val video = feed.items.getOrNull(cardIndex(row.index))
                video != null && video.bvid == row.key && settledCovers[video.bvid] == video.cover
            }
            if (visible.isEmpty() || !visibleCoversSettled) {
                return@snapshotFlow feed.items to IntRange.EMPTY
            }
            val range = if (listState.lastScrolledBackward) {
                val first = cardIndex(visible.first().index)
                first - 1 downTo maxOf(0, first - count)
            } else {
                val last = cardIndex(visible.last().index)
                last + 1 until minOf(feed.items.size, last + 1 + count)
            }
            feed.items to range
        }.collect { (items, indices) ->
            val urls = indices.mapTo(linkedSetOf()) { index ->
                Formatters.sizedImageUrl(items[index].cover, coverWidthPx, coverHeightPx)
            }
            requests.entries.removeAll { (url, job) ->
                if (url in urls) return@removeAll false
                job.cancel()
                true
            }
            urls.forEach { url ->
                requests.getOrPut(url) {
                    launch {
                        permits.withPermit {
                            loader.execute(
                                ImageRequest.Builder(context)
                                    .data(url)
                                    .memoryCachePolicy(CachePolicy.DISABLED)
                                    .decoderFactory(BlackholeDecoder.Factory())
                                    .build(),
                            )
                        }
                    }
                }
            }
        }
    }

    // 只有自己是当前页时才报折叠量：翻走时收起来，翻回来按列表实际位置重新报一次。
    LaunchedEffect(listState, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            if (first == null || first.index > 0) titleHeightPx
            else (-first.offset.y).toFloat().coerceIn(0f, titleHeightPx)
        }
            .distinctUntilChanged()
            .collect(onCollapse)
    }

    // 双击底栏当前 Tab / 双击分段标题：直接停在被下拉位置刷新，回顶对齐该位置，刷新完再回顶。
    // 切走底栏会把这一页拆掉。进来时先记住当时的计数，回来重组不会把上次那次再刷一遍。
    val refreshBaseline = remember { mutableIntStateOf(refreshTick) }
    LaunchedEffect(refreshTick) {
        if (refreshTick == refreshBaseline.intValue || !active) return@LaunchedEffect
        pullState.snapToRefreshing { viewModel.refresh() }
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            if (listState.firstVisibleItemIndex > 6) {
                listState.scrollToItem(6)
            }
            listState.animateScrollToItem(0)
        }
    }

    HandleFeedPrimaryTap(
        primaryTapTick = primaryTapTick,
        active = active,
        isRefreshing = state.isRefreshing,
        gridState = listState,
        pullState = pullState,
        onRefresh = { viewModel.refresh() },
    )

    LaunchedEffect(listState, prefetchDistance) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val size = viewModel.state.value.items.size
                if (lastVisible != null && size > 0 && lastVisible >= size - prefetchDistance) {
                    viewModel.loadMore()
                }
            }
    }

    FullPagePullToRefresh(
        isRefreshing = state.isRefreshing,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize(),
        state = pullState,
        contentPadding = PaddingValues(top = topInset + FeedSegmentRowHeight),
        colors = fullPagePullToRefreshColors(backgroundColor = androidx.compose.ui.graphics.Color.Transparent),
        refreshTexts = FullPageRefreshTexts,
        completeHoldDurationMillis = FullPageRefreshHoldMillis,
    ) {
        LazyVerticalGrid(
            state = listState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .graphicsLayer { alpha = listAlpha.value },
            contentPadding = PaddingValues(
                start = 5.dp,
                top = topInset + FeedSegmentRowHeight,
                end = 5.dp,
                bottom = 66.dp + navBottomPadding(),
            ),
            horizontalArrangement = Arrangement.spacedBy(gridSpacing),
            verticalArrangement = Arrangement.spacedBy(gridSpacing),
        ) {
            if (shown.items.isEmpty()) {
                item(key = "state", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth()) {
                        when {
                            state.isInitialLoading -> LoadingState()
                            state.error != null -> ErrorState(state.error.orEmpty()) {
                                scope.launch { viewModel.refresh() }
                            }

                            else -> EmptyState("暂时没有推荐内容")
                        }
                    }
                }
            }
            val inserted = shown.inserted.coerceIn(0, shown.items.size)
            val freshItems = shown.items.take(inserted).filter { blocks.shouldShowFeedVideo(it) }
            val olderItems = shown.items.drop(inserted).filter { blocks.shouldShowFeedVideo(it) }
            val card: @Composable (FeedVideo) -> Unit = { video ->
                val origin = rememberExpandOrigin()
                val topBar = LocalTopBarBounds.current
                val visible by remember(listState, video.bvid) {
                    derivedStateOf { video.bvid in visibleKeys }
                }
                DisposableEffect(video.bvid, video.cover) {
                    onDispose { settledCovers.remove(video.bvid) }
                }
                SideEffect {
                    origin.attachToRow(listState.asRowScrollState(), video.bvid, topBar)
                }
                FeedCard(
                    video = video,
                    aspect = coverAspect,
                    coverWidthPx = coverWidthPx,
                    coverHeightPx = coverHeightPx,
                    modifier = Modifier.expandOrigin(origin),
                    columns = columns,
                    onOpen = { nav.push(Route.Video(video.bvid), origin) },
                    onWatchLater = rememberWatchLaterAction(container, video),
                    generateBackground = imageWorkActive && visible,
                    onCoverSettled = { settledCovers[video.bvid] = video.cover },
                )
            }
            items(
                items = freshItems,
                key = { it.bvid },
                contentType = { FeedCardContentType },
            ) { card(it) }
            if (inserted in 1 until shown.items.size) {
                item(key = RefreshDividerKey, span = { GridItemSpan(maxLineSpan) }) {
                    RefreshDivider(onBackToTop = { scope.launch { listState.animateScrollToItem(0) } })
                }
            }
            items(
                items = olderItems,
                key = { it.bvid },
                contentType = { FeedCardContentType },
            ) { card(it) }
            if (shown.items.isNotEmpty()) {
                item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (state.isLoadingMore) {
                            LoadingState()
                        } else {
                            Text(
                                text = "没有更多了",
                                color = COUITheme.colorScheme.onSurfaceSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 推荐流刷新后的拼接占位：整块和一张卡片差不多高，提示这批新内容看完了。
 * 点一下滚回列表顶部。
 */
@Composable
private fun RefreshDivider(onBackToTop: () -> Unit) {
    val scheme = COUITheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp),
        insideMargin = PaddingValues(0.dp),
        onClick = onBackToTop,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "本次刷新的内容已看完",
                fontSize = 12.sp,
                color = scheme.onSurfaceSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "回到顶部",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.primary,
            )
        }
    }
}
