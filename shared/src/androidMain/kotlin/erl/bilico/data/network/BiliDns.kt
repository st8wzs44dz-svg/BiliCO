package erl.bilico.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Log
import okhttp3.Dns
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 分层 HTTPDNS / DoH 解析器：
 * - B 站业务及图床域名（.bilibili.com / .hdslb.com）：B 站 HTTPDNS → 腾讯 HTTPDNS → Cloudflare DoH → 系统 DNS
 * - 视频 CDN 域名（.bilivideo.com / .bilivideo.cn / .akamaized.net）：阿里 HTTPDNS → Cloudflare DoH → 系统 DNS
 * - 其余域名直走系统 DNS
 *
 * 带本地 TTL 缓存、后台刷新去重及切网自动失效，降低弱网/运营商劫持下的首屏与起播延迟。
 */
class BiliDns(
    context: Context,
    private val enabledProvider: () -> Boolean = { true },
) : Dns {

    private enum class DomainType { HTTPDNS, VIDEO_CDN, OTHER }

    private data class DnsResult(
        val addresses: List<InetAddress>,
        val ttlSeconds: Long,
    )

    private data class CacheEntry(
        val addresses: List<InetAddress>,
        val expireAtMs: Long,
    )

    private data class NetworkState(
        val networkHandle: Long,
        val operatorCode: String?,
        val hasIpv6: Boolean,
    )

    private val appContext = context.applicationContext
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val refreshExecutor: ExecutorService = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "bilico-dns").apply { isDaemon = true }
    }
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var networkState: NetworkState? = null

    init {
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        refreshExecutor.execute { onNetworkChanged(network) }
                    }

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                        refreshExecutor.execute { onNetworkChanged(network) }
                    }

                    override fun onLost(network: Network) {
                        refreshExecutor.execute {
                            networkState = null
                            cache.clear()
                        }
                    }
                },
            )
        }
    }

    /** 启动期异步预热高频业务域名与图床域名，减少首屏建连等待。 */
    fun prefetch() {
        if (!enabledProvider()) return
        refreshExecutor.execute {
            PREFETCH_DOMAINS.forEach { host ->
                runCatching { lookup(host) }
            }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trim().lowercase()
        if (host.isEmpty()) throw UnknownHostException("Empty hostname")
        if (!enabledProvider() || isIpLiteral(host)) {
            return Dns.SYSTEM.lookup(host)
        }
        val domainType = classify(host)
        if (domainType == DomainType.OTHER) {
            return Dns.SYSTEM.lookup(host)
        }

        val now = System.currentTimeMillis()
        val cached = cache[host]
        if (cached != null) {
            if (now >= cached.expireAtMs) {
                triggerAsyncRefresh(host, domainType)
            }
            return orderByNetwork(cached.addresses)
        }

        val resolved = resolveRemote(host, domainType)
        if (resolved != null && resolved.addresses.isNotEmpty()) {
            putCache(host, domainType, resolved)
            return orderByNetwork(resolved.addresses)
        }
        return Dns.SYSTEM.lookup(host)
    }

    private fun triggerAsyncRefresh(host: String, type: DomainType) {
        if (!refreshing.add(host)) return
        refreshExecutor.execute {
            try {
                resolveRemote(host, type)?.takeIf { it.addresses.isNotEmpty() }?.let {
                    putCache(host, type, it)
                }
            } finally {
                refreshing.remove(host)
            }
        }
    }

    private fun putCache(host: String, type: DomainType, result: DnsResult) {
        val ttlMs = (result.ttlSeconds.coerceIn(10L, 600L) * 1000L).let { raw ->
            if (type == DomainType.HTTPDNS) maxOf(raw, MIN_HTTPDNS_TTL_MS) else raw
        }
        if (cache.size > MAX_CACHE_SIZE) cache.clear()
        cache[host] = CacheEntry(result.addresses, System.currentTimeMillis() + ttlMs)
    }

    private fun resolveRemote(host: String, type: DomainType): DnsResult? {
        val operator = readNetworkState().operatorCode
        return when (type) {
            DomainType.HTTPDNS -> {
                resolveBiliHttpDns(host, operator)
                    ?: resolveTencentHttpDns(host)
                    ?: resolveCloudflareDoh(host)
            }
            DomainType.VIDEO_CDN -> {
                resolveAlibabaHttpDns(host)
                    ?: resolveCloudflareDoh(host)
            }
            DomainType.OTHER -> null
        }
    }

    private fun orderByNetwork(addresses: List<InetAddress>): List<InetAddress> {
        val ipv6 = readNetworkState().hasIpv6
        val v4 = addresses.filterIsInstance<Inet4Address>()
        val v6 = addresses.filterIsInstance<Inet6Address>()
        return when {
            !ipv6 && v4.isNotEmpty() -> v4
            v4.isNotEmpty() && v6.isNotEmpty() -> v4 + v6
            else -> addresses
        }
    }

    private fun onNetworkChanged(network: Network) {
        val handle = runCatching { network.networkHandle }.getOrDefault(NO_NETWORK_HANDLE)
        val prev = networkState
        val next = queryNetworkState(network, handle)
        networkState = next
        if (prev == null || prev.networkHandle != next.networkHandle || prev.hasIpv6 != next.hasIpv6) {
            cache.clear()
        }
    }

    private fun readNetworkState(): NetworkState {
        networkState?.let { return it }
        val active = runCatching { connectivityManager?.activeNetwork }.getOrNull()
        val handle = runCatching { active?.networkHandle }.getOrNull() ?: NO_NETWORK_HANDLE
        return queryNetworkState(active, handle).also { networkState = it }
    }

    private fun queryNetworkState(network: Network?, handle: Long): NetworkState {
        val caps = runCatching { network?.let { connectivityManager?.getNetworkCapabilities(it) } }.getOrNull()
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val operator = if (isCellular) {
            runCatching {
                val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                tm?.networkOperator?.takeIf { it.length >= 5 }
            }.getOrNull()
        } else {
            null
        }
        val hasIpv6 = runCatching {
            val props = network?.let { connectivityManager?.getLinkProperties(it) }
            props?.linkAddresses?.any { addr ->
                val ip = addr.address
                ip is Inet6Address && !ip.isLinkLocalAddress && !ip.isLoopbackAddress
            } ?: false
        }.getOrDefault(false)
        return NetworkState(handle, operator, hasIpv6)
    }

    private fun classify(host: String): DomainType = when {
        VIDEO_CDN_SUFFIXES.any { host.endsWith(it) } -> DomainType.VIDEO_CDN
        HTTPDNS_SUFFIXES.any { host.endsWith(it) || host == it.removePrefix(".") } -> DomainType.HTTPDNS
        else -> DomainType.OTHER
    }

    private fun isIpLiteral(host: String): Boolean =
        host.all { it.isDigit() || it == '.' } || host.contains(':')

    // ---------- Providers ----------

    private fun resolveBiliHttpDns(hostname: String, operatorCode: String?): DnsResult? {
        val sign = md5Hex(hostname + BILI_SIGN_SUFFIX)
        val servers = when {
            operatorCode == null -> BILI_DEFAULT_SERVERS
            operatorCode.startsWith("46003") || operatorCode.startsWith("46005") || operatorCode.startsWith("46011") -> BILI_CT_SERVERS
            operatorCode.startsWith("46001") || operatorCode.startsWith("46006") || operatorCode.startsWith("46009") -> BILI_CU_SERVERS
            operatorCode.startsWith("46000") || operatorCode.startsWith("46002") || operatorCode.startsWith("46004") ||
                operatorCode.startsWith("46007") || operatorCode.startsWith("46008") || operatorCode.startsWith("46012") -> BILI_CM_SERVERS
            else -> BILI_DEFAULT_SERVERS
        }
        for (server in servers) {
            runCatching {
                val body = httpGet("http://$server/resolve?host=$hostname&sign=$sign&query=4,6")
                parseJsonDns(body)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun resolveAlibabaHttpDns(hostname: String): DnsResult? {
        for (server in ALI_SERVERS) {
            runCatching {
                val body = httpGet("http://$server/$ALI_ACCOUNT_ID/resolve?host=$hostname&query=4,6")
                parseJsonDns(body)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun resolveTencentHttpDns(hostname: String): DnsResult? = runCatching {
        val encrypted = desEncryptHex(hostname, TENCENT_DES_KEY)
        val body = httpGet("http://$TENCENT_SERVER/d?dn=$encrypted&id=$TENCENT_ID&ttl=1")
        if (body.isBlank()) return@runCatching null
        val plain = desDecryptHex(body.trim(), TENCENT_DES_KEY)
        val comma = plain.lastIndexOf(',')
        if (comma <= 0) return@runCatching null
        val ips = plain.substring(0, comma).split(';').mapNotNull { ip ->
            runCatching { InetAddress.getByName(ip.trim()) }.getOrNull()
        }
        val ttl = plain.substring(comma + 1).trim().toLongOrNull() ?: FALLBACK_TTL_SEC
        if (ips.isEmpty()) null else DnsResult(ips.distinct(), ttl)
    }.getOrNull()

    private fun resolveCloudflareDoh(hostname: String): DnsResult? = runCatching {
        val body = httpGet(
            url = "https://1.1.1.1/dns-query?name=$hostname&type=A",
            headers = mapOf("accept" to "application/dns-json"),
        )
        val json = JSONObject(body)
        if (json.optInt("Status", -1) != 0) return@runCatching null
        val answers = json.optJSONArray("Answer") ?: return@runCatching null
        val addresses = ArrayList<InetAddress>(answers.length())
        var minTtl = Long.MAX_VALUE
        for (i in 0 until answers.length()) {
            val item = answers.getJSONObject(i)
            val type = item.optInt("type")
            if (type == 1 || type == 28) {
                val ip = item.optString("data")
                if (ip.isBlank()) continue
                val ttl = item.optLong("TTL", FALLBACK_TTL_SEC)
                if (ttl < minTtl) minTtl = ttl
                runCatching { InetAddress.getByName(ip) }.getOrNull()?.let(addresses::add)
            }
        }
        val distinct = addresses.distinct()
        if (distinct.isEmpty()) null else DnsResult(distinct, if (minTtl == Long.MAX_VALUE) FALLBACK_TTL_SEC else minTtl)
    }.onFailure {
        Log.d(TAG, "DoH resolve failed for $hostname: ${it.message}")
    }.getOrNull()

    private fun parseJsonDns(response: String): DnsResult? {
        val array = JSONObject(response).optJSONArray("dns") ?: return null
        val addresses = ArrayList<InetAddress>(4)
        var minTtl = Long.MAX_VALUE
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val ttl = entry.optLong("ttl", FALLBACK_TTL_SEC)
            if (ttl < minTtl) minTtl = ttl
            val ips = entry.optJSONArray("ips") ?: continue
            for (j in 0 until ips.length()) {
                runCatching { InetAddress.getByName(ips.getString(j)) }.getOrNull()?.let(addresses::add)
            }
        }
        val distinct = addresses.distinct()
        if (distinct.isEmpty()) return null
        return DnsResult(distinct, if (minTtl == Long.MAX_VALUE) FALLBACK_TTL_SEC else minTtl)
    }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                requestMethod = "GET"
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun desEncryptHex(input: String, key: String): String {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "DES"))
        return cipher.doFinal(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun desDecryptHex(hex: String, key: String): String {
        val bytes = ByteArray(hex.length / 2) { idx ->
            ((Character.digit(hex[idx * 2], 16) shl 4) + Character.digit(hex[idx * 2 + 1], 16)).toByte()
        }
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "DES"))
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "BiliDns"
        const val HTTP_TIMEOUT_MS = 1000
        const val FALLBACK_TTL_SEC = 60L
        const val MIN_HTTPDNS_TTL_MS = 120_000L
        const val MAX_CACHE_SIZE = 256
        const val NO_NETWORK_HANDLE = -1L

        const val BILI_SIGN_SUFFIX = "*-Bili-dns^*-Http"
        val BILI_DEFAULT_SERVERS = listOf("116.63.10.135", "117.185.17.235")
        val BILI_CT_SERVERS = listOf("122.9.13.79", "180.163.55.134")
        val BILI_CU_SERVERS = listOf("116.63.10.31", "112.65.200.25")
        val BILI_CM_SERVERS = listOf("117.185.18.212", "117.185.17.235")

        const val ALI_ACCOUNT_ID = "191607"
        val ALI_SERVERS = listOf(
            "203.119.206.8",
            "203.119.206.9",
            "203.119.204.80",
            "203.119.238.240",
            "203.119.238.194",
        )

        const val TENCENT_SERVER = "119.29.29.29"
        const val TENCENT_ID = "3092"
        const val TENCENT_DES_KEY = "LkgBm3xj"

        val PREFETCH_DOMAINS = listOf(
            "api.bilibili.com",
            "app.bilibili.com",
            "api.live.bilibili.com",
            "i0.hdslb.com",
            "i1.hdslb.com",
            "i2.hdslb.com",
        )
        val VIDEO_CDN_SUFFIXES = listOf(".bilivideo.com", ".bilivideo.cn", ".akamaized.net")
        val HTTPDNS_SUFFIXES = listOf(".bilibili.com", ".bilibili.cn", ".hdslb.com", ".hdslb.net")
    }
}
