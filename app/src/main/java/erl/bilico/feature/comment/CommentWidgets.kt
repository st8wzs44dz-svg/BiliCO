package erl.bilico.feature.comment

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.InlineTextContent
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import erl.bilico.data.model.Comment
import erl.bilico.data.model.DynamicImage
import erl.bilico.data.util.Formatters
import erl.bilico.ui.components.CouiPopupMenu
import erl.bilico.ui.components.CoverImage
import erl.bilico.ui.components.LoadingImage
import erl.bilico.ui.imageOrigin
import erl.bilico.ui.components.RichCommentText
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.preciseClickAnchor
import io.github.suqi8.coui.kmp.basic.rememberPreciseClickState
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.icon.extended.Messages
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 评论正文默认折叠到这个行数，超出才给一行「展开」。 */
private const val FOLDED_COMMENT_LINES = 8

private val LocationPrefix = Regex("^IP\\s*属地\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
private val CommentAvatarGap = 12.dp
private val FlatCommentAvatarGap = 8.dp
private val FlatCommentNameLineHeight = 17.sp
private val FlatCommentMetaLineHeight = 12.sp
private val FlatCommentMetaGap = 1.dp
private val FlatCommentSectionGap = 8.dp
private val FlatCommentActionPadding = 6.dp

internal fun Density.flatCommentAvatarSize(): Dp = (
    FlatCommentNameLineHeight.toDp().roundToPx() +
        FlatCommentMetaGap.roundToPx() +
        FlatCommentMetaLineHeight.toDp().roundToPx()
    ).toDp()

/** 视频主评论使用平铺样式，回复详情和其他入口共用原有评论内容。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BiliCommentItem(
    comment: Comment,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onReply: () -> Unit,
    onOpenReplies: ((Comment) -> Unit)? = null,
    onOpenUser: (Long) -> Unit = {},
    onOpenImage: (List<DynamicImage>, Int) -> Unit = { _, _ -> },
    isSubReply: Boolean = false,
    expandable: Boolean = true,
    currentMid: Long = 0L,
    onDelete: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    flat: Boolean = false,
    showDivider: Boolean = false,
) {
    var expanded by remember(comment.id) { mutableStateOf(false) }
    // 正文真的被截断了才给「展开」；短评论不占这一行。
    var folded by remember(comment.id) { mutableStateOf(false) }
    // 不可折叠时整段正文始终铺满，点击正文也不再翻转展开态。
    val unfolded = !expandable || expanded
    val density = LocalDensity.current
    val avatarDp = when {
        flat -> density.flatCommentAvatarSize()
        isSubReply -> 32.dp
        else -> 38.dp
    }
    val avatarGap = if (flat) FlatCommentAvatarGap else CommentAvatarGap
    val avatarPx = with(density) { avatarDp.roundToPx() }
    val openUser = remember(comment.mid, onOpenUser) {
        { if (comment.mid > 0L) onOpenUser(comment.mid) }
    }
    val isDark = COUITheme.colorScheme.background.luminance() < 0.5f
    val linkBlue = if (flat) COUITheme.colorScheme.primary else if (isDark) Color(0xFF5CA8FF) else Color(0xFF1367B1)
    // 楼中楼：白卡片上压一块中性浅灰，和列表底色拉开层次（取微博的配色）。
    val subBoxBg = if (isDark) Color(0xFF2B2D31) else Color(0xFFF2F3F5)
    // 灰块和白卡的亮度差只有 12 级，补一根极淡的描边，免得看着像没渲染出来。
    val subBoxOutline = if (isDark) Color(0xFF3A3D42) else Color(0xFFE6E8EC)
    val defaultNameColor = if (isDark) Color(0xFFB8BCC4) else Color(0xFF61666D)
    val nameColor = remember(comment.vipColorHex, defaultNameColor) {
        parseCommentColor(comment.vipColorHex) ?: defaultNameColor
    }
    val metaColor = if (isDark) Color(0xFF848994) else Color(0xFF9499A0)
    val canManageOwn = currentMid > 0L && comment.mid == currentMid && comment.id > 0L && onDelete != null
    val replyCount = maxOf(comment.replyCount, comment.replies.size)
    val replyCenter = if (flat && !isSubReply && onOpenReplies != null && replyCount > 0) {
        remember(comment.id) { mutableFloatStateOf(0f) }
    } else {
        null
    }
    val pressAnchor = rememberPreciseClickState()
    var longPressMenuExpanded by remember(comment.id) { mutableStateOf(false) }
    var confirmDelete by remember(comment.id) { mutableStateOf(false) }
    val menuItems = rememberCommentMenuItems(
        comment = comment,
        enabled = flat || canManageOwn,
        canDelete = canManageOwn,
        onRequestDelete = { confirmDelete = true },
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (replyCenter != null) {
                    Modifier.replyThreadLine(avatarDp, avatarGap, COUITheme.colorScheme.dividerLine) {
                        replyCenter.floatValue
                    }
                } else {
                    Modifier
                },
            )
            .then(if (canManageOwn) Modifier.preciseClickAnchor(pressAnchor) else Modifier)
            .then(
                if (onClick != null || canManageOwn) {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onClick?.invoke() },
                        onLongClick = if (canManageOwn) { { longPressMenuExpanded = true } } else null,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.size(avatarDp)) {
            CoverImage(
                url = Formatters.sizedImageUrl(comment.avatar, avatarPx, avatarPx),
                modifier = Modifier
                    .size(avatarDp)
                    .clip(CircleShape)
                    .clickable(onClick = openUser),
            )
            if (comment.officialType == 0 || comment.officialType == 1) {
                erl.bilico.ui.components.OfficialVerifyBadge(
                    type = comment.officialType,
                    size = (avatarDp * 0.36f).coerceIn(11.dp, 16.dp),
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
        Spacer(Modifier.width(avatarGap))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).then(if (flat) Modifier.height(avatarDp) else Modifier),
                    verticalArrangement = if (flat) Arrangement.Center else Arrangement.Top,
                ) {
                    UserNameWithBadges(
                        userName = comment.userName,
                        level = comment.level,
                        isUp = comment.isUp,
                        nameColor = nameColor,
                        onClick = openUser,
                        flat = flat,
                    )
                    val metaText = remember(comment.timeSec, comment.location, flat) {
                        val datePart = Formatters.formatCommentDate(comment.timeSec)
                        val location = if (flat) comment.location.replaceFirst(LocationPrefix, "").trim() else comment.location
                        when {
                            datePart.isNotEmpty() && location.isNotEmpty() -> "$datePart · $location"
                            location.isNotEmpty() -> location
                            else -> datePart
                        }
                    }
                    if (metaText.isNotEmpty()) {
                        Spacer(Modifier.height(if (flat) FlatCommentMetaGap else 2.dp))
                        Text(
                            text = metaText,
                            modifier = if (flat) {
                                Modifier.height(with(density) { FlatCommentMetaLineHeight.toDp() })
                            } else Modifier,
                            style = COUITheme.textStyles.footnote2.copy(
                                fontSize = if (flat) 10.5.sp else 11.5.sp,
                                lineHeight = if (flat) FlatCommentMetaLineHeight else 15.sp,
                            ),
                            color = metaColor,
                            maxLines = if (flat) 1 else Int.MAX_VALUE,
                            overflow = if (flat) TextOverflow.Ellipsis else TextOverflow.Clip,
                        )
                    }
                }

                if (!flat) {
                    comment.sailing?.let { sailing ->
                        Spacer(Modifier.width(8.dp))
                        CommentSailingBadge(sailing = sailing, isDark = isDark)
                    }
                }
                if (menuItems.isNotEmpty()) {
                    CommentMoreButton(
                        menuItems = menuItems,
                        modifier = Modifier.padding(start = 4.dp)
                            .then(if (flat) Modifier.align(Alignment.Top) else Modifier),
                        horizontal = flat,
                    )
                }
            }

            if (comment.content.isNotBlank() || comment.emotes.isNotEmpty() || comment.pictures.isEmpty()) {
                Spacer(Modifier.height(FlatCommentSectionGap))
                RichCommentText(
                    content = comment.content,
                    emotes = comment.emotes,
                    mentions = comment.mentions,
                    badgeLabel = if (!isSubReply && comment.isTop) "TOP" else null,
                    selectable = !canManageOwn,
                    modifier = Modifier.then(
                        if (onClick == null && expandable && folded && !expanded) {
                            Modifier.clickable { expanded = !expanded }
                        } else {
                            Modifier
                        },
                    ),
                    style = COUITheme.textStyles.body1.copy(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    color = COUITheme.colorScheme.onSurface,
                    prefixColor = linkBlue,
                    maxLines = if (unfolded) Int.MAX_VALUE else FOLDED_COMMENT_LINES,
                    onOpenUser = onOpenUser,
                    onTextLayout = { result ->
                        // 展开态下 maxLines 不再截断，别把标记清掉，否则「收起」会跟着消失。
                        if (expandable && !expanded && result.hasVisualOverflow != folded) {
                            folded = result.hasVisualOverflow
                        }
                    },
                )
                if (expandable && folded) {
                    Text(
                        text = if (expanded) "收起" else "展开",
                        modifier = Modifier
                            .clip(SquircleShape(4.dp))
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = COUITheme.colorScheme.primary,
                    )
                }
            }

            if (comment.pictures.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                CommentPicturesGrid(
                    pictures = comment.pictures,
                    isSubReply = isSubReply,
                    onOpenImage = onOpenImage,
                )
            }

            val actionGap = if (flat) FlatCommentSectionGap - FlatCommentActionPadding else 10.dp
            Spacer(Modifier.height(actionGap))

            CommentActionBar(
                liked = comment.liked,
                disliked = comment.disliked,
                likeCount = comment.like,
                metaColor = metaColor,
                onReply = onReply,
                onDislike = onDislike,
                onLike = onLike,
                flat = flat,
            )

            if (replyCenter != null) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .onPlaced { replyCenter.floatValue = it.positionInParent().y + it.size.height / 2f }
                        .clip(SquircleShape(6.dp))
                        .clickable { onOpenReplies?.invoke(comment) }
                        .padding(top = 6.dp, bottom = 6.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$replyCount 条回复",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = linkBlue,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(COUIIcons.ChevronForward, null, Modifier.size(14.dp), tint = linkBlue)
                }
            } else if (!flat && !isSubReply && onOpenReplies != null &&
                (comment.replies.isNotEmpty() || comment.replyCount > 0)
            ) {
                val previewReplies = remember(comment.replies) { comment.replies.take(2) }
                val entryLabel = remember(comment.subReplyEntryText, comment.replyCount) {
                    comment.subReplyEntryText.ifBlank { "共${comment.replyCount}条回复" }
                }
                Column(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .clip(SquircleShape(8.dp))
                        .background(subBoxBg)
                        .border(0.5.dp, subBoxOutline, SquircleShape(8.dp))
                        .clickable { onOpenReplies(comment) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    previewReplies.forEach { reply ->
                        RichCommentText(
                            content = reply.content,
                            emotes = reply.emotes,
                            mentions = reply.mentions,
                            prefixUserName = reply.userName,
                            compactEmotes = true,
                            style = COUITheme.textStyles.body2.copy(
                                fontSize = 13.5.sp,
                                lineHeight = 19.5.sp,
                            ),
                            color = COUITheme.colorScheme.onSurface,
                            prefixColor = linkBlue,
                            maxLines = 2,
                            onOpenUser = onOpenUser,
                        )
                        if (reply.pictures.isNotEmpty()) {
                            CommentPicturesGrid(
                                pictures = reply.pictures,
                                isSubReply = true,
                                compactPreview = true,
                                onOpenImage = onOpenImage,
                            )
                        }
                    }
                    if (comment.replyCount > 0) {
                        Text(
                            text = entryLabel,
                            style = COUITheme.textStyles.footnote1.copy(
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = linkBlue,
                        )
                    }
                }
            }
            if (showDivider) {
                HorizontalDivider(Modifier.padding(top = 10.dp), color = COUITheme.colorScheme.dividerLine)
            }
        }
    }

    if (canManageOwn) {
        CouiPopupMenu(
            show = longPressMenuExpanded,
            onDismiss = { longPressMenuExpanded = false },
            items = menuItems,
            preciseClickState = pressAnchor,
        )
    }
    }

    if (confirmDelete) {
        DeleteCommentConfirmDialog(
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete?.invoke()
            },
        )
    }
}

private fun Modifier.replyThreadLine(
    avatarSize: Dp,
    avatarGap: Dp,
    color: Color,
    replyCenter: () -> Float,
): Modifier = drawWithCache {
    val fromY = (avatarSize + 5.dp).toPx()
    val toY = replyCenter()
    // The reply row is measured inside the text column, whose top aligns with the avatar.
    val path = Path().apply {
        if (toY > fromY) {
            val avatarX = avatarSize.toPx() / 2f
            val tailX = (avatarSize + avatarGap - 6.dp).toPx()
            val fromX = if (layoutDirection == LayoutDirection.Ltr) avatarX else size.width - avatarX
            val toX = if (layoutDirection == LayoutDirection.Ltr) tailX else size.width - tailX
            val direction = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
            val bend = minOf(12.dp.toPx(), tailX - avatarX, toY - fromY)
            moveTo(fromX, fromY)
            lineTo(fromX, toY - bend)
            cubicTo(
                x1 = fromX,
                y1 = toY - bend * 0.45f,
                x2 = fromX + direction * bend * 0.45f,
                y2 = toY,
                x3 = fromX + direction * bend,
                y3 = toY,
            )
            lineTo(toX, toY)
        }
    }
    val stroke = Stroke(width = 1.dp.toPx())
    onDrawBehind { drawPath(path, color = color, style = stroke) }
}

@Composable
private fun CommentPicturesGrid(
    pictures: List<DynamicImage>,
    isSubReply: Boolean,
    compactPreview: Boolean = false,
    onOpenImage: (List<DynamicImage>, Int) -> Unit,
) {
    val density = LocalDensity.current
    if (pictures.size == 1 && !compactPreview) {
        val pic = pictures.first()
        val ratio = if (pic.width > 0 && pic.height > 0) {
            (pic.width.toFloat() / pic.height.toFloat()).coerceIn(0.65f, 1.85f)
        } else {
            4f / 3f
        }
        val thumbW = if (isSubReply) 136.dp else 164.dp
        val thumbPx = remember(density, thumbW) { with(density) { (thumbW * 1.5f).roundToPx() } }
        val url = remember(pic.url, thumbPx) { Formatters.widthImageUrl(pic.url, thumbPx) }
        val keyUrl = remember(pic.url) { Formatters.normalizeImageUrl(pic.url) }
        val trueAspect = if (pic.width > 0 && pic.height > 0) pic.width.toFloat() / pic.height.toFloat() else null
        Box(
            modifier = Modifier
                .width(thumbW)
                .aspectRatio(ratio)
                .imageOrigin(url = keyUrl, cornerRadius = 8.dp, aspect = trueAspect, model = url)
                .clip(SquircleShape(8.dp))
                .clickable { onOpenImage(pictures, 0) },
        ) {
            LoadingImage(
                url = url,
                contentDescription = "评论图片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        return
    }

    val cellDp = when {
        compactPreview -> 58.dp
        isSubReply -> 74.dp
        else -> 86.dp
    }
    val cellPx = remember(density, cellDp) { with(density) { (cellDp * 1.5f).roundToPx() } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        pictures.take(9).chunked(3).forEachIndexed { rowIdx, rowPics ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowPics.forEachIndexed { colIdx, pic ->
                    val index = rowIdx * 3 + colIdx
                    val url = remember(pic.url, cellPx) {
                        Formatters.sizedImageUrl(pic.url, cellPx, cellPx)
                    }
                    val keyUrl = remember(pic.url) { Formatters.normalizeImageUrl(pic.url) }
                    val trueAspect = if (pic.width > 0 && pic.height > 0) {
                        pic.width.toFloat() / pic.height.toFloat()
                    } else {
                        null
                    }
                    Box(
                        modifier = Modifier
                            .size(cellDp)
                            .imageOrigin(url = keyUrl, cornerRadius = 6.dp, aspect = trueAspect, model = url)
                            .clip(SquircleShape(6.dp))
                            .clickable { onOpenImage(pictures, index) },
                    ) {
                        LoadingImage(
                            url = url,
                            contentDescription = "评论图片",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserNameWithBadges(
    userName: String,
    level: Int,
    isUp: Boolean,
    nameColor: Color,
    onClick: () -> Unit,
    flat: Boolean,
) {
    val density = LocalDensity.current
    val lvWidthSp = with(density) { 24.dp.toSp() }
    val lvHeightSp = with(density) { 12.dp.toSp() }
    val upWidthSp = with(density) { 21.dp.toSp() }

    val (annotated, inlineContent) = remember(userName, level, isUp, lvWidthSp, lvHeightSp, upWidthSp) {
        val inline = LinkedHashMap<String, InlineTextContent>()
        val builder = AnnotatedString.Builder()
        builder.append(userName)
        if (level > 0) {
            builder.append(" ")
            builder.appendInlineContent("lv_badge", "LV$level")
            inline["lv_badge"] = InlineTextContent(
                Placeholder(lvWidthSp, lvHeightSp, PlaceholderVerticalAlign.Center),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BiliLevelBadge(level = level)
                }
            }
        }
        if (isUp) {
            builder.append(" ")
            builder.appendInlineContent("up_badge", "UP")
            inline["up_badge"] = InlineTextContent(
                Placeholder(upWidthSp, lvHeightSp, PlaceholderVerticalAlign.Center),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BiliUpBadge()
                }
            }
        }
        builder.toAnnotatedString() to inline
    }

    Text(
        text = annotated,
        modifier = Modifier
            .then(if (flat) Modifier.height(with(density) { FlatCommentNameLineHeight.toDp() }) else Modifier)
            .clickable(onClick = onClick),
        style = COUITheme.textStyles.body2.copy(
            fontSize = 13.5.sp,
            lineHeight = if (flat) FlatCommentNameLineHeight else 18.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = nameColor,
        softWrap = !flat,
        maxLines = if (flat) 1 else Int.MAX_VALUE,
        overflow = if (flat) TextOverflow.Ellipsis else TextOverflow.Visible,
        inlineContent = inlineContent,
    )
}

@Composable
private fun CommentActionBar(
    liked: Boolean,
    disliked: Boolean,
    likeCount: Int,
    metaColor: Color,
    onReply: () -> Unit,
    onDislike: () -> Unit,
    onLike: () -> Unit,
    flat: Boolean,
) {
    val activeColor = COUITheme.colorScheme.primary
    val idleColor = if (flat) COUITheme.colorScheme.onSurface else metaColor
    val likeIcon = if (liked) LikeFilledIcon else LikeOutlineIcon
    val likeLabel = if (liked) "取消点赞" else "点赞"
    val likeColor = if (liked) activeColor else idleColor
    val dislikeIcon = if (disliked) DislikeFilledIcon else DislikeOutlineIcon
    val dislikeLabel = if (disliked) "取消点踩" else "点踩"
    val dislikeColor = if (disliked) activeColor else idleColor
    val formattedCount = if (likeCount > 0) Formatters.formatStat(likeCount.toLong()) else null

    if (flat) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommentIconAction(likeIcon, likeLabel, likeColor, onLike, formattedCount, liked, flat = true)
            CommentIconAction(dislikeIcon, dislikeLabel, dislikeColor, onDislike, flat = true)
            CommentIconAction(COUIIcons.Messages, "回复", idleColor, onReply, flat = true)
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clip(SquircleShape(6.dp))
                    .clickable(onClick = onReply)
                    .padding(vertical = 2.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = ReplyCurveIcon,
                    contentDescription = "回复",
                    modifier = Modifier.size(15.dp),
                    tint = metaColor,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "回复",
                    style = COUITheme.textStyles.footnote1.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
                    color = metaColor,
                )
            }

            Spacer(Modifier.weight(1f))
            CommentIconAction(dislikeIcon, dislikeLabel, dislikeColor, onDislike, horizontalPadding = 6.dp)
            Spacer(Modifier.width(14.dp))
            CommentIconAction(likeIcon, likeLabel, likeColor, onLike, formattedCount, liked)
        }
    }
}

@Composable
private fun CommentIconAction(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    count: String? = null,
    emphasizedCount: Boolean = false,
    horizontalPadding: Dp = 4.dp,
    flat: Boolean = false,
) {
    Row(
        modifier = Modifier
            .clip(SquircleShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = if (flat) FlatCommentActionPadding else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, Modifier.size(if (flat) 18.dp else 16.dp), tint = color)
        if (count != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = count,
                style = COUITheme.textStyles.footnote1.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    fontWeight = if (emphasizedCount) FontWeight.Medium else FontWeight.Normal,
                ),
                color = color,
            )
        }
    }
}
