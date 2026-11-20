package erl.bilico.feature.feed

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import erl.bilico.data.model.FeedVideo
import erl.bilico.data.repository.BlockKeywordScope
import erl.bilico.data.settings.CoverAspect
import erl.bilico.data.util.Formatters
import erl.bilico.di.LocalAppContainer
import erl.bilico.ui.components.Avatar
import erl.bilico.ui.components.CouiMenuItem
import erl.bilico.ui.components.CouiPopupMenu
import erl.bilico.ui.components.FeedDislikeDialog
import erl.bilico.ui.components.KeywordBlockDialog
import erl.bilico.ui.components.ReportDialog
import erl.bilico.ui.components.ReportTarget
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.preciseClickAnchor
import io.github.suqi8.coui.kmp.basic.rememberPreciseClickState
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Messages
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val FeedAvatarSize = 16.dp

/** 推荐流与排行榜共用的视频卡片：4:3 封面 + 镜像翻转模糊一体衬底（覆盖底边统计与紧凑信息区）。 */
@Composable
internal fun FeedCard(
    video: FeedVideo,
    aspect: CoverAspect = CoverAspect.CLASSIC,
    coverWidthPx: Int,
    coverHeightPx: Int,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onWatchLater: () -> Unit,
    generateBackground: Boolean = true,
    onCoverSettled: () -> Unit = {},
) {
    val coverUrl = remember(video.cover, coverWidthPx, coverHeightPx) {
        Formatters.sizedImageUrl(video.cover, coverWidthPx, coverHeightPx)
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val avatarUrl = remember(video.upFace, density) {
        val pixels = with(density) { FeedAvatarSize.roundToPx() }.coerceAtLeast(1)
        val thumbnailPx = ((pixels + 31) / 32) * 32
        Formatters.sizedImageUrl(video.upFace, thumbnailPx, thumbnailPx)
    }
    val scheme = COUITheme.colorScheme
    // 长按卡片在手指位置弹同一个三点菜单，不跳到播放页也不走卡片点击。
    val pressState = rememberPreciseClickState()
    var menuExpanded by remember { mutableStateOf(false) }
    var coverReady by remember(coverUrl) { mutableStateOf(false) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var coverHeight by remember { mutableStateOf(0) }
    val background = rememberFeedCoverBackground(
        video.cover, coverUrl, coverReady, cardSize, coverHeight, generateBackground,
    )
    val showDarkBlur = background != null
    val titleColor = if (showDarkBlur) Color.White else scheme.onSurface
    val secondaryTextColor = if (showDarkBlur) {
        Color.White.copy(alpha = 0.72f)
    } else {
        scheme.onSurfaceSecondary
    }
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
                        onSuccess = {
                            coverReady = true
                            onCoverSettled()
                        },
                        onError = {
                            coverReady = false
                            onCoverSettled()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect.ratio),
                    )
                    if (background != null) {
                        Canvas(Modifier.matchParentSize()) {
                            val top = (coverHeight - FeedCoverOverlap.roundToPx()).coerceAtLeast(0)
                            val height = size.height.roundToInt() - top
                            if (height > 0) {
                                drawImage(
                                    image = background,
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
                        if (video.playCountText.isNotBlank() ||
                            video.danmakuText.isNotBlank() ||
                            video.durationText.isNotBlank()
                        ) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(start = 9.dp, end = 9.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (video.playCountText.isNotBlank()) {
                                        Icon(
                                            imageVector = COUIIcons.Play,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = Color.White,
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            text = video.playCountText,
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (video.danmakuText.isNotBlank()) {
                                        if (video.playCountText.isNotBlank()) {
                                            Spacer(Modifier.width(10.dp))
                                        }
                                        Icon(
                                            imageVector = COUIIcons.Messages,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = Color.White,
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            text = video.danmakuText,
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (video.durationText.isNotBlank()) {
                                    Text(
                                        text = video.durationText,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        color = Color.White,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.padding(start = 9.dp, end = 6.dp, top = 5.dp, bottom = 7.dp),
                    ) {
                        Text(
                            text = video.title,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.Medium,
                            color = titleColor,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        Spacer(Modifier.height(5.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (video.upFace.isNotBlank()) {
                                Avatar(avatarUrl, FeedAvatarSize, officialType = video.officialType)
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(
                                text = video.upName.ifBlank { "未知 UP" },
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = secondaryTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            MoreMenu(
                                onWatchLater = onWatchLater,
                                onShare = { shareVideo(context, video) },
                                items = cardMenuItems,
                                tint = secondaryTextColor,
                                buttonSize = 18.dp,
                                iconSize = 16.dp,
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
