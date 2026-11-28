package erl.bilico.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * 平台 HTTP 客户端构造器：Android 使用 OkHttp 引擎并挂接分层 HTTPDNS / DoH 解析，
 * iOS 使用默认 Darwin 引擎。
 */
expect fun createPlatformHttpClient(
    block: HttpClientConfig<*>.() -> Unit,
): HttpClient

/** 开关平台级 HTTPDNS / DoH 解析（仅 Android 生效）。 */
expect fun setPlatformHttpDnsEnabled(enabled: Boolean)

/** 通过 B 站 gRPC 翻译单条评论（Android 完整支持，iOS 暂不支持）。 */
expect suspend fun platformTranslateBiliReply(
    type: Int,
    oid: Long,
    rpid: Long,
    buvid: String,
    accessToken: String,
    mid: Long,
): Result<String?>
