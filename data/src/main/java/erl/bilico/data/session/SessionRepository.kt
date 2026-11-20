package erl.bilico.data.session

import android.content.Context
import android.webkit.CookieManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import erl.bilico.data.biliDataStore
import erl.bilico.data.model.Account
import erl.bilico.data.model.QrLogin
import erl.bilico.data.model.QrLoginState
import erl.bilico.data.model.QrPollData
import erl.bilico.data.network.AppSignUtils
import erl.bilico.data.network.BiliApi
import erl.bilico.data.network.BuvidManager
import erl.bilico.data.network.BiliClient
import erl.bilico.data.network.PassportApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 登录态：Cookie 由 CookieStore 负责，这里只管账号画像与扫码流程。
 * 启动时先用本地缓存的画像点亮界面，再用 nav 接口校正，避免首屏空白。
 */
class SessionRepository(
    private val context: Context,
    private val cookies: CookieStore,
    private val api: BiliApi,
    private val passportApi: PassportApi,
    private val buvid: BuvidManager,
    private val scope: CoroutineScope,
) {
    private val accounts = AccountStore(context, scope)
    private val accessTokenMutex = Mutex()

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    /** 已登录过的账号名册，最近的在前；切换账号的面板直接读它。 */
    private val _sessions = MutableStateFlow<List<StoredSession>>(emptyList())
    val sessions: StateFlow<List<StoredSession>> = _sessions.asStateFlow()

    init {
        scope.launch {
            _sessions.value = accounts.read()
            restore()
        }
    }

    fun isLoggedIn(): Boolean = !cookies.get("SESSDATA").isNullOrBlank()

    /** 当前账号是不是大会员。清晰度菜单只对大会员展开会员档位。 */
    fun isVip(): Boolean = _account.value?.vip == true

    fun csrf(): String = cookies.get("bili_jct").orEmpty()

    fun mid(): Long = cookies.get("DedeUserID")?.toLongOrNull() ?: 0L

    fun hasAccessToken(): Boolean = !cookies.get("access_token").isNullOrBlank()

    fun webCookies(): Map<String, String> = cookies.snapshot()

    suspend fun generateQr(): Result<QrLogin> = runCatching {
        val response = passportApi.generateQrCode()
        if (response.code != 0) error(response.message.ifBlank { "二维码获取失败(${response.code})" })
        val data = response.data ?: error("二维码数据为空")
        val key = data.qrcodeKey?.takeIf { it.isNotBlank() } ?: error("二维码 key 为空")
        val url = data.url?.takeIf { it.isNotBlank() } ?: error("二维码内容为空")
        QrLogin(key = key, url = url)
    }

    suspend fun pollQr(key: String): Result<Pair<QrLoginState, QrPollData?>> = runCatching {
        val response = passportApi.pollQrCode(key)
        val data = response.data
        val state = when (data?.code) {
            0 -> QrLoginState.SUCCESS
            86090 -> QrLoginState.SCANNED
            86038 -> QrLoginState.EXPIRED
            else -> QrLoginState.WAITING
        }
        if (state == QrLoginState.SUCCESS) {
            clearTokens()
            buvid.ensure()
            refreshProfile()?.let { upsertCurrentSession(it) }
        }
        state to data
    }

    /** 网页登录完成后把 WebView 的 Cookie 写入 CookieStore 并拉取账号信息。 */
    suspend fun loginWithCookies(cookieMap: Map<String, String>): Result<Account> = runCatching {
        val sessData = cookieMap["SESSDATA"]?.trim().orEmpty()
        require(sessData.isNotEmpty()) { "未检测到登录凭证（SESSDATA）" }
        clearTokens()
        cookies.putAll(cookieMap)
        buvid.ensure()
        val account = refreshProfile() ?: error("读取账号信息失败，请重试")
        upsertCurrentSession(account)
        account
    }

    /** nav 是唯一可信的登录态来源；-101 时清掉失效 Cookie。 */
    suspend fun refreshProfile(): Account? {
        return runCatching {
            buvid.ensure()
            val response = api.nav()
            val data = response.data
            if (response.code == 0 && data != null && data.isLogin && data.mid > 0L) {
                val account = Account(
                    mid = data.mid,
                    name = data.uname,
                    face = data.face,
                    vip = data.vipStatus == 1,
                )
                _account.value = account
                runCatching { persistAccount(account) }
                account
            } else {
                if (response.code == -101) clearSession()
                _account.value
            }
        }.getOrNull()
    }

    /**
     * 退出当前账号。
     *
     * 先给当前账号留档（下次还能切回来），再清掉当前 Cookie。
     * 名册里还有别的号就直接换过去：只退出一个号不该把用户丢回扫码页，剩下的号还要能继续用；
     * 一个别的号都没有才算真的退出。
     */
    suspend fun logout() {
        val currentMid = _account.value?.mid ?: mid()
        upsertCurrentSession()
        clearSession()
        val next = _sessions.value.firstOrNull {
            it.mid != currentMid && it.cookies.isNotEmpty()
        } ?: return
        switchAccount(next.mid)
    }

    /**
     * 切到名册里的另一个账号：把它的 Cookie 整份换回来，再校正画像。
     * 换不回来就保持原账号不动。
     */
    suspend fun switchAccount(mid: Long): Boolean {
        if (mid == _account.value?.mid && isLoggedIn()) return true
        val target = _sessions.value.firstOrNull { it.mid == mid } ?: return false
        if (target.cookies.isEmpty()) return false
        upsertCurrentSession()
        cookies.replaceAll(target.cookies)
        if (target.accessToken.isNotBlank()) {
            saveTokens(target.accessToken, target.refreshToken)
        } else {
            clearTokens()
        }
        val account = refreshProfile()
        if (account == null || account.mid != mid) {
            // 目标会话已失效：Cookie 已经换掉了，只能按退出处理，避免停在半登录态。
            clearSession()
            return false
        }
        upsertCurrentSession(account)
        return true
    }

    /** 从名册里删掉一个账号；删的正好是当前账号就一并退出。 */
    suspend fun removeAccount(mid: Long) {
        val activeMid = _account.value?.mid ?: this.mid()
        if (mid == activeMid) clearSession()
        _sessions.value = _sessions.value.filterNot { it.mid == mid }
        accounts.write(_sessions.value)
    }

    /** 把当前 Cookie 连同画像与 App token 存进名册；同一个 mid 覆盖旧的一份并排到最前。 */
    private suspend fun upsertCurrentSession(account: Account? = _account.value) {
        val current = account ?: return
        val snapshot = cookies.snapshot()
        if (snapshot.isEmpty()) return
        val (accessToken, refreshToken) = readTokens()
        val stored = StoredSession(
            mid = current.mid,
            name = current.name,
            face = current.face,
            cookies = snapshot,
            savedAt = System.currentTimeMillis(),
            accessToken = accessToken.orEmpty(),
            refreshToken = refreshToken.orEmpty(),
        )
        _sessions.value = (listOf(stored) + _sessions.value.filterNot { it.mid == current.mid })
            .take(MAX_STORED_ACCOUNTS)
        accounts.write(_sessions.value)
    }

    /**
     * 获取当前账号的 App 端 `access_key`：
     * 优先读本地缓存；缺失或要求强制刷新时，尝试刷新或用当前 Web Cookie 静默换取（参考 PiliPlus）。
     */
    suspend fun ensureAccessToken(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        accessTokenMutex.withLock {
            if (!isLoggedIn()) return@withLock null
            val (cachedAccess, cachedRefresh) = readTokens()
            if (!forceRefresh && !cachedAccess.isNullOrBlank()) {
                return@withLock cachedAccess
            }

            if (forceRefresh && !cachedAccess.isNullOrBlank() && !cachedRefresh.isNullOrBlank()) {
                val refreshed = runCatching {
                    passportApi.refreshToken(
                        AppSignUtils.signForAndroidHdApi(
                            mapOf(
                                "access_key" to cachedAccess,
                                "refresh_token" to cachedRefresh,
                            ),
                        ),
                    )
                }.getOrNull()
                val refreshedData = refreshed?.data
                if (refreshed?.code == 0 && refreshedData != null && refreshedData.accessToken.isNotBlank()) {
                    saveTokens(refreshedData.accessToken, refreshedData.refreshToken)
                    upsertCurrentSession()
                    return@withLock refreshedData.accessToken
                }
            }

            val biliJct = csrf()
            if (biliJct.isBlank()) return@withLock null

            val exchanged = runCatching {
                val authResp = passportApi.generateTvQrCode(
                    AppSignUtils.signForAndroidHdApi(
                        mapOf(
                            "local_id" to "0",
                            "platform" to "android",
                            "mobi_app" to "android_hd",
                        ),
                    ),
                )
                val authCode = authResp.data?.authCode?.takeIf { authResp.code == 0 && it.isNotBlank() }
                    ?: return@runCatching null

                val confirmResp = passportApi.confirmTvQrCode(
                    mapOf(
                        "auth_code" to authCode,
                        "csrf" to biliJct,
                        "scanning_type" to "1",
                    ),
                )
                if (confirmResp.code != 0) return@runCatching null

                val pollResp = passportApi.pollTvQrCode(
                    AppSignUtils.signForAndroidHdApi(
                        mapOf(
                            "auth_code" to authCode,
                            "local_id" to "0",
                        ),
                    ),
                )
                val pollData = pollResp.data
                if (pollResp.code != 0 || pollData == null || pollData.accessToken.isBlank()) {
                    return@runCatching null
                }
                pollData.accessToken to pollData.refreshToken
            }.getOrNull() ?: return@withLock null

            saveTokens(exchanged.first, exchanged.second)
            upsertCurrentSession()
            exchanged.first
        }
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        context.biliDataStore.edit {
            it[KEY_ACCESS_TOKEN] = accessToken
            it[KEY_REFRESH_TOKEN] = refreshToken
        }
    }

    private suspend fun clearTokens() {
        context.biliDataStore.edit {
            it.remove(KEY_ACCESS_TOKEN)
            it.remove(KEY_REFRESH_TOKEN)
        }
    }

    suspend fun readTokens(): Pair<String?, String?> {
        val prefs = context.biliDataStore.data.first()
        return prefs[KEY_ACCESS_TOKEN] to prefs[KEY_REFRESH_TOKEN]
    }

    private suspend fun restore() {
        val prefs = context.biliDataStore.data.first()
        prefs[KEY_ACCOUNT]?.let { raw ->
            runCatching {
                BiliClient.json.decodeFromString<Account>(raw)
            }.getOrNull()?.let { _account.value = it }
        }
        if (isLoggedIn()) refreshProfile()?.let { upsertCurrentSession(it) }
    }

    private suspend fun persistAccount(account: Account) {
        val encoded = BiliClient.json.encodeToString(account)
        context.biliDataStore.edit { it[KEY_ACCOUNT] = encoded }
    }

    private suspend fun clearSession() {
        cookies.clear()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        context.biliDataStore.edit {
            it.remove(KEY_ACCOUNT)
            it.remove(KEY_ACCESS_TOKEN)
            it.remove(KEY_REFRESH_TOKEN)
        }
        _account.value = null
    }

    private companion object {
        val KEY_ACCOUNT = stringPreferencesKey("account")
        val KEY_ACCESS_TOKEN = stringPreferencesKey("tv_access_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("tv_refresh_token")

        /** 名册上限：够来回切几个号就行，不做无限堆积。 */
        const val MAX_STORED_ACCOUNTS = 8
    }
}
