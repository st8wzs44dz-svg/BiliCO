package erl.bilico.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

actual fun createPlatformHttpClient(
    block: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(block)

actual fun setPlatformHttpDnsEnabled(enabled: Boolean) = Unit

actual suspend fun platformTranslateBiliReply(
    type: Int,
    oid: Long,
    rpid: Long,
    buvid: String,
    accessToken: String,
    mid: Long,
): Result<String?> = Result.failure(UnsupportedOperationException("iOS 暂不支持评论翻译"))
