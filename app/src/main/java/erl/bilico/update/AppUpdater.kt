package erl.bilico.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import erl.bilico.BuildConfig
import erl.bilico.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** 自动检查更新节流间隔：12 小时内已查过则冷启动不再重复请求。 */
const val UpdateCheckInterval = 12 * 60 * 60 * 1000L

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apk: UpdateApk?,
    val pageUrl: String,
    val prerelease: Boolean,
    val publishedAt: String = "",
)

data class UpdateApk(val id: Long, val url: String)

/**
 * GitHub Releases 更新检查与应用内安装包下载器（对齐 Cobo Updater + c001apk InAppUpdater）：
 * - 支持正式版与预发布版（`-pre` / `-beta` / `prerelease=true`）过滤与语义化版本比较
 * - 直连 GitHub Releases + HTTP Range 4 路分片断点续传
 * - 下载完成后校验包名、版本与签名证书，通过 FileProvider 调起系统安装器
 */
object AppUpdater {
    private const val REPO = BuildConfig.UPDATE_REPOSITORY
    const val repositoryUrl = "https://github.com/$REPO"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=20"

    private const val SEGMENTS = 4

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 16
            },
        )
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault(BuildConfig.VERSION_NAME).ifBlank { BuildConfig.VERSION_NAME }
        .removePrefix("v")
        .removePrefix("V")

    suspend fun latest(
        currentVersion: String,
        includePrerelease: Boolean,
    ): UpdateInfo? = findUpdate(releases(includePrerelease), currentVersion)

    fun findUpdate(releases: List<UpdateInfo>, currentVersion: String): UpdateInfo? =
        releases.firstOrNull { it.apk != null && compareVersion(it.version, currentVersion) > 0 }

    suspend fun releases(includePrerelease: Boolean): List<UpdateInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "BiliCO-Android")
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body.string()
        }
        parseReleases(body, includePrerelease)
    }

    internal fun parseReleases(body: String, includePrerelease: Boolean): List<UpdateInfo> {
        val arr = JSONArray(body)
        val candidates = ArrayList<UpdateInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optBoolean("draft", false)) continue
            val tag = item.optString("tag_name").trim()
            val version = tag.removePrefix("v").removePrefix("V")
            if (version.isEmpty()) continue
            val prerelease = item.optBoolean("prerelease", false) || version.contains('-')
            if (prerelease && !includePrerelease) continue
            val apk = selectApk(item.optJSONArray("assets"), version)
            val pageUrl = item.optString("html_url").ifEmpty {
                "$repositoryUrl/releases"
            }
            candidates += UpdateInfo(
                version = version,
                notes = item.optString("body").trim(),
                apk = apk,
                pageUrl = pageUrl,
                prerelease = prerelease,
                publishedAt = item.optString("published_at").trim(),
            )
        }
        return candidates.sortedWith { a, b -> compareVersion(b.version, a.version) }
    }

    private fun selectApk(assets: JSONArray?, version: String): UpdateApk? {
        if (assets == null) return null
        val preferredName = "BiliCO-$version.apk"
        val candidates = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val id = asset.optLong("id")
                val url = asset.optString("browser_download_url")
                if (asset.optString("state") != "uploaded" || id <= 0 ||
                    !name.endsWith(".apk", ignoreCase = true) ||
                    name.contains("unsigned", ignoreCase = true) ||
                    !url.startsWith("$repositoryUrl/releases/download/")
                ) continue
                val apk = UpdateApk(id, url)
                if (name == preferredName) return apk
                add(apk)
            }
        }
        return candidates.singleOrNull()
    }

    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        force: Boolean = false,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val apk = info.apk ?: throw IOException("此版本没有可下载的安装包")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeVersion = info.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir, "BiliCO-$safeVersion-${apk.id}.apk")

        dir.listFiles()?.forEach { file ->
            if (force || (file.name != target.name && !file.name.startsWith("${target.name}."))) {
                file.delete()
            }
        }
        if (target.exists()) {
            try {
                verifyApkOrThrow(context, target, info.version)
                val len = target.length()
                onProgress(1f, len, len)
                return@withContext target
            } catch (_: IOException) {
                target.delete()
            }
        }

        try {
            downloadFromCandidate(apk.url, target, onProgress)
            verifyApkOrThrow(context, target, info.version)
            target
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApkOrThrow(context: Context, file: File, version: String) {
        val pm = context.packageManager
        val signingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(file.absolutePath, signingFlags)
            ?: throw IOException("安装包已损坏、未签名或下载不完整")
        if (archive.packageName != context.packageName) {
            throw IOException("APK 包名与当前应用不一致 (${archive.packageName})")
        }
        val installed = pm.getPackageInfo(context.packageName, signingFlags)
        val signers = archive.currentSigners()
        if (signers.isEmpty() || signers != installed.currentSigners()) {
            throw IOException("安装包签名证书与当前已安装版本不一致")
        }
        if (archive.versionName?.removePrefix("v")?.removePrefix("V") != version) {
            throw IOException("安装包版本与发布标签不一致")
        }
        val archiveCode = PackageInfoCompat.getLongVersionCode(archive)
        val installedCode = PackageInfoCompat.getLongVersionCode(installed)
        if (archiveCode < installedCode ||
            (archiveCode == installedCode && compareVersion(version, installed.versionName.orEmpty()) > 0)
        ) {
            throw IOException("新版安装包的版本号必须高于当前已安装版本")
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.currentSigners(): Set<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners?.toSet().orEmpty()
        } else {
            signatures?.toSet().orEmpty()
        }

    private data class ProbeResult(val total: Long, val ranged: Boolean)

    private fun probe(url: String): ProbeResult {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "BiliCO-Android")
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            if (resp.code == 206) {
                val cr = resp.header("Content-Range").orEmpty()
                val slash = cr.lastIndexOf('/')
                val total = if (slash >= 0) cr.substring(slash + 1).trim().toLongOrNull() ?: -1L else -1L
                if (total > 0L) return ProbeResult(total, true)
            }
            val len = resp.body?.contentLength() ?: -1L
            return ProbeResult(len, false)
        }
    }

    private suspend fun downloadFromCandidate(
        url: String,
        target: File,
        onProgress: (Float, Long, Long) -> Unit,
    ) {
        val info = probe(url)
        if (info.ranged && info.total > SEGMENTS * 256 * 1024L) {
            downloadSegmented(url, target, info.total, onProgress)
        } else {
            val part = File(target.parentFile, "${target.name}.part")
            downloadSingle(url, part, target, onProgress)
        }
    }

    private suspend fun downloadSegmented(
        url: String,
        target: File,
        total: Long,
        onProgress: (Float, Long, Long) -> Unit,
    ) = coroutineScope {
        val parts = (0 until SEGMENTS).map { i -> File(target.parentFile, "${target.name}.part$i") }
        val chunk = total / SEGMENTS
        val ranges = (0 until SEGMENTS).map { i ->
            val start = i * chunk
            val end = if (i == SEGMENTS - 1) total - 1 else (i + 1) * chunk - 1
            start to end
        }

        val counter = AtomicLong(0L)
        for (i in 0 until SEGMENTS) {
            val expected = ranges[i].second - ranges[i].first + 1
            val len = if (parts[i].exists()) parts[i].length() else 0L
            if (len > expected) {
                parts[i].delete()
            } else {
                counter.addAndGet(len)
            }
        }
        onProgress((counter.get().toFloat() / total).coerceIn(0f, 1f), counter.get(), total)

        val lastTick = AtomicLong(0L)
        fun report(force: Boolean = false) {
            val now = System.currentTimeMillis()
            val prev = lastTick.get()
            if (!force && now - prev < 80L) return
            if (!force && !lastTick.compareAndSet(prev, now)) return
            if (force) lastTick.set(now)
            val done = counter.get()
            onProgress((done.toFloat() / total).coerceIn(0f, 1f), done, total)
        }

        (0 until SEGMENTS).map { i ->
            async(Dispatchers.IO) {
                val (start, end) = ranges[i]
                val expected = end - start + 1
                val part = parts[i]
                val existing = if (part.exists()) part.length() else 0L
                if (existing >= expected) return@async
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "BiliCO-Android")
                    .header("Range", "bytes=${start + existing}-$end")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code != 206) throw IOException("分段下载被拒绝 (HTTP ${resp.code})")
                    val body = resp.body ?: throw IOException("空响应")
                    FileOutputStream(part, existing > 0L).use { out ->
                        val buf = ByteArray(64 * 1024)
                        val stream = body.byteStream()
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = stream.read(buf)
                            if (read < 0) break
                            out.write(buf, 0, read)
                            counter.addAndGet(read.toLong())
                            report()
                        }
                    }
                }
                if (part.length() != expected) {
                    throw IOException("分段 $i 长度不符：${part.length()} / $expected")
                }
            }
        }.awaitAll()

        report(force = true)

        val merged = File(target.parentFile, "${target.name}.merged")
        FileOutputStream(merged, false).use { out ->
            val buf = ByteArray(128 * 1024)
            for (part in parts) {
                FileInputStream(part).use { input ->
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                    }
                }
            }
        }
        parts.forEach { it.delete() }
        if (target.exists()) target.delete()
        if (!merged.renameTo(target)) {
            merged.copyTo(target, overwrite = true)
            merged.delete()
        }
        onProgress(1f, total, total)
    }

    private suspend fun downloadSingle(
        url: String,
        part: File,
        target: File,
        onProgress: (Float, Long, Long) -> Unit,
    ) {
        val existing = if (part.exists()) part.length() else 0L
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "BiliCO-Android")
        if (existing > 0L) {
            reqBuilder.header("Range", "bytes=$existing-")
        }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code == 416) {
                part.delete()
                return downloadSingle(url, part, target, onProgress)
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("空响应")
            val append = resp.code == 206 && existing > 0L
            val contentLen = body.contentLength()
            val total = if (contentLen > 0) contentLen + (if (append) existing else 0L) else -1L
            var downloaded = if (append) existing else 0L
            onProgress(if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f, downloaded, total)

            var lastEmit = 0L
            FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(64 * 1024)
                val stream = body.byteStream()
                while (true) {
                    coroutineContext.ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= 80L) {
                        lastEmit = now
                        val p = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                        onProgress(p, downloaded, total)
                    }
                }
            }
            if (total > 0 && downloaded < total) {
                throw IOException("下载未完成：$downloaded / $total")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            onProgress(1f, downloaded, total)
        }
    }

    fun openDownload(context: Context, info: UpdateInfo) {
        val target = info.apk?.url ?: info.pageUrl
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /**
     * 先比主版本数字段；数字一样时正式版高于预发布版（`0.1.2 > 0.1.2-pre`），
     * 点分隔的预发布标识按语义化版本规则比较，例如 pre.12 高于 pre.9。
     */
    fun compareVersion(a: String, b: String): Int {
        val (aCore, aPre) = splitPre(a)
        val (bCore, bPre) = splitPre(b)
        val ap = aCore.split('.').map { it.toIntOrNull() ?: 0 }
        val bp = bCore.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(ap.size, bp.size)
        for (i in 0 until n) {
            val av = ap.getOrElse(i) { 0 }
            val bv = bp.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        if (aPre.isEmpty() && bPre.isEmpty()) return 0
        if (aPre.isEmpty()) return 1
        if (bPre.isEmpty()) return -1
        val aIdentifiers = aPre.split('.')
        val bIdentifiers = bPre.split('.')
        for (i in 0 until minOf(aIdentifiers.size, bIdentifiers.size)) {
            val left = aIdentifiers[i]
            val right = bIdentifiers[i]
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            val order = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (order != 0) return order
        }
        return aIdentifiers.size.compareTo(bIdentifiers.size)
    }

    private fun splitPre(v: String): Pair<String, String> {
        val version = v.removePrefix("v").removePrefix("V").substringBefore('+')
        return version.substringBefore('-') to version.substringAfter('-', "")
    }
}

/**
 * 进程级更新状态持有者（挂在 AppContainer.updater 上）：
 * 统一管理「检查更新中」状态、待展示的更新弹窗及更新日志预览。
 */
class AppUpdateManager(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    var checkingUpdate by mutableStateOf(false)
        private set

    var pendingUpdate by mutableStateOf<UpdateInfo?>(null)
        private set

    var previewingUpdate by mutableStateOf(false)
        private set

    var latestCheckedInfo by mutableStateOf<UpdateInfo?>(null)
        private set

    fun dismissUpdate() {
        pendingUpdate = null
        previewingUpdate = false
    }

    fun previewUpdate(info: UpdateInfo) {
        previewingUpdate = true
        pendingUpdate = info
    }

    suspend fun checkUpdate(manual: Boolean): UpdateInfo? {
        if (checkingUpdate) {
            if (manual) toast("正在检查更新…")
            return pendingUpdate
        }
        checkingUpdate = true
        try {
            val current = AppUpdater.currentVersion(context)
            val includePre = settings.acceptPrerelease.value
            val info = AppUpdater.latest(current, includePrerelease = includePre)
            settings.setUpdateCheckedAt(System.currentTimeMillis())
            latestCheckedInfo = info
            if (info == null) {
                pendingUpdate = null
                if (manual) toast("已是最新版本 v$current")
                return null
            }
            if (!manual && info.version == settings.skippedUpdateVersion.value) {
                return info
            }
            previewingUpdate = false
            pendingUpdate = info
            return info
        } catch (t: Throwable) {
            if (manual) toast(t.message ?: "检查更新失败")
            return null
        } finally {
            checkingUpdate = false
        }
    }

    fun toast(message: String) {
        if (message.isBlank()) return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
