package erl.bilico.data.repository

import erl.bilico.data.model.TripleData
import erl.bilico.data.model.UpCardSummary
import erl.bilico.data.model.VideoRelationData
import erl.bilico.data.network.BiliApi
import erl.bilico.data.network.BuvidManager
import erl.bilico.data.session.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视频页互动：点赞、投币、收藏、稍后再看、关注 UP。
 * 全部依赖登录 Cookie 与 bili_jct，未登录时由界面直接拦下，不走到这里。
 */
class InteractionRepository(
    private val api: BiliApi,
    private val buvid: BuvidManager,
    private val session: SessionRepository,
) {
    suspend fun relation(aid: Long): Result<VideoRelationData> = withContext(Dispatchers.IO) {
        runCatching {
            buvid.ensure()
            val response = api.videoRelation(aid)
            if (response.code != 0) error(response.message.ifBlank { "互动状态获取失败(${response.code})" })
            response.data ?: VideoRelationData()
        }
    }

    suspend fun like(aid: Long, liked: Boolean): Result<Unit> = post {
        api.likeVideo(
            mapOf(
                "aid" to aid.toString(),
                "like" to if (liked) "1" else "2",
                "csrf" to csrf(),
            )
        )
    }

    /** 点踩走同一个接口，3 点踩、4 取消点踩；点赞与点踩互斥由服务端处理。 */
    suspend fun dislike(aid: Long, disliked: Boolean): Result<Unit> = post {
        api.likeVideo(
            mapOf(
                "aid" to aid.toString(),
                "like" to if (disliked) "3" else "4",
                "csrf" to csrf(),
            )
        )
    }

    /** UP 主粉丝数；拿不到就返回 null，界面上直接不显示这一段。 */
    suspend fun upFollowers(mid: Long): Long? = withContext(Dispatchers.IO) {
        if (mid <= 0L) return@withContext null
        runCatching {
            buvid.ensure()
            api.relationStat(mid).data?.follower
        }.getOrNull()
    }

    /** UP 主名片摘要（粉丝数 + 官方认证），优先走 x/web-interface/card。 */
    suspend fun upCardSummary(mid: Long): UpCardSummary? = withContext(Dispatchers.IO) {
        if (mid <= 0L) return@withContext null
        runCatching {
            buvid.ensure()
            val data = api.memberCard(mid).data
            val card = data?.card
            val official = card?.resolvedOfficial()
            val followers = when {
                (data?.follower ?: 0L) > 0L -> data?.follower ?: 0L
                (card?.fans ?: 0L) > 0L -> card?.fans ?: 0L
                else -> api.relationStat(mid).data?.follower ?: 0L
            }
            UpCardSummary(
                followers = followers,
                officialType = official?.resolvedType() ?: -1,
                officialDesc = official?.resolvedDesc().orEmpty(),
            )
        }.getOrNull()
    }

    suspend fun coin(aid: Long, count: Int): Result<Unit> = post {
        api.coinVideo(
            mapOf(
                "aid" to aid.toString(),
                "multiply" to count.coerceIn(1, 2).toString(),
                "select_like" to "0",
                "csrf" to csrf(),
            )
        )
    }

    suspend fun triple(aid: Long): Result<TripleData> = withContext(Dispatchers.IO) {
        runCatching {
            buvid.ensure()
            require(csrf().isNotBlank()) { "登录后可用" }
            val response = api.tripleVideo(
                mapOf(
                    "aid" to aid.toString(),
                    "csrf" to csrf(),
                )
            )
            if (response.code != 0) {
                error(response.message.ifBlank { "一键三连失败(${response.code})" })
            }
            response.data ?: TripleData(like = true, coin = true, fav = true, multiply = 2)
        }
    }

    suspend fun favorite(
        aid: Long,
        addMediaIds: List<Long>,
        delMediaIds: List<Long>,
    ): Result<Unit> = post {
        api.favoriteResource(
            mapOf(
                "rid" to aid.toString(),
                "type" to "2",
                "add_media_ids" to addMediaIds.joinToString(","),
                "del_media_ids" to delMediaIds.joinToString(","),
                "csrf" to csrf(),
            )
        )
    }

    /**
     * 后台静默一键切换默认收藏夹状态，返回操作后该视频是否仍处于已收藏状态。
     * 未收藏时加入默认收藏夹；已收藏时优先移出默认收藏夹（若仅存在于其他自定义收藏夹则一并移出）。
     */
    suspend fun toggleDefaultFavorite(aid: Long, targetFavorited: Boolean): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                buvid.ensure()
                val token = csrf()
                require(token.isNotBlank()) { "登录后可用" }
                val mid = session.mid().takeIf { it > 0L } ?: (session.account.value?.mid ?: 0L)
                require(mid > 0L) { "登录后可用" }

                val foldersResp = api.favFolders(mid = mid, rid = aid, type = 2)
                if (foldersResp.code != 0) {
                    error(foldersResp.message.ifBlank { "收藏夹加载失败(${foldersResp.code})" })
                }
                val folders = foldersResp.data?.list.orEmpty()
                val defaultFolder = folders.firstOrNull { (it.attr and 2) == 0 }
                    ?: folders.firstOrNull { it.title == "默认收藏夹" }
                    ?: folders.firstOrNull()
                val defaultMediaId = defaultFolder?.let { it.id.takeIf { id -> id > 0L } ?: it.fid } ?: 0L
                val containingIds = folders.mapNotNull { folder ->
                    if (folder.fav_state != 1) return@mapNotNull null
                    (folder.id.takeIf { it > 0L } ?: folder.fid).takeIf { it > 0L }
                }

                if (targetFavorited) {
                    require(defaultMediaId > 0L) { "未找到默认收藏夹" }
                    if (defaultMediaId !in containingIds) {
                        val resp = api.favoriteResource(
                            mapOf(
                                "rid" to aid.toString(),
                                "type" to "2",
                                "add_media_ids" to defaultMediaId.toString(),
                                "del_media_ids" to "",
                                "csrf" to token,
                            )
                        )
                        if (resp.code != 0) {
                            error(resp.message.ifBlank { "收藏失败(${resp.code})" })
                        }
                    }
                    true
                } else {
                    val delIds = when {
                        defaultMediaId > 0L && defaultMediaId in containingIds -> listOf(defaultMediaId)
                        containingIds.isNotEmpty() -> containingIds
                        else -> emptyList()
                    }
                    if (delIds.isNotEmpty()) {
                        val resp = api.favoriteResource(
                            mapOf(
                                "rid" to aid.toString(),
                                "type" to "2",
                                "add_media_ids" to "",
                                "del_media_ids" to delIds.joinToString(","),
                                "csrf" to token,
                            )
                        )
                        if (resp.code != 0) {
                            error(resp.message.ifBlank { "取消收藏失败(${resp.code})" })
                        }
                    }
                    containingIds.any { it !in delIds }
                }
            }
        }

    suspend fun addToWatchLater(aid: Long): Result<Unit> = post {
        api.addWatchLater(
            mapOf(
                "aid" to aid.toString(),
                "csrf" to csrf(),
            )
        )
    }

    suspend fun follow(mid: Long, following: Boolean): Result<Unit> = post {
        api.modifyRelation(
            mapOf(
                "fid" to mid.toString(),
                "act" to if (following) "1" else "2",
                "csrf" to csrf(),
                "re_src" to "11",
            )
        )
    }

    suspend fun isFollowing(mid: Long): Boolean = withContext(Dispatchers.IO) {
        if (mid <= 0L) return@withContext false
        runCatching {
            buvid.ensure()
            api.userRelation(mid).data?.attribute?.let { it == 2 || it == 6 } ?: false
        }.getOrDefault(false)
    }

    private suspend fun post(block: suspend () -> erl.bilico.data.model.BaseResponse): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                buvid.ensure()
                require(csrf().isNotBlank()) { "登录后可用" }
                val response = block()
                if (response.code != 0) {
                    error(response.message.ifBlank { "操作失败(${response.code})" })
                }
            }
        }

    private fun csrf(): String = session.csrf()
}
