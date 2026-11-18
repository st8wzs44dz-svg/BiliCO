package erl.bilico.feature.feed

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import erl.bilico.data.model.FeedVideo
import erl.bilico.data.util.Formatters
import erl.bilico.ui.components.Avatar
import erl.bilico.ui.components.CouiMenuItem
import erl.bilico.ui.components.CouiPopupMenu
import erl.bilico.ui.components.StatLabel
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

private val FeedAvatarSize = 18.dp

/** 推荐流与排行榜共用的视频卡片。 */
@Composable
internal fun FeedCard(
    video: FeedVideo,
    coverWidthPx: Int,
    coverHeightPx: Int,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onWatchLater: () -> Unit,
) {
    val coverUrl = remember(video.cover, coverWidthPx, coverHeightPx) {
        Formatters.sizedImageUrl(video.cover, coverWidthPx, coverHeightPx)
    }
    val blurCoverUrl = remember(video.cover) {
        Formatters.sizedImageUrl(video.cover, 96, 54)
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val avatarUrl = remember(video.upFace, density) {
        val pixels = with(density) { FeedAvatarSize.roundToPx() }.coerceAtLeast(1)
        // 向上取整到常用 CDN 尺寸，显示时仍用原来的头像大小和圆形裁剪。
        val thumbnailPx = ((pixels + 31) / 32) * 32
        Formatters.sizedImageUrl(video.upFace, thumbnailPx, thumbnailPx)
    }
    val blurRadiusPx = remember(density) { with(density) { 20.dp.toPx() } }
    val scheme = COUITheme.colorScheme
    // 长按卡片在手指位置弹同一个三点菜单，不跳到播放页也不走卡片点击。
    val pressState = rememberPreciseClickState()
    var menuExpanded by remember { mutableStateOf(false) }
    var blurLoaded by remember(blurCoverUrl) { mutableStateOf(false) }
    var blurFailed by remember(blurCoverUrl) { mutableStateOf(false) }
    val showDarkBlur = blurCoverUrl.isNotBlank() && blurLoaded && !blurFailed
    val titleColor = if (showDarkBlur) Color.White else scheme.onSurface
    val secondaryTextColor = if (showDarkBlur) {
        Color.White.copy(alpha = 0.7f)
    } else {
        scheme.onSurfaceSecondary
    }

    Box(modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .preciseClickAnchor(pressState),
            insideMargin = PaddingValues(0.dp),
            onClick = onOpen,
            onLongPress = { menuExpanded = true },
        ) {
        // 封面顶到卡片上沿和两侧，圆角由卡片自己的轮廓裁出来，不再单独套内边距。
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (video.durationText.isNotBlank()) {
                Text(
                    text = video.durationText,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(SquircleShape(4.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    fontSize = 11.sp,
                    color = Color.White,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds(),
        ) {
            if (blurCoverUrl.isNotBlank() && !blurFailed) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            val fadePx = 28.dp.toPx().coerceAtMost(size.height)
                            val fadeStop = if (size.height > 0f) {
                                (fadePx / size.height).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    fadeStop to Color.White,
                                    1f to Color.White,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) {
                    AsyncImage(
                        model = blurCoverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onSuccess = {
                            blurLoaded = true
                            blurFailed = false
                        },
                        onError = {
                            blurLoaded = false
                            blurFailed = true
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleY = -1f
                                renderEffect = BlurEffect(
                                    radiusX = blurRadiusPx,
                                    radiusY = blurRadiusPx,
                                    edgeTreatment = TileMode.Mirror,
                                )
                            },
                    )
                }
                if (blurLoaded) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0f),
                                    1f to Color.Black.copy(alpha = 0.22f),
                                ),
                            ),
                    )
                }
            }

            Column(
                modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 6.dp),
            ) {
                Text(
                    text = video.title,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(avatarUrl, FeedAvatarSize)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = video.upName.ifBlank { "未知 UP" },
                        fontSize = 12.sp,
                        color = secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(4.dp))

                // 三点菜单落在右下角、和播放量同一行，跟左侧头像差不多高。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatLabel(COUIIcons.Play, video.playCountText, color = secondaryTextColor)
                    if (video.danmakuText.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        StatLabel(COUIIcons.Messages, video.danmakuText, color = secondaryTextColor)
                    }
                    Spacer(Modifier.weight(1f))
                    MoreMenu(
                        tint = secondaryTextColor,
                        onWatchLater = onWatchLater,
                        onShare = { shareVideo(context, video) },
                    )
                }
            }
        }
    }

        CouiPopupMenu(
            show = menuExpanded,
            onDismiss = { menuExpanded = false },
            items = listOf(
                CouiMenuItem("稍后再看", onWatchLater),
                CouiMenuItem("分享", { shareVideo(context, video) }),
            ),
            preciseClickState = pressState,
        )
    }
}

/** 卡片右下角的三个点：COUI 的玻璃弹层菜单，稍后再看与分享。与 UP 主头像同高。 */
@Composable
internal fun MoreMenu(
    onWatchLater: () -> Unit,
    onShare: () -> Unit,
    tint: Color = COUITheme.colorScheme.onSurfaceSecondary,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .size(20.dp)
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
                modifier = Modifier.size(18.dp),
                tint = tint,
            )
        }

        CouiPopupMenu(
            show = expanded,
            onDismiss = { expanded = false },
            items = listOf(
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
