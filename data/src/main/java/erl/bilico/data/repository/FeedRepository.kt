package erl.bilico.data.repository

import erl.bilico.data.model.FeedVideo
import erl.bilico.data.model.RecommendItem
import erl.bilico.data.network.AppSignUtils
import erl.bilico.data.network.BiliApi
import erl.bilico.data.network.BuvidManager
import erl.bilico.data.network.WbiKeyManager
import erl.bilico.data.network.WbiUtils
import erl.bilico.data.session.SessionRepository
import erl.bilico.data.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 推荐流「不感兴趣」反馈类型，参考 PiliPlus 复刻 App 端反馈与点踩接口。 */
enum class FeedDislikeAction {
    NOT_INTERESTED,
    BLOCK_UP,
    DISLIKE_VIDEO,
}

class FeedRepository(
    private val api: BiliApi,
    private val wbi: WbiKeyManager,
    private val buvid: BuvidManager,
    private val session: SessionRepository,
) {
    /**
     * 首页推荐：`fresh_idx` 既是页号也是去重种子，翻页时递增。
     * 只保留普通稿件（goto=av），直播/番剧卡片这一轮不展示。
     */
    suspend fun recommend(page: Int): Result<List<FeedVideo>> = withContext(Dispatchers.IO) {
        runCatching {
            buvid.ensure()
            val keys = wbi.getKeys()
            val index = page.coerceAtLeast(1).toString()
            val params = mapOf(
                "ps" to "20",
                "fresh_idx" to index,
                "fresh_idx_1h" to index,
                "feed_version" to "V8",
                "fresh_type" to "3",
                "homepage_ver" to "1",
                "web_location" to "1430650",
            )
            val response = api.recommend(WbiUtils.sign(params, keys.first, keys.second))
            if (response.code != 0) {
                error(response.message.ifBlank { "推荐加载失败(${response.code})" })
            }
            response.data?.item.orEmpty()
                .filter { it.goto == "av" || it.goto.isNullOrBlank() }
                .mapNotNull { it.toFeedVideo() }
        }
    }

    /**
     * 推荐流不感兴趣 / 不想看该 UP 主 / 点踩：
     * 使用当前登录 Cookie 静默换取的 App `access_key` + `ANDROID_HD` 签名上报。
     */
    suspend fun dislike(
        video: FeedVideo,
        action: FeedDislikeAction,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(video.aid > 0L) { "无效的稿件 ID" }
            var token = session.ensureAccessToken()
                ?: error("获取 App 凭证失败，请重新登录")
            var response = executeDislike(video, action, token)
            if (response.code == -101) {
                token = session.ensureAccessToken(forceRefresh = true)
                    ?: error("登录凭证已失效，请重新登录")
                response = executeDislike(video, action, token)
            }
            if (response.code != 0) {
                error(response.message.ifBlank { "反馈失败(${response.code})" })
            }
        }
    }

    private suspend fun executeDislike(
        video: FeedVideo,
        action: FeedDislikeAction,
        accessKey: String,
    ) = when (action) {
        FeedDislikeAction.NOT_INTERESTED -> api.feedDislike(
            AppSignUtils.signForAndroidHdApi(
                mapOf(
                    "access_key" to accessKey,
                    "goto" to "av",
                    "id" to video.aid.toString(),
                    "reason_id" to "1",
                    "build" to "1",
                    "mobi_app" to "android",
                ),
            ),
        )

        FeedDislikeAction.BLOCK_UP -> api.feedDislike(
            AppSignUtils.signForAndroidHdApi(
                buildMap {
                    put("access_key", accessKey)
                    put("goto", "av")
                    put("id", video.aid.toString())
                    put("reason_id", "4")
                    if (video.upMid > 0L) put("mid", video.upMid.toString())
                    put("build", "1")
                    put("mobi_app", "android")
                },
            ),
        )

        FeedDislikeAction.DISLIKE_VIDEO -> api.viewDislike(
            AppSignUtils.signForAndroidHdApi(
                mapOf(
                    "access_key" to accessKey,
                    "aid" to video.aid.toString(),
                    "dislike" to "0",
                ),
            ),
        )
    }

    private fun RecommendItem.toFeedVideo(): FeedVideo? {
        val videoBvid = bvid?.takeIf { it.isNotBlank() } ?: return null
        val cover = pic?.takeIf { it.isNotBlank() } ?: return null
        val viewCount = stat?.view ?: 0L
        val seconds = (duration ?: 0).toLong().coerceAtLeast(0L)
        return FeedVideo(
            bvid = videoBvid,
            aid = id,
            title = title.orEmpty(),
            cover = Formatters.normalizeImageUrl(cover),
            upName = owner?.name.orEmpty(),
            upMid = owner?.mid ?: 0L,
            upFace = Formatters.normalizeImageUrl(owner?.face),
            playCount = viewCount,
            danmaku = stat?.danmaku ?: 0L,
            durationSec = duration ?: 0,
            playCountText = Formatters.formatStat(viewCount),
            danmakuText = Formatters.formatStat(stat?.danmaku ?: 0L),
            durationText = Formatters.formatDuration(seconds),
        )
    }
}
