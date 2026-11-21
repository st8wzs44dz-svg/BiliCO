package erl.bilico.data.repository

import android.content.Context
import erl.bilico.data.network.BiliClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

enum class PublishedRecordKind {
    COMMENT,
    DANMAKU,
}

@Serializable
data class PublishedRecord(
    val key: String,
    val kind: PublishedRecordKind,
    val content: String,
    val oid: Long,
    val commentType: Int = 1,
    val rpid: Long = 0L,
    val rootId: Long = 0L,
    val cid: Long = 0L,
    val progressMs: Long = 0L,
    val targetTitle: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
)

/** 评论可见性自检（发评反诈）结果。 */
enum class CommentVisibilityStatus(val label: String) {
    VISIBLE("正常可见"),
    SHADOWBANNED("疑似仅自己可见（审核/吞评）"),
    DELETED("已不存在或被删除"),
    UNKNOWN("检测失败"),
}

/**
 * 本地发评与弹幕记录仓库：采用轻量 JSON 文件持久化（无需引入 Room），
 * 支持记录存档、关键词检索、单条删除以及 JSON 导入导出。
 */
class PublishedRecordRepository(
    context: Context,
    scope: CoroutineScope,
) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val mutex = Mutex()

    private val _records = MutableStateFlow<List<PublishedRecord>>(emptyList())
    val records: StateFlow<List<PublishedRecord>> = _records.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            load()
        }
    }

    suspend fun recordComment(
        oid: Long,
        type: Int,
        rpid: Long,
        rootId: Long,
        content: String,
        targetTitle: String = "",
    ) {
        val text = content.trim()
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        val key = "comment_${type}_${oid}_${if (rpid > 0L) rpid else now}"
        save(
            PublishedRecord(
                key = key,
                kind = PublishedRecordKind.COMMENT,
                content = text,
                oid = oid,
                commentType = type,
                rpid = rpid,
                rootId = rootId,
                targetTitle = targetTitle,
                createdAtMs = now,
            ),
        )
    }

    suspend fun recordDanmaku(
        aid: Long,
        cid: Long,
        dmid: Long,
        progressMs: Long,
        content: String,
        targetTitle: String = "",
    ) {
        val text = content.trim()
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        val key = "danmaku_${cid}_${if (dmid > 0L) dmid else now}"
        save(
            PublishedRecord(
                key = key,
                kind = PublishedRecordKind.DANMAKU,
                content = text,
                oid = aid,
                cid = cid,
                rpid = dmid,
                progressMs = progressMs.coerceAtLeast(0L),
                targetTitle = targetTitle,
                createdAtMs = now,
            ),
        )
    }

    suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = _records.value.filterNot { it.key == key }
            _records.value = next
            persistLocked(next)
        }
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            BiliClient.json.encodeToString(_records.value)
        }
    }

    /** 从 JSON 导入并按 key 去重合并，返回新增条数。 */
    suspend fun importJson(raw: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val incoming = BiliClient.json.decodeFromString<List<PublishedRecord>>(raw.trim())
            mutex.withLock {
                val existing = _records.value.associateBy { it.key }
                var added = 0
                val merged = LinkedHashMap<String, PublishedRecord>(existing)
                incoming.forEach { item ->
                    if (item.key.isNotBlank() && item.content.isNotBlank() && !merged.containsKey(item.key)) {
                        merged[item.key] = item
                        added++
                    }
                }
                val sorted = merged.values
                    .sortedByDescending { it.createdAtMs }
                    .take(MAX_RECORDS)
                _records.value = sorted
                persistLocked(sorted)
                added
            }
        }
    }

    private suspend fun save(record: PublishedRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = (listOf(record) + _records.value.filterNot { it.key == record.key })
                .sortedByDescending { it.createdAtMs }
                .take(MAX_RECORDS)
            _records.value = next
            persistLocked(next)
        }
    }

    private suspend fun load() {
        mutex.withLock {
            if (!file.isFile) return
            val list = runCatching {
                BiliClient.json.decodeFromString<List<PublishedRecord>>(file.readText())
            }.getOrNull().orEmpty()
            _records.value = list.sortedByDescending { it.createdAtMs }
        }
    }

    private fun persistLocked(list: List<PublishedRecord>) {
        runCatching {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(BiliClient.json.encodeToString(list))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE_NAME = "published_records.json"
        const val MAX_RECORDS = 1000
    }
}
