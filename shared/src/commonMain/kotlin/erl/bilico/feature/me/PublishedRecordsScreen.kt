package erl.bilico.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import erl.bilico.data.repository.CommentVisibilityStatus
import erl.bilico.data.repository.PublishedRecord
import erl.bilico.data.repository.PublishedRecordKind
import erl.bilico.data.util.Formatters
import erl.bilico.di.AppContainer
import erl.bilico.platform.LocalPlatformContext
import erl.bilico.platform.copyToClipboard
import erl.bilico.platform.showToast
import erl.bilico.ui.NavState
import erl.bilico.ui.components.ConfirmDialog
import erl.bilico.ui.components.CouiMenuItem
import erl.bilico.ui.components.CouiPopupMenu
import erl.bilico.ui.components.EmptyState
import erl.bilico.ui.components.SecondaryPage
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.HighlightedIconButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.Search
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

internal interface JsonFileExchanger {
    val supported: Boolean
    fun exportJson(suggestedFileName: String, provideContent: suspend () -> String)
    fun importJson(onContent: suspend (String) -> Unit)
}

@Composable
internal expect fun rememberJsonFileExchanger(): JsonFileExchanger

private enum class RecordFilter(val label: String) {
    ALL("全部"),
    COMMENT("评论"),
    DANMAKU("弹幕"),
}

@Composable
fun PublishedRecordsScreen(
    container: AppContainer,
    nav: NavState,
) {
    val context = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val allRecords by container.publishedRecords.records.collectAsStateWithLifecycle()
    val exchanger = rememberJsonFileExchanger()

    var filter by remember { mutableStateOf(RecordFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PublishedRecord?>(null) }
    val visibilityMap = remember { mutableStateMapOf<String, CommentVisibilityStatus>() }
    val checkingKeys = remember { mutableStateMapOf<String, Boolean>() }

    val filtered = remember(allRecords, filter, query) {
        val q = query.trim().lowercase()
        allRecords.filter { item ->
            val matchKind = when (filter) {
                RecordFilter.ALL -> true
                RecordFilter.COMMENT -> item.kind == PublishedRecordKind.COMMENT
                RecordFilter.DANMAKU -> item.kind == PublishedRecordKind.DANMAKU
            }
            if (!matchKind) return@filter false
            if (q.isEmpty()) true
            else item.content.lowercase().contains(q) ||
                item.targetTitle.lowercase().contains(q)
        }
    }

    SecondaryPage(
        title = "发言记录",
        onBack = nav::pop,
        actions = {
            Box {
                HighlightedIconButton(onClick = { menuExpanded = true }) {
                    Icon(COUIIcons.More, "更多", Modifier.size(22.dp))
                }
                CouiPopupMenu(
                    show = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    items = buildList {
                        if (exchanger.supported) {
                            add(
                                CouiMenuItem(
                                    label = "导出 JSON 文件",
                                    onClick = {
                                        exchanger.exportJson("bilico_published_records.json") {
                                            container.publishedRecords.exportJson()
                                        }
                                    },
                                ),
                            )
                            add(
                                CouiMenuItem(
                                    label = "导入 JSON 文件",
                                    onClick = {
                                        exchanger.importJson { raw ->
                                            container.publishedRecords.importJson(raw)
                                                .onSuccess { added ->
                                                    showToast(context, "导入成功，新增 $added 条记录")
                                                }
                                                .onFailure { err ->
                                                    showToast(context, err.message ?: "JSON 格式无效")
                                                }
                                        }
                                    },
                                ),
                            )
                        }
                        add(
                            CouiMenuItem(
                                label = "复制全部 JSON",
                                onClick = {
                                    scope.launch {
                                        val json = container.publishedRecords.exportJson()
                                        copyToClipboard(context, json, "发言记录 JSON")
                                        showToast(context, "已复制 JSON 到剪贴板")
                                    }
                                },
                                group = 1,
                            ),
                        )
                    },
                )
            }
        },
    ) {
        item(key = "controls") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SearchInputBar(
                    query = query,
                    onQueryChange = { query = it },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RecordFilter.entries.forEach { tab ->
                        val selected = filter == tab
                        val scheme = COUITheme.colorScheme
                        Text(
                            text = tab.label,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) scheme.primary else scheme.onSurfaceSecondary,
                            modifier = Modifier
                                .clip(SquircleShape(14.dp))
                                .background(
                                    if (selected) scheme.primary.copy(alpha = 0.14f)
                                    else scheme.surfaceContainerHigh,
                                )
                                .clickable { filter = tab }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${filtered.size} 条",
                        style = COUITheme.textStyles.footnote2,
                        color = COUITheme.colorScheme.onSurfaceSecondary,
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        if (allRecords.isEmpty()) {
                            "还没有本地发言记录\n通过 BiliCO 发送评论或弹幕时会自动留档"
                        } else {
                            "没有匹配的结果"
                        },
                    )
                }
            }
        } else {
            items(filtered, key = { it.key }) { record ->
                PublishedRecordCard(
                    record = record,
                    visibilityStatus = visibilityMap[record.key],
                    checkingVisibility = checkingKeys[record.key] == true,
                    onCopy = {
                        copyToClipboard(context, record.content, "发言内容")
                        showToast(context, "已复制内容")
                    },
                    onCheckVisibility = if (record.kind == PublishedRecordKind.COMMENT && record.oid > 0L && record.rpid > 0L) {
                        {
                            if (checkingKeys[record.key] != true) {
                                checkingKeys[record.key] = true
                                scope.launch {
                                    val status = container.comments.checkCommentVisibility(
                                        oid = record.oid,
                                        rpid = record.rpid,
                                        rootId = record.rootId,
                                        type = record.commentType,
                                    ).getOrDefault(CommentVisibilityStatus.UNKNOWN)
                                    visibilityMap[record.key] = status
                                    checkingKeys.remove(record.key)
                                }
                            }
                        }
                    } else {
                        null
                    },
                    onDelete = { pendingDelete = record },
                )
            }
        }
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = "删除本地记录",
            message = "只从本地发言档案移除，不会删除 B 站服务器上的原评论或弹幕。",
            confirmText = "删除",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                val key = target.key
                pendingDelete = null
                scope.launch { container.publishedRecords.delete(key) }
            },
        )
    }
}

@Composable
private fun SearchInputBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val scheme = COUITheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SquircleShape(14.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = COUIIcons.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = scheme.onSurfaceSecondary,
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索已发送的评论或弹幕…",
                    style = COUITheme.textStyles.body2,
                    color = scheme.onSurfaceSecondary,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = COUITheme.textStyles.body2.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = COUIIcons.Close,
                contentDescription = "清除",
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onQueryChange("") },
                tint = scheme.onSurfaceSecondary,
            )
        }
    }
}

@Composable
private fun PublishedRecordCard(
    record: PublishedRecord,
    visibilityStatus: CommentVisibilityStatus?,
    checkingVisibility: Boolean,
    onCopy: () -> Unit,
    onCheckVisibility: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val scheme = COUITheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    val kindLabel = if (record.kind == PublishedRecordKind.COMMENT) "评论" else "弹幕"
    val timeText = remember(record.createdAtMs) {
        Formatters.formatDateTime(record.createdAtMs / 1000L)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        cornerRadius = CardDefaults.GroupCornerRadius,
        insideMargin = PaddingValues(14.dp),
        weight = CardDefaults.GroupWeight,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = kindLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                    modifier = Modifier
                        .clip(SquircleShape(6.dp))
                        .background(scheme.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
                Text(
                    text = timeText,
                    style = COUITheme.textStyles.footnote2,
                    color = scheme.onSurfaceSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (record.kind == PublishedRecordKind.DANMAKU && record.progressMs > 0L) {
                    Text(
                        text = Formatters.formatDuration(record.progressMs / 1000L),
                        style = COUITheme.textStyles.footnote2,
                        color = scheme.onSurfaceSecondary,
                    )
                }
            }

            Text(
                text = record.content,
                style = COUITheme.textStyles.body1.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = scheme.onSurface,
                modifier = Modifier.clickable(onClick = onCopy),
            )

            if (record.targetTitle.isNotBlank()) {
                Text(
                    text = record.targetTitle,
                    style = COUITheme.textStyles.footnote2,
                    color = scheme.onSurfaceSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (visibilityStatus != null || checkingVisibility) {
                val statusColor = when (visibilityStatus) {
                    CommentVisibilityStatus.VISIBLE -> if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
                    CommentVisibilityStatus.SHADOWBANNED -> if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309)
                    CommentVisibilityStatus.DELETED -> if (isDark) Color(0xFFF87171) else Color(0xFFB91C1C)
                    else -> scheme.onSurfaceSecondary
                }
                Text(
                    text = if (checkingVisibility) "正在检测评论可见性…" else "反诈自检：${visibilityStatus?.label.orEmpty()}",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                    modifier = Modifier
                        .clip(SquircleShape(6.dp))
                        .background(statusColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onCheckVisibility != null) {
                    Text(
                        text = if (checkingVisibility) "检测中…" else "可见性自检",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.primary,
                        modifier = Modifier
                            .clip(SquircleShape(12.dp))
                            .background(scheme.primary.copy(alpha = 0.12f))
                            .clickable(enabled = !checkingVisibility, onClick = onCheckVisibility)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                Text(
                    text = "复制",
                    fontSize = 12.5.sp,
                    color = scheme.onSurfaceSecondary,
                    modifier = Modifier
                        .clip(SquircleShape(12.dp))
                        .background(scheme.surfaceContainerHigh)
                        .clickable(onClick = onCopy)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                Spacer(Modifier.weight(1f))
                HighlightedIconButton(onClick = onDelete) {
                    Icon(
                        imageVector = COUIIcons.Delete,
                        contentDescription = "删除记录",
                        modifier = Modifier.size(18.dp),
                        tint = scheme.onSurfaceSecondary,
                    )
                }
            }
        }
    }
}
