package erl.bilico.feature.feed

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import erl.bilico.data.model.FeedVideo
import erl.bilico.data.repository.FeedDislikeAction
import erl.bilico.di.AppContainer
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

/**
 * 卡片三个点里的「稍后再看」。
 *
 * 未登录先拦下，和播放页一样不把空 csrf 发给接口；成功失败都给一句提示。
 */
@Composable
internal fun rememberWatchLaterAction(
    container: AppContainer,
    video: FeedVideo,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(video.aid, container) {
        {
            if (!container.session.isLoggedIn()) {
                Toast.makeText(context, "登录后可用", Toast.LENGTH_SHORT).show()
            } else {
                scope.launch {
                    container.interactions.addToWatchLater(video.aid)
                        .onSuccess {
                            Toast.makeText(context, "已加入稍后再看", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { error ->
                            Toast.makeText(
                                context,
                                error.message ?: "加入稍后再看失败",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                }
            }
        }
    }
}

private data class DislikeOption(
    val title: String,
    val subtitle: String,
    val action: FeedDislikeAction,
)

/**
 * 推荐流「不感兴趣」二级理由弹窗（COUI OverlayDialog）：
 * 参考 PiliPlus 提供不感兴趣、不想看该 UP 主、点踩此视频三个反馈入口。
 */
@Composable
internal fun FeedDislikeDialog(
    video: FeedVideo,
    isLoggedIn: Boolean,
    onDismiss: () -> Unit,
    onSelect: (FeedDislikeAction) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<FeedDislikeAction?>(null) }
    var settled by remember { mutableStateOf(false) }
    val colors = COUITheme.colorScheme
    val textStyles = COUITheme.textStyles
    val dimens = COUITheme.dimens
    LaunchedEffect(Unit) { show = true }

    val options = remember(video.aid, video.upMid, video.upName) {
        buildList {
            add(
                DislikeOption(
                    title = "不感兴趣",
                    subtitle = "减少此类内容推荐",
                    action = FeedDislikeAction.NOT_INTERESTED,
                ),
            )
            if (video.upMid > 0L && video.upName.isNotBlank()) {
                add(
                    DislikeOption(
                        title = "不想看 UP 主：${video.upName}",
                        subtitle = "减少该 UP 主推荐并在本次浏览中隐藏",
                        action = FeedDislikeAction.BLOCK_UP,
                    ),
                )
            }
            add(
                DislikeOption(
                    title = "点踩此视频",
                    subtitle = "向 B 站提交点踩反馈并移除该卡片",
                    action = FeedDislikeAction.DISLIKE_VIDEO,
                ),
            )
        }
    }

    OverlayDialog(
        show = show,
        title = "不感兴趣",
        summary = if (isLoggedIn) {
            "选择反馈理由，将同步向 B 站反馈并隐藏此卡片"
        } else {
            "未登录时仅在本次浏览中隐藏，登录后将同步向 B 站反馈"
        },
        outsideMargin = DpSize(16.dp, 8.dp),
        onDismissRequest = { show = false },
        onDismissFinished = {
            if (!settled) {
                settled = true
                val selected = chosen
                if (selected != null) onSelect(selected) else onDismiss()
            }
        },
    ) {
        COUITheme(colors = colors, textStyles = textStyles, dimens = dimens) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { option ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(SquircleShape(12.dp))
                                .background(colors.surfaceVariant)
                                .clickable {
                                    chosen = option.action
                                    show = false
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        ) {
                            Text(
                                text = option.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = option.subtitle,
                                fontSize = 12.sp,
                                color = colors.onSurfaceSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                DialogButtonBar(
                    negative = DialogButtonBarAction(text = "取消", onClick = { show = false }),
                    positive = null,
                )
            }
        }
    }
}
