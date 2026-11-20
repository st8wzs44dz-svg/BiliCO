package erl.bilico

import android.content.Intent
import android.net.Uri

private val BvidPattern = Regex("BV[0-9A-Za-z]{10}")
private val BareBvidRegex = Regex("(?<![0-9A-Za-z])BV[0-9A-Za-z]{10}(?![0-9A-Za-z])")
private val BiliUrlRegex = Regex(
    "https?://(?:b23\\.tv|bili2233\\.cn|(?:www\\.|m\\.)?bilibili\\.com)/[^\\s\\u4e00-\\u9fa5\\]\\[<>\"'）)]+",
    RegexOption.IGNORE_CASE,
)
private val BiliSchemeRegex = Regex(
    "bilibili://[^\\s\\u4e00-\\u9fa5\\]\\[<>\"'）)]+",
    RegexOption.IGNORE_CASE,
)
private val TrailingLinkPunctuation = charArrayOf('.', ',', ';', ':', '!', '?', ')', '）', '"', '\'', '。', '，', '；', '：', '！', '？')

/**
 * 从外部 VIEW / SEND Intent 或剪贴板文本解析出的 B 站目标：
 * - 普通视频（BV / av，含 `bilibili://video` 与 `bilibili://story`）
 * - 番剧 / 影视（`ep` / `ss`，含网页与 `bilibili://bangumi`）
 * - 短链（`b23.tv` / `bili2233.cn` / `bilibili.com/s/`，需在后台跟一次 302 重定向）
 */
internal class ExternalVideoLink private constructor(
    val bvid: String? = null,
    val aid: Long? = null,
    val seasonId: Long = 0L,
    val epId: Long = 0L,
    val shortUrl: String? = null,
    val startSec: Long = 0L,
    val displayLabel: String = "",
) {
    companion object {
        fun fromIntent(intent: Intent?): ExternalVideoLink? {
            if (intent == null) return null
            // 从系统最近任务拉起被杀掉的进程时，系统会重放冷启动时的外链 Intent，不能再跳进去。
            if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
            return when (intent.action) {
                Intent.ACTION_VIEW -> intent.data?.let(::fromUri)
                Intent.ACTION_SEND -> {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    fromText(text)
                }
                else -> null
            }
        }

        fun fromText(rawText: String?): ExternalVideoLink? {
            val text = rawText?.trim().orEmpty()
            if (text.isEmpty()) return null

            BiliUrlRegex.find(text)?.value?.trimEnd(*TrailingLinkPunctuation)?.let { rawUrl ->
                fromUri(Uri.parse(rawUrl), rawShareText = text)?.let { return it }
            }
            BiliSchemeRegex.find(text)?.value?.trimEnd(*TrailingLinkPunctuation)?.let { rawScheme ->
                fromUri(Uri.parse(rawScheme), rawShareText = text)?.let { return it }
            }
            BareBvidRegex.find(text)?.value?.let { bvid ->
                return ExternalVideoLink(
                    bvid = bvid,
                    displayLabel = extractTitlePreview(text) ?: bvid,
                )
            }
            return null
        }

        fun fromUri(uri: Uri, rawShareText: String? = null): ExternalVideoLink? {
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.pathSegments.filter { it.isNotBlank() }
            val startSec = uri.getQueryParameter("t")?.toLongOrNull()
                ?.takeIf { it in 1..Long.MAX_VALUE / 1000L } ?: 0L
            val titlePreview = rawShareText?.let(::extractTitlePreview)

            return when (scheme) {
                "https", "http" -> when (host) {
                    "b23.tv", "bili2233.cn" -> {
                        val first = path.firstOrNull() ?: return null
                        parseDirectVideoOrPgcId(first, startSec, titlePreview) ?: ExternalVideoLink(
                            shortUrl = uri.toString(),
                            startSec = startSec,
                            displayLabel = titlePreview ?: "$host/$first",
                        )
                    }
                    "www.bilibili.com", "m.bilibili.com" -> when {
                        path.getOrNull(0)?.equals("video", ignoreCase = true) == true -> {
                            val id = path.getOrNull(1) ?: return null
                            parseVideoId(id, startSec, titlePreview, allowBareDigits = false)
                        }
                        path.getOrNull(0)?.equals("bangumi", ignoreCase = true) == true &&
                            path.getOrNull(1)?.equals("play", ignoreCase = true) == true -> {
                            val id = path.getOrNull(2) ?: return null
                            parsePgcId(id, startSec, titlePreview)
                        }
                        path.getOrNull(0)?.equals("s", ignoreCase = true) == true && path.size >= 2 -> {
                            ExternalVideoLink(
                                shortUrl = uri.toString(),
                                startSec = startSec,
                                displayLabel = titlePreview ?: uri.toString(),
                            )
                        }
                        else -> null
                    }
                    else -> null
                }
                "bilibili" -> when (host) {
                    "video", "story" -> {
                        val id = path.firstOrNull()
                            ?: uri.getQueryParameter("bvid")
                            ?: uri.getQueryParameter("aid")
                            ?: return null
                        parseVideoId(id, startSec, titlePreview, allowBareDigits = true)
                    }
                    "bangumi" -> {
                        val epFromQuery = uri.getQueryParameter("ep_id")?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
                        val ssFromQuery = uri.getQueryParameter("season_id")?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
                        if (epFromQuery > 0L || ssFromQuery > 0L) {
                            return ExternalVideoLink(
                                seasonId = ssFromQuery,
                                epId = epFromQuery,
                                startSec = startSec,
                                displayLabel = titlePreview ?: if (epFromQuery > 0L) "ep$epFromQuery" else "ss$ssFromQuery",
                            )
                        }
                        val seasonNumeric = if (path.getOrNull(0)?.equals("season", ignoreCase = true) == true) {
                            path.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
                        } else {
                            0L
                        }
                        if (seasonNumeric > 0L) {
                            return ExternalVideoLink(
                                seasonId = seasonNumeric,
                                startSec = startSec,
                                displayLabel = titlePreview ?: "ss$seasonNumeric",
                            )
                        }
                        path.firstNotNullOfOrNull { seg -> parsePgcId(seg, startSec, titlePreview) }
                    }
                    else -> null
                }
                else -> null
            }
        }

        private fun parseDirectVideoOrPgcId(
            segment: String,
            startSec: Long,
            titlePreview: String?,
        ): ExternalVideoLink? =
            parseVideoId(segment, startSec, titlePreview, allowBareDigits = false)
                ?: parsePgcId(segment, startSec, titlePreview)

        private fun parseVideoId(
            id: String,
            startSec: Long,
            titlePreview: String?,
            allowBareDigits: Boolean,
        ): ExternalVideoLink? {
            val bvid = BvidPattern.find(id)?.value?.takeIf { it == id }
            val aid = when {
                id.startsWith("av", ignoreCase = true) -> id.drop(2)
                allowBareDigits -> id
                else -> ""
            }.takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }
                ?.toLongOrNull()?.takeIf { it > 0L }
            if (bvid == null && aid == null) return null
            return ExternalVideoLink(
                bvid = bvid,
                aid = aid,
                startSec = startSec,
                displayLabel = titlePreview ?: bvid ?: "av$aid",
            )
        }

        private fun parsePgcId(
            id: String,
            startSec: Long,
            titlePreview: String?,
        ): ExternalVideoLink? {
            val lower = id.lowercase()
            return when {
                lower.startsWith("ep") -> {
                    val epId = lower.drop(2).toLongOrNull()?.takeIf { it > 0L } ?: return null
                    ExternalVideoLink(
                        epId = epId,
                        startSec = startSec,
                        displayLabel = titlePreview ?: "ep$epId",
                    )
                }
                lower.startsWith("ss") -> {
                    val seasonId = lower.drop(2).toLongOrNull()?.takeIf { it > 0L } ?: return null
                    ExternalVideoLink(
                        seasonId = seasonId,
                        startSec = startSec,
                        displayLabel = titlePreview ?: "ss$seasonId",
                    )
                }
                else -> null
            }
        }

        /** 从微信/QQ 复制的分享文案（如 `【标题】 https://b23.tv/xxx`）里提取简短标题用于提示条展示。 */
        private fun extractTitlePreview(text: String): String? {
            val stripped = text
                .replace(BiliUrlRegex, "")
                .replace(BiliSchemeRegex, "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('【', '】', '[', ']', '《', '》', '-', '—', ':')
                .trim()
            return stripped.takeIf { it.length >= 2 }?.take(36)
        }
    }
}
