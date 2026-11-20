package erl.bilico.feature.pgc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import erl.bilico.data.model.BangumiCard
import erl.bilico.data.util.Formatters
import erl.bilico.di.AppContainer
import erl.bilico.feature.feed.FeedSegmentRowHeight
import erl.bilico.feature.feed.HandleFeedPrimaryTap
import erl.bilico.ui.BiliCoTheme
import erl.bilico.ui.NavState
import erl.bilico.ui.Route
import erl.bilico.ui.TitleRowHeight
import erl.bilico.ui.components.EmptyState
import erl.bilico.ui.components.ErrorState
import erl.bilico.ui.components.FullPageRefreshHoldMillis
import erl.bilico.ui.components.FullPageRefreshTexts
import erl.bilico.ui.components.LoadingState
import erl.bilico.ui.components.navBottomPadding
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.FullPagePullToRefresh
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.fullPagePullToRefreshColors
import io.github.suqi8.coui.kmp.basic.rememberFullPagePullToRefreshState
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val BangumiCinemaBackground = Color(0xFF141414)
private val BangumiPosterPlaceholder = Color(0xFF1E1E22)
private val BangumiPosterRadius = 14.dp
private val BangumiRatingColor = Color(0xFFFFB74D)

/** 番剧分段：自动深色影院模式 + Netflix 风格 2:3 全封面海报卡，点封面进详情。 */
@Composable
fun BangumiRankSection(
    container: AppContainer,
    nav: NavState,
    topInset: Dp,
    gridState: LazyGridState,
    active: Boolean,
    onCollapse: (Float) -> Unit,
    refreshTick: Int,
    primaryTapTick: Int = 0,
) {
    val viewModel: BangumiRankViewModel = viewModel { BangumiRankViewModel(container.pgc) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pullState = rememberFullPagePullToRefreshState()
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val coverWidthPx = remember(screenWidthDp, density) {
        with(density) { ((screenWidthDp.dp - 34.dp) / 2f).roundToPx() }.coerceAtMost(600)
    }
    val coverHeightPx = coverWidthPx * 3 / 2
    val titleHeightPx = with(density) { TitleRowHeight.toPx() }

    LaunchedEffect(gridState, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()
            if (first == null || first.index > 0) titleHeightPx
            else (-first.offset.y).toFloat().coerceIn(0f, titleHeightPx)
        }
            .distinctUntilChanged()
            .collect(onCollapse)
    }

    // 双击当前分段：直接停在被下拉位置刷新，回顶对齐该位置，刷新完再回顶。
    // 切走底栏会把这一页拆掉。进来时先记住当时的计数，回来重组不会把上次那次再刷一遍。
    val refreshBaseline = remember { mutableIntStateOf(refreshTick) }
    LaunchedEffect(refreshTick) {
        if (refreshTick == refreshBaseline.intValue || !active) return@LaunchedEffect
        pullState.snapToRefreshing { viewModel.refresh() }
        if (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0) {
            if (gridState.firstVisibleItemIndex > 6) {
                gridState.scrollToItem(6)
            }
            gridState.animateScrollToItem(0)
        }
    }

    HandleFeedPrimaryTap(
        primaryTapTick = primaryTapTick,
        active = active,
        isRefreshing = state.isRefreshing,
        gridState = gridState,
        pullState = pullState,
        onRefresh = { viewModel.refresh() },
    )

    val visibleKeys by remember(gridState) {
        derivedStateOf(structuralEqualityPolicy()) {
            gridState.layoutInfo.visibleItemsInfo.mapTo(HashSet()) { it.key }
        }
    }

    BiliCoTheme(darkMode = 2) {
        FullPagePullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { scope.launch { viewModel.refresh() } },
            modifier = Modifier
                .fillMaxSize()
                .background(BangumiCinemaBackground),
            state = pullState,
            contentPadding = PaddingValues(top = topInset + FeedSegmentRowHeight),
            colors = fullPagePullToRefreshColors(backgroundColor = BangumiCinemaBackground),
            refreshTexts = FullPageRefreshTexts,
            completeHoldDurationMillis = FullPageRefreshHoldMillis,
        ) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().scrollEndHaptic(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    top = topInset + FeedSegmentRowHeight,
                    end = 12.dp,
                    bottom = 66.dp + navBottomPadding(),
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.items.isEmpty()) {
                    item(key = "state", span = { GridItemSpan(2) }) {
                        Box(Modifier.fillMaxWidth()) {
                            when {
                                state.isLoading -> LoadingState()
                                state.error != null -> ErrorState(state.error.orEmpty()) {
                                    viewModel.refresh()
                                }

                                else -> EmptyState("暂时没有番剧排行")
                            }
                        }
                    }
                }
                items(
                    items = state.items,
                    key = { it.seasonId },
                    contentType = { "bangumi-card" },
                ) { card ->
                    val visible by remember(gridState, card.seasonId) {
                        derivedStateOf { card.seasonId in visibleKeys }
                    }
                    BangumiRankCard(
                        card = card,
                        coverWidthPx = coverWidthPx,
                        coverHeightPx = coverHeightPx,
                        generateBlur = active && visible,
                        onClick = { nav.push(Route.Pgc(card.seasonId, 0L)) },
                    )
                }
            }
        }
    }
}

/**
 * Netflix 风格 2:3 完整海报卡片：
 * 整张卡片由圆角剪切的海报铺满，底部仅按文字区域高度自适应叠加渐变模糊与深色垫底，
 * 将 73%～80% 以上的清晰海报画面完整保留出来。
 */
@Composable
internal fun BangumiRankCard(
    card: BangumiCard,
    coverWidthPx: Int,
    coverHeightPx: Int,
    onClick: () -> Unit,
    generateBlur: Boolean = true,
) {
    val density = LocalDensity.current
    val coverUrl = remember(card.cover, coverWidthPx, coverHeightPx) {
        Formatters.sizedImageUrl(card.cover, coverWidthPx, coverHeightPx)
    }
    var coverReady by remember(coverUrl) { mutableStateOf(false) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var infoHeight by remember { mutableIntStateOf(0) }
    val posterBlur = rememberBangumiPosterBlur(
        originalUrl = card.cover,
        coverUrl = coverUrl,
        coverReady = coverReady,
        cardSize = cardSize,
        infoHeight = infoHeight,
        generateBlur = generateBlur,
    )
    val cardShape = remember { SquircleShape(BangumiPosterRadius) }
    val fadePx = with(density) { BangumiPosterBlurFade.roundToPx() }
    val scrimHeightDp = with(density) {
        if (infoHeight > 0) (infoHeight + fadePx).toDp() + 8.dp else 72.dp
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = BangumiPosterRadius,
        insideMargin = PaddingValues(0.dp),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(cardShape)
                .background(BangumiPosterPlaceholder)
                .onSizeChanged { cardSize = it },
        ) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { coverReady = true },
                onError = { coverReady = false },
                modifier = Modifier.fillMaxSize(),
            )

            if (posterBlur != null && infoHeight > 0 && cardSize.height > 0) {
                Canvas(Modifier.matchParentSize()) {
                    val blurHeight = (infoHeight + fadePx).coerceAtMost(size.height.roundToInt())
                    val top = (size.height.roundToInt() - blurHeight).coerceAtLeast(0)
                    if (blurHeight > 0) {
                        drawImage(
                            image = posterBlur,
                            dstOffset = IntOffset(0, top),
                            dstSize = IntSize(size.width.roundToInt(), blurHeight),
                            filterQuality = FilterQuality.Low,
                        )
                    }
                }
            }

            // 始终常驻的深色渐变垫底：模糊图就绪前防白字发虚，就绪后进一步增强暗底对比度。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(scrimHeightDp)
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.30f to Color(0x520A0A0D),
                            0.68f to Color(0xBA09090C),
                            1.00f to Color(0xE607070A),
                        ),
                    ),
            )

            if (card.badge.isNotBlank()) {
                Text(
                    text = card.badge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(SquircleShape(5.dp))
                        .background(Color(0xA8000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .onSizeChanged { infoHeight = it.height }
                    .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 9.dp),
            ) {
                Text(
                    text = card.title,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                )
                if (card.subtitle.isNotBlank() || card.rating.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = card.subtitle,
                            modifier = Modifier.weight(1f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White.copy(alpha = 0.78f),
                        )
                        if (card.rating.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = card.rating,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BangumiRatingColor,
                            )
                        }
                    }
                }
            }

            // 极淡高光描边，在深色影院底面上勾勒清晰的海报边界。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(0.5.dp, Color.White.copy(alpha = 0.08f), cardShape),
            )
        }
    }
}
