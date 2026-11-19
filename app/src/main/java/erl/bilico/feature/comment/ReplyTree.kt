package erl.bilico.feature.comment

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import erl.bilico.data.model.Comment

internal data class ReplyRow(
    val comment: Comment,
    val depth: Int = 1,
    val moreRootReplies: Boolean = false,
    val hasNestedReplies: Boolean = false,
    val moreNestedReplies: Boolean = false,
)

/** Arrange the loaded replies under their known parents without reducing the text width beyond two levels. */
internal fun buildReplyRows(rootId: Long, replies: List<Comment>): List<ReplyRow> {
    val replyIds = replies.mapTo(HashSet()) { it.id }
    val children = replies.groupBy { it.parentId }
    val roots = replies.filter { it.parentId == rootId || it.parentId == it.id || it.parentId !in replyIds }
    val visited = HashSet<Long>()
    val branches = mutableListOf<Pair<Comment, List<Comment>>>()
    for (parent in roots + replies) {
        if (!visited.add(parent.id)) continue
        val descendants = mutableListOf<Comment>()
        val pending = ArrayDeque<Comment>()
        children[parent.id].orEmpty().asReversed().forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            val reply = pending.removeLast()
            if (!visited.add(reply.id)) continue
            descendants.add(reply)
            children[reply.id].orEmpty().asReversed().forEach(pending::addLast)
        }
        branches.add(parent to descendants)
    }
    return buildList {
        branches.forEachIndexed { index, (parent, descendants) ->
            val moreRootReplies = index < branches.lastIndex
            add(ReplyRow(parent, moreRootReplies = moreRootReplies, hasNestedReplies = descendants.isNotEmpty()))
            descendants.forEachIndexed { childIndex, reply ->
                add(ReplyRow(reply, depth = 2, moreRootReplies = moreRootReplies, moreNestedReplies = childIndex < descendants.lastIndex))
            }
        }
    }
}

internal val ReplyIndent = 28.dp
internal val ReplyRowTopPadding = 12.dp

internal fun Modifier.replyRootLine(color: Color): Modifier = drawWithCache {
    val center = ReplyRootAvatarSize.toPx() / 2f
    val x = if (layoutDirection == LayoutDirection.Ltr) center else size.width - center
    val start = (ReplyRootAvatarSize + 4.dp).toPx()
    onDrawBehind {
        if (start < size.height) {
            drawLine(color, Offset(x, start), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
    }
}

internal fun Modifier.replyBranchLines(row: ReplyRow, color: Color): Modifier = drawWithCache {
    val indent = ReplyIndent.toPx()
    val rootRail = ReplyRootAvatarSize.toPx() / 2f
    val branchRail = indent + ReplyChildAvatarSize.toPx() / 2f
    val avatarCenterY = ReplyRowTopPadding.toPx() + ReplyChildAvatarSize.toPx() / 2f
    val belowAvatar = (ReplyRowTopPadding + ReplyChildAvatarSize + 4.dp).toPx()
    val gap = 4.dp.toPx()
    fun x(start: Float) = if (layoutDirection == LayoutDirection.Ltr) start else size.width - start

    val path = Path().apply {
        val rail = if (row.depth == 1) rootRail else branchRail
        val end = row.depth * indent - gap
        val bend = minOf(8.dp.toPx(), end - rail, avatarCenterY)
        moveTo(x(rail), 0f)
        lineTo(x(rail), avatarCenterY - bend)
        quadraticTo(x(rail), avatarCenterY, x(rail + bend), avatarCenterY)
        lineTo(x(end), avatarCenterY)

        if (row.moreRootReplies) {
            moveTo(x(rootRail), if (row.depth == 1) avatarCenterY - bend else 0f)
            lineTo(x(rootRail), size.height)
        }
        if (row.hasNestedReplies) {
            moveTo(x(branchRail), belowAvatar)
            lineTo(x(branchRail), size.height)
        } else if (row.moreNestedReplies) {
            moveTo(x(branchRail), avatarCenterY - bend)
            lineTo(x(branchRail), size.height)
        }
    }
    val stroke = Stroke(width = 1.dp.toPx())
    onDrawBehind { drawPath(path, color = color, style = stroke) }
}
