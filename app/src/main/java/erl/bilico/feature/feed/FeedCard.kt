package erl.bilico.feature.feed

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.BitmapImage
import coil3.compose.AsyncImage
import erl.bilico.data.model.FeedVideo
import erl.bilico.data.repository.BlockKeywordScope
import erl.bilico.data.settings.CoverAspect
import erl.bilico.data.settings.FeedCoverBlurStyle
import erl.bilico.data.util.Formatters
import erl.bilico.di.LocalAppContainer
import erl.bilico.ui.components.Avatar
import erl.bilico.ui.components.CouiMenuItem
import erl.bilico.ui.components.CouiPopupMenu
import erl.bilico.ui.components.DanmakuStatIcon
import erl.bilico.ui.components.FeedDislikeDialog
import erl.bilico.ui.components.KeywordBlockDialog
import erl.bilico.ui.components.OfficialVerifyBadge
import erl.bilico.ui.components.ReportDialog
import erl.bilico.ui.components.ReportTarget
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.preciseClickAnchor
import io.github.suqi8.coui.kmp.basic.rememberPreciseClickState
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 不同列数下的卡片排版密度参数，保证 1~4 列都清晰不拥挤。 */
private data class FeedCardMetrics(
    val coverStatsHorizontalPadding: Dp,
    val coverStatsBottomPadding: Dp,
    val statGap: Dp,
    val statFontSize: TextUnit,
    val statLineHeight: TextUnit,
    val statIconSize: Dp,
    val showDanmakuOnCover: Boolean,
    val infoPadding: PaddingValues,
    val titleFontSize: TextUnit,
    val titleLineHeight: TextUnit,
    val rowSpacer: Dp,
    val showAvatar: Boolean,
    val avatarSize: Dp,
    val avatarGap: Dp,
    val upFontSize: TextUnit,
    val upLineHeight: TextUnit,
    val moreButtonSize: Dp,
    val moreIconSize: Dp,
) {
    companion object {
        fun forColumns(columns: Int): FeedCardMetrics = when {
            columns >= 4 -> FeedCardMetrics(
                coverStatsHorizontalPadding = 4.dp,
                coverStatsBottomPadding = 2.dp,
                statGap = 4.dp,
                statFontSize = 9.sp,
                statLineHeight = 11.sp,
                statIconSize = 10.dp,
                showDanmakuOnCover = false,
                infoPadding = PaddingValues(start = 5.dp, end = 3.dp, top = 4.dp, bottom = 5.dp),
                titleFontSize = 11.sp,
                titleLineHeight = 14.sp,
                rowSpacer = 3.dp,
                showAvatar = false,
                avatarSize = 14.dp,
                avatarGap = 4.dp,
                upFontSize = 10.sp,
                upLineHeight = 12.sp,
                moreButtonSize = 16.dp,
                moreIconSize = 14.dp,
            )

            columns == 3 -> FeedCardMetrics(
                coverStatsHorizontalPadding = 6.dp,
                coverStatsBottomPadding = 3.dp,
                statGap = 6.dp,
                statFontSize = 10.sp,
                statLineHeight = 13.sp,
                statIconSize = 11.dp,
                showDanmakuOnCover = false,
                infoPadding = PaddingValues(start = 7.dp, end = 4.dp, top = 5.dp, bottom = 6.dp),
                titleFontSize = 12.sp,
                titleLineHeight = 16.sp,
                rowSpacer = 4.dp,
                showAvatar = true,
                avatarSize = 14.dp,
                avatarGap = 4.dp,
                upFontSize = 10.sp,
                upLineHeight = 13.sp,
                moreButtonSize = 16.dp,
                moreIconSize = 14.dp,
            )

            else -> FeedCardMetrics(
                coverStatsHorizontalPadding = 9.dp,
                coverStatsBottomPadding = 3.dp,
                statGap = 10.dp,
                statFontSize = 11.sp,
                statLineHeight = 14.sp,
                statIconSize = 13.dp,
                showDanmakuOnCover = true,
                infoPadding = PaddingValues(start = 9.dp, end = 6.dp, top = 5.dp, bottom = 7.dp),
                titleFontSize = 13.sp,
                titleLineHeight = 17.sp,
                rowSpacer = 5.dp,
                showAvatar = true,
                avatarSize = 16.dp,
                avatarGap = 5.dp,
                upFontSize = 11.sp,
                upLineHeight = 14.sp,
                moreButtonSize = 18.dp,
                moreIconSize = 16.dp,
            )
        }
    }
}

/** 推荐流与排行榜共用的视频卡片：封面 + 镜像翻转模糊一体衬底（覆盖底边统计与紧凑信息区）。 */
@Composable
internal fun FeedCard(
    video: FeedVideo,
    aspect: CoverAspect = CoverAspect.CLASSIC,
    blurStyle: FeedCoverBlurStyle = FeedCoverBlurStyle.VIBRANT_DARK,
    coverWidthPx: Int,
    coverHeightPx: Int,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    onOpen: () -> Unit,
    onWatchLater: () -> Unit,
    generateBackground: Boolean = true,
    onCoverSettled: () -> Unit = {},
) {
    val metrics = remember(columns) { FeedCardMetrics.forColumns(columns) }
    val coverUrl = remember(video.cover, coverWidthPx, coverHeightPx) {
        Formatters.sizedImageUrl(video.cover, coverWidthPx, coverHeightPx)
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val avatarUrl = remember(video.upFace, density, metrics.avatarSize) {
        val pixels = with(density) { metrics.avatarSize.roundToPx() }.coerceAtLeast(1)
        val thumbnailPx = ((pixels + 31) / 32) * 32
        Formatters.sizedImageUrl(video.upFace, thumbnailPx, thumbnailPx)
    }
    val scheme = COUITheme.colorScheme
    val appDarkTheme = scheme.surface.luminance() < 0.5f
    // 长按卡片在手指位置弹同一个三点菜单，不跳到播放页也不走卡片点击。
    val pressState = rememberPreciseClickState()
    var menuExpanded by remember { mutableStateOf(false) }
    var coverReady by remember(coverUrl) { mutableStateOf(false) }
    var coverBitmap by remember(coverUrl) { mutableStateOf<Bitmap?>(null) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var coverHeight by remember { mutableStateOf(0) }
    val backdrop = rememberFeedCoverBackground(
        originalUrl = video.cover,
        coverUrl = coverUrl,
        coverReady = coverReady,
        coverBitmap = coverBitmap,
        cardSize = cardSize,
        coverHeight = coverHeight,
        generateBackground = generateBackground,
        blurStyle = blurStyle,
        appDarkTheme = appDarkTheme,
    )
    val titleColor = backdrop?.titleColor ?: scheme.onSurface
    val secondaryTextColor = backdrop?.secondaryTextColor ?: scheme.onSurfaceSecondary
    val statsColor = backdrop?.statsColor ?: Color.White
    val cardMenuItems = rememberVideoCardMenuItems(
        video = video,
        onWatchLater = onWatchLater,
    )

    Box(modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .preciseClickAnchor(pressState),
            insideMargin = PaddingValues(0.dp),
            onClick = onOpen,
            onLongPress = { menuExpanded = true },
        ) {
            Box(Modifier.fillMaxWidth().onSizeChanged { cardSize = it }) {
                // 封面与镜像翻转模糊共用底层图层，超出卡片圆角轮廓的部分由 Card 直接裁剪。
                Box(Modifier.matchParentSize()) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onSuccess = { state ->
                            coverReady = true
                            coverBitmap = (state.result.image as? BitmapImage)?.bitmap
                            onCoverSettled()
                        },
                        onError = {
                            coverReady = false
                            coverBitmap = null
                            onCoverSettled()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect.ratio)
                            .background(scheme.surfaceContainerHigh),
                    )
                    if (backdrop != null) {
                        Canvas(Modifier.matchParentSize()) {
                            val top = (coverHeight - FeedCoverOverlap.roundToPx()).coerceAtLeast(0)
                            val height = size.height.roundToInt() - top
                            if (height > 0) {
                                drawImage(
                                    image = backdrop.bitmap,
                                    dstOffset = IntOffset(0, top),
                                    dstSize = IntSize(size.width.roundToInt(), height),
                                    filterQuality = FilterQuality.Low,
                                )
                            }
                        }
                    }
                }

                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect.ratio)
                            .onSizeChanged { coverHeight = it.height },
                    ) {
                        if (coverReady && (
                                video.playCountText.isNotBlank() ||
                                    (metrics.showDanmakuOnCover && video.danmakuText.isNotBlank()) ||
                                    video.durationText.isNotBlank()
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(
                                        start = metrics.coverStatsHorizontalPadding,
                                        end = metrics.coverStatsHorizontalPadding,
                                        bottom = metrics.coverStatsBottomPadding,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (video.playCountText.isNotBlank()) {
                                        Icon(
                                            imageVector = COUIIcons.Play,
                                            contentDescription = null,
                                            modifier = Modifier.size(metrics.statIconSize),
                                            tint = statsColor,
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            text = video.playCountText,
                                            fontSize = metrics.statFontSize,
                                            lineHeight = metrics.statLineHeight,
                                            color = statsColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (metrics.showDanmakuOnCover && video.danmakuText.isNotBlank()) {
                                        if (video.playCountText.isNotBlank()) {
                                            Spacer(Modifier.width(metrics.statGap))
                                        }
                                        Icon(
                                            imageVector = DanmakuStatIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(metrics.statIconSize),
                                            tint = statsColor,
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            text = video.danmakuText,
                                            fontSize = metrics.statFontSize,
                                            lineHeight = metrics.statLineHeight,
                                            color = statsColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (video.durationText.isNotBlank()) {
                                    Text(
                                        text = video.durationText,
                                        fontSize = metrics.statFontSize,
                                        lineHeight = metrics.statLineHeight,
                                        color = statsColor,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.padding(metrics.infoPadding),
                    ) {
                        Text(
                            text = video.title,
                            fontSize = metrics.titleFontSize,
                            lineHeight = metrics.titleLineHeight,
                            fontWeight = FontWeight.Medium,
                            color = titleColor,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        Spacer(Modifier.height(metrics.rowSpacer))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (metrics.showAvatar && video.upFace.isNotBlank()) {
                                Avatar(avatarUrl, metrics.avatarSize)
                                Spacer(Modifier.width(metrics.avatarGap))
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = video.upName.ifBlank { "未知 UP" },
                                    fontSize = metrics.upFontSize,
                                    lineHeight = metrics.upLineHeight,
                                    color = secondaryTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (video.officialType == 0 || video.officialType == 1) {
                                    val idBadgeSize = with(LocalDensity.current) { metrics.upFontSize.toDp() }
                                    Spacer(Modifier.width(3.dp))
                                    OfficialVerifyBadge(
                                        type = video.officialType,
                                        size = idBadgeSize,
                                        ringColor = Color.Transparent,
                                    )
                                }
                            }
                            MoreMenu(
                                onWatchLater = onWatchLater,
                                onShare = { shareVideo(context, video) },
                                items = cardMenuItems,
                                tint = secondaryTextColor,
                                buttonSize = metrics.moreButtonSize,
                                iconSize = metrics.moreIconSize,
                            )
                        }
                    }
                }
            }
        }

        CouiPopupMenu(
            show = menuExpanded,
            onDismiss = { menuExpanded = false },
            items = cardMenuItems,
            preciseClickState = pressState,
        )
    }
}
@Composable
internal fun rememberVideoCardMenuItems(
    video: FeedVideo,
    onWatchLater: () -> Unit,
): List<CouiMenuItem> {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var showDislike by remember(video.bvid) { mutableStateOf(false) }
    var showReport by remember(video.bvid) { mutableStateOf(false) }
    var showKeyword by remember(video.bvid) { mutableStateOf(false) }
    var showDownload by remember(video.bvid) { mutableStateOf(false) }

    if (showDownload && container != null) {
        erl.bilico.feature.download.FeedVideoDownloadDialog(
            container = container,
            bvid = video.bvid,
            onEnqueued = { count ->
                if (count > 0) {
                    Toast.makeText(context, "已加入离线缓存队列 ($count)", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showDownload = false },
        )
    }

    if (showDislike) {
        FeedDislikeDialog(
            video = video,
            onDismiss = { showDislike = false },
        )
    }
    if (showReport && video.aid > 0L) {
        ReportDialog(
            target = ReportTarget.Video(
                aid = video.aid,
                bvid = video.bvid,
                title = video.title,
            ),
            onDismiss = { showReport = false },
        )
    }
    if (showKeyword) {
        KeywordBlockDialog(
            allowedScopes = listOf(
                BlockKeywordScope.VIDEO_TITLE,
                BlockKeywordScope.UP_NAME,
            ),
            initialScope = BlockKeywordScope.VIDEO_TITLE,
            scopeInitialKeywords = mapOf(
                BlockKeywordScope.VIDEO_TITLE to video.title,
                BlockKeywordScope.UP_NAME to video.upName,
            ),
            onDismiss = { showKeyword = false },
        )
    }

    return remember(video, onWatchLater, container, context, scope) {
        buildList {
            add(CouiMenuItem("稍后再看", onWatchLater))
            if (container != null) {
                add(CouiMenuItem("离线缓存", { showDownload = true }))
            }
            add(CouiMenuItem("分享", { shareVideo(context, video) }))
            if (container != null) {
                add(CouiMenuItem("我不想看", onClick = { showDislike = true }, group = 1))
                add(CouiMenuItem("关键词屏蔽", onClick = { showKeyword = true }, group = 1))
                if (video.upMid > 0L) {
                    add(
                        CouiMenuItem(
                            label = "屏蔽该 UP 主",
                            group = 1,
                            onClick = {
                                if (container.session.isLoggedIn() && container.session.csrf().isNotBlank()) {
                                    scope.launch {
                                        container.blocks.toggleServerBlacklist(
                                            mid = video.upMid,
                                            add = true,
                                            name = video.upName,
                                            face = video.upFace,
                                        ).onSuccess {
                                            Toast.makeText(context, "已屏蔽 UP 主（已同步账号黑名单）", Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            container.blocks.blockUserLocally(video.upMid, video.upName, video.upFace)
                                            Toast.makeText(
                                                context,
                                                "已本地屏蔽 UP 主 ${video.upName}（云端同步失败：${err.message ?: "未知错误"}）",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                } else {
                                    container.blocks.blockUserLocally(video.upMid, video.upName, video.upFace)
                                    Toast.makeText(context, "已本地屏蔽 UP 主 ${video.upName}", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ),
                    )
                }
                if (video.aid > 0L) {
                    add(CouiMenuItem("举报视频", onClick = { showReport = true }, group = 2))
                }
            }
        }
    }
}

/** 卡片右下角的三个点：COUI 的玻璃弹层菜单，稍后再看、分享、屏蔽与举报。 */
@Composable
internal fun MoreMenu(
    onWatchLater: () -> Unit,
    onShare: () -> Unit,
    items: List<CouiMenuItem>? = null,
    tint: Color = COUITheme.colorScheme.onSurfaceSecondary,
    buttonSize: Dp = 20.dp,
    iconSize: Dp = 18.dp,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .size(buttonSize)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = true },
                )
                .semantics { contentDescription = "更多" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = COUIIcons.More,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = tint,
            )
        }

        CouiPopupMenu(
            show = expanded,
            onDismiss = { expanded = false },
            items = items ?: listOf(
                CouiMenuItem("稍后再看", onWatchLater),
                CouiMenuItem("分享", onShare),
            ),
        )
    }
}

internal fun shareVideo(context: Context, video: FeedVideo) {
    val link = "https://www.bilibili.com/video/${video.bvid}"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "${video.title}\n$link")
    }
    context.startActivity(Intent.createChooser(intent, "分享视频"))
}
