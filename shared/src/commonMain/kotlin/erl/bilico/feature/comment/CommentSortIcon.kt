package erl.bilico.feature.comment

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import erl.bilico.data.model.CommentSort
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.theme.COUITheme

@Composable
internal fun CommentSortIcon(
    sort: CommentSort,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Icon(
        imageVector = if (sort == CommentSort.Hot) HotCommentSortIcon else LatestCommentSortIcon,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = COUITheme.colorScheme.primary,
    )
}

internal fun commentSortDescription(sort: CommentSort): String =
    if (sort == CommentSort.Hot) "当前最热，切换到最新" else "当前最新，切换到最热"

private val HotCommentSortIcon by lazy {
    commentSortIcon(
        "HotCommentSort",
        "M12 2.8C12.4 7 9.1 7.7 9.1 11.2C7.6 10.7 6.9 9.4 7 8C5.3 9.8 4.5 11.6 4.5 14.3" +
            "C4.5 18.7 7.8 21.5 12 21.5C16.2 21.5 19.5 18.6 19.5 14.3C19.5 10.6 16.7 6.3 12 2.8Z" +
            "M12 13.2C11.7 15.5 9.2 15.5 9.2 18.1C9.2 19.6 10.4 21.3 12 21.3C13.6 21.3 14.8 19.9 14.8 18.3" +
            "C14.8 16.4 13.2 14.6 12 13.2Z",
    )
}

private val LatestCommentSortIcon by lazy {
    commentSortIcon(
        "LatestCommentSort",
        "M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12ZM12 6.8V12H16.2",
    )
}

private fun commentSortIcon(name: String, data: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes(data),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
