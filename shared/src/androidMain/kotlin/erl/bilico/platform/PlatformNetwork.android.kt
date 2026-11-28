package erl.bilico.platform

import erl.bilico.data.network.BiliDns
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Protocol
import java.net.InetAddress
import java.util.concurrent.TimeUnit

internal object AndroidBiliDns : Dns {
    @Volatile
    var enabled: Boolean = true

    private val delegate: BiliDns by lazy {
        BiliDns(
            context = AndroidPlatformBridge.requireAppContext(),
            enabledProvider = { enabled },
        )
    }

    override fun lookup(hostname: String): List<InetAddress> = delegate.lookup(hostname)
}

actual fun createPlatformHttpClient(
    block: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            dns(AndroidBiliDns)
            dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                },
            )
            connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        }
    }
    block()
}

actual fun setPlatformHttpDnsEnabled(enabled: Boolean) {
    AndroidBiliDns.enabled = enabled
}
