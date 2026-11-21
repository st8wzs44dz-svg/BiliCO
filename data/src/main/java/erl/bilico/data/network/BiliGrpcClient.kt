package erl.bilico.data.network

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 轻量级 B 站 gRPC over OkHttp 客户端：不引入重型 grpc-netty 与 protobuf-javalite，
 * 手写 5 字节 gRPC 帧头编解码与最小 protobuf wire 读写器，供评论翻译等 gRPC 专属能力使用。
 */
class BiliGrpcClient(
    private val okHttp: OkHttpClient,
    private val buvidProvider: () -> String,
    private val accessTokenProvider: () -> String,
    private val midProvider: () -> Long,
) {
    /**
     * 调用 `bilibili.main.community.reply.v1.Reply/TranslateReply` 翻译单条评论。
     * 返回译文；服务端未返回译文时返回 null。
     */
    suspend fun translateReply(
        type: Int,
        oid: Long,
        rpid: Long,
    ): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val reqBytes = ProtoWriter().apply {
                writeVarintField(1, type.toLong())
                writeVarintField(2, oid)
                writeVarintField(3, rpid)
            }.toByteArray()

            val respBytes = call(TRANSLATE_REPLY_ENDPOINT, reqBytes)
            parseTranslateReply(respBytes, rpid)
        }
    }

    private fun call(endpoint: String, protobufBytes: ByteArray): ByteArray {
        val (frame, compressed) = encodeFrame(protobufBytes)
        val buvid = buvidProvider().ifBlank { DEFAULT_BUVID }
        val token = accessTokenProvider()
        val mid = midProvider()
        val reqBuilder = Request.Builder()
            .url("https://app.bilibili.com/$endpoint")
            .post(frame.toRequestBody(GRPC_MEDIA_TYPE))
            .header("accept", "*/*")
            .header("app-key", "android64")
            .header("bili-http-engine", "ignet")
            .header("buvid", buvid)
            .header("content-type", "application/grpc")
            .header("env", "prod")
            .header("grpc-accept-encoding", "identity, gzip")
            .header("user-agent", buildGrpcUserAgent())
            .header("x-bili-device-bin", encodeBase64(buildDeviceBin(buvid)))
            .header("x-bili-fawkes-req-bin", encodeBase64(buildFawkesBin()))
            .header("x-bili-locale-bin", encodeBase64(buildLocaleBin()))
            .header("x-bili-metadata-bin", encodeBase64(buildMetadataBin(token, buvid)))
            .header("x-bili-network-bin", encodeBase64(buildNetworkBin()))
            .header("x-bili-trace-id", generateTraceId())
        if (compressed) {
            reqBuilder.header("grpc-encoding", "gzip")
        }
        if (token.isNotBlank()) {
            reqBuilder.header("authorization", "identify_v1 $token")
        }
        if (mid > 0L) {
            reqBuilder.header("x-bili-mid", mid.toString())
        }

        okHttp.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                error("gRPC HTTP ${response.code}")
            }
            val grpcStatus = response.header("grpc-status")
            if (!grpcStatus.isNullOrBlank() && grpcStatus != "0") {
                val msg = response.header("grpc-message").orEmpty()
                error(msg.ifBlank { "翻译失败（gRPC $grpcStatus）" })
            }
            val raw = response.body.bytes()
            return decodeFrame(raw, response.header("grpc-encoding"))
        }
    }

    /**
     * 解析 `TranslateReplyResp`:
     *   field 1: map<int64, ReplyInfo> translated_replies
     *     map entry: field 1 = key(rpid), field 2 = ReplyInfo
     *       ReplyInfo: field 17 = Content translated_content (fallback field 12 = content)
     *         Content: field 1 = string message
     */
    private fun parseTranslateReply(data: ByteArray, targetRpid: Long): String? {
        if (data.isEmpty()) return null
        val reader = ProtoReader(data, 0, data.size)
        var fallback: String? = null
        while (reader.pos < reader.end) {
            val tag = reader.readTag() ?: break
            val field = tag ushr 3
            val wire = tag and 0x07
            if (field == 1 && wire == 2) {
                val len = reader.readVarint()?.toInt() ?: break
                if (len < 0 || reader.pos + len > reader.end) break
                val (key, text) = parseMapEntry(data, reader.pos, reader.pos + len)
                reader.pos += len
                if (!text.isNullOrBlank()) {
                    if (key == targetRpid) return text.trim()
                    if (fallback == null) fallback = text.trim()
                }
            } else if (!reader.skip(wire)) {
                break
            }
        }
        return fallback
    }

    private fun parseMapEntry(data: ByteArray, start: Int, end: Int): Pair<Long, String?> {
        var key = 0L
        var translated: String? = null
        val reader = ProtoReader(data, start, end)
        while (reader.pos < reader.end) {
            val tag = reader.readTag() ?: break
            val field = tag ushr 3
            val wire = tag and 0x07
            when {
                field == 1 && wire == 0 -> key = reader.readVarint() ?: break
                field == 2 && wire == 2 -> {
                    val len = reader.readVarint()?.toInt() ?: break
                    if (len < 0 || reader.pos + len > reader.end) break
                    translated = parseReplyInfoTranslated(data, reader.pos, reader.pos + len)
                    reader.pos += len
                }
                else -> if (!reader.skip(wire)) break
            }
        }
        return key to translated
    }

    private fun parseReplyInfoTranslated(data: ByteArray, start: Int, end: Int): String? {
        val reader = ProtoReader(data, start, end)
        while (reader.pos < reader.end) {
            val tag = reader.readTag() ?: break
            val field = tag ushr 3
            val wire = tag and 0x07
            if (field == 17 && wire == 2) {
                val len = reader.readVarint()?.toInt() ?: break
                if (len < 0 || reader.pos + len > reader.end) break
                val msg = parseContentMessage(data, reader.pos, reader.pos + len)
                reader.pos += len
                if (!msg.isNullOrBlank()) return msg
            } else if (!reader.skip(wire)) {
                break
            }
        }
        return null
    }

    private fun parseContentMessage(data: ByteArray, start: Int, end: Int): String? {
        val reader = ProtoReader(data, start, end)
        while (reader.pos < reader.end) {
            val tag = reader.readTag() ?: break
            val field = tag ushr 3
            val wire = tag and 0x07
            if (field == 1 && wire == 2) {
                return reader.readString()
            } else if (!reader.skip(wire)) {
                break
            }
        }
        return null
    }

    // ---------- gRPC 5-byte frame codec ----------

    private fun encodeFrame(protobufBytes: ByteArray): Pair<ByteArray, Boolean> {
        val compress = protobufBytes.size > COMPRESSION_THRESHOLD
        val payload = if (compress) {
            ByteArrayOutputStream(protobufBytes.size).also { bos ->
                GZIPOutputStream(bos).use { it.write(protobufBytes) }
            }.toByteArray()
        } else {
            protobufBytes
        }
        val out = ByteArrayOutputStream(5 + payload.size)
        out.write(if (compress) 1 else 0)
        out.write((payload.size ushr 24) and 0xFF)
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
        return out.toByteArray() to compress
    }

    private fun decodeFrame(raw: ByteArray, grpcEncoding: String?): ByteArray {
        if (raw.size < 5) return ByteArray(0)
        val compressed = raw[0].toInt() != 0
        val declaredLen = ((raw[1].toInt() and 0xFF) shl 24) or
            ((raw[2].toInt() and 0xFF) shl 16) or
            ((raw[3].toInt() and 0xFF) shl 8) or
            (raw[4].toInt() and 0xFF)
        val end = if (declaredLen in 0..(raw.size - 5)) 5 + declaredLen else raw.size
        val payload = raw.copyOfRange(5, end)
        return if (!compressed) {
            payload
        } else if (grpcEncoding == null || grpcEncoding.equals("gzip", ignoreCase = true)) {
            GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
        } else {
            error("不支持的 gRPC 压缩格式: $grpcEncoding")
        }
    }

    // ---------- Minimal Bilibili metadata protobuf builders ----------

    private fun buildMetadataBin(accessKey: String, buvid: String): ByteArray = ProtoWriter().apply {
        if (accessKey.isNotBlank()) writeStringField(1, accessKey)
        writeStringField(2, "android")
        writeStringField(3, "android")
        writeVarintField(4, APP_BUILD)
        writeStringField(5, "bili")
        writeStringField(6, buvid)
        writeStringField(7, "android")
    }.toByteArray()

    private fun buildDeviceBin(buvid: String): ByteArray = ProtoWriter().apply {
        writeVarintField(1, 1)
        writeVarintField(2, APP_BUILD)
        writeStringField(3, buvid)
        writeStringField(4, "android")
        writeStringField(5, "android")
        writeStringField(6, "bili")
        writeStringField(7, Build.BRAND.orEmpty())
        writeStringField(8, Build.MODEL.orEmpty())
        writeStringField(9, Build.VERSION.RELEASE.orEmpty())
    }.toByteArray()

    private fun buildLocaleBin(): ByteArray {
        val localeIds = ProtoWriter().apply {
            writeStringField(1, "zh")
            writeStringField(3, "CN")
        }.toByteArray()
        return ProtoWriter().apply {
            writeBytesField(1, localeIds)
            writeBytesField(2, localeIds)
            writeStringField(3, "Asia/Shanghai")
        }.toByteArray()
    }

    private fun buildNetworkBin(): ByteArray = ProtoWriter().apply {
        writeVarintField(1, 1) // WIFI
    }.toByteArray()

    private fun buildFawkesBin(): ByteArray = ProtoWriter().apply {
        writeStringField(1, "android64")
        writeStringField(2, "prod")
    }.toByteArray()

    private fun buildGrpcUserAgent(): String =
        "Dalvik/2.1.0 (Linux; U; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
            "8.43.0 os/android model/${Build.MODEL} mobi_app/android build/$APP_BUILD " +
            "channel/bili innerVer/$APP_BUILD osVer/${Build.VERSION.RELEASE} network/2"

    private fun generateTraceId(): String {
        val hex = UUID.randomUUID().toString().replace("-", "")
        return "$hex:${hex.takeLast(16)}:0:0"
    }

    private fun encodeBase64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING)

    // ---------- Minimal protobuf wire writer/reader ----------

    private class ProtoWriter {
        private val out = ByteArrayOutputStream(64)

        fun writeVarintField(field: Int, value: Long) {
            writeVarint(((field shl 3) or 0).toLong())
            writeVarint(value)
        }

        fun writeStringField(field: Int, value: String) {
            writeBytesField(field, value.toByteArray(StandardCharsets.UTF_8))
        }

        fun writeBytesField(field: Int, bytes: ByteArray) {
            writeVarint(((field shl 3) or 2).toLong())
            writeVarint(bytes.size.toLong())
            out.write(bytes)
        }

        private fun writeVarint(v: Long) {
            var value = v
            while (true) {
                if ((value and 0x7FL.inv()) == 0L) {
                    out.write(value.toInt())
                    return
                }
                out.write(((value and 0x7FL) or 0x80L).toInt())
                value = value ushr 7
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class ProtoReader(
        private val data: ByteArray,
        var pos: Int,
        val end: Int,
    ) {
        fun readVarint(): Long? {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (pos >= end) return null
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return null
        }

        fun readTag(): Int? {
            val tag = (readVarint() ?: return null).toInt()
            return if (tag ushr 3 > 0) tag else null
        }

        fun readString(): String? {
            val len = (readVarint() ?: return null).toInt()
            if (len < 0 || pos + len > end) return null
            val text = String(data, pos, len, StandardCharsets.UTF_8)
            pos += len
            return text
        }

        fun skip(wireType: Int): Boolean = when (wireType) {
            0 -> readVarint() != null
            1 -> advance(8)
            2 -> {
                val len = (readVarint() ?: return false).toInt()
                len >= 0 && advance(len)
            }
            5 -> advance(4)
            else -> false
        }

        private fun advance(count: Int): Boolean {
            if (count < 0 || pos + count > end) return false
            pos += count
            return true
        }
    }

    private companion object {
        const val APP_BUILD = 8430300L
        const val COMPRESSION_THRESHOLD = 1000
        const val DEFAULT_BUVID = "XY00000000000000000000000000000000000"
        const val TRANSLATE_REPLY_ENDPOINT = "bilibili.main.community.reply.v1.Reply/TranslateReply"
        val GRPC_MEDIA_TYPE = "application/grpc".toMediaType()
    }
}
