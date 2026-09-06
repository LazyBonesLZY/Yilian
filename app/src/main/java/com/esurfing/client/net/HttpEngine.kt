package com.esurfing.client.net

import android.net.Network
import com.esurfing.client.core.AppLog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

enum class NetStatus {
    /** 已联网（204 或探测地址 404）。 */
    SUCCESS,

    /** 200，带响应体。 */
    HAVE_RES,

    /** 3xx，需要认证。 */
    REDIRECT,

    NOT_FOUND,
    TIMEOUT,
    ERROR,
}

data class HttpResponse(
    val status: NetStatus,
    val code: Int = 0,
    val body: String? = null,
    /** 原始响应字节。首包 ZSM 交付包是二进制，必须按字节解析而不能先转字符串。 */
    val bodyBytes: ByteArray? = null,
    val location: String? = null,
)

/** 门户在重定向链里下发的可选头，只记录第一次出现的值（与 C 版 header_cb 一致）。 */
class PortalHeaders {
    @Volatile
    var schoolId: String = ""

    @Volatile
    var domain: String = ""

    @Volatile
    var area: String = ""

    fun reset() {
        schoolId = ""
        domain = ""
        area = ""
    }
}

/**
 * CCTP 用的最小 HTTP 客户端。不跟随重定向（认证流程要自己解析 Location），
 * 可以绑定到指定 Network，避免请求走到蜂窝网络上。
 */
class HttpEngine(private val portalHeaders: PortalHeaders) {

    /** 非空时所有请求都绑定到该网络（通常是 Wi-Fi）。 */
    @Volatile
    var network: Network? = null

    fun get(url: String, userAgent: String, clientId: String): HttpResponse =
        execute(url, null, userAgent, clientId, null)

    fun post(url: String, body: String, userAgent: String, clientId: String, algoId: String): HttpResponse =
        execute(url, body, userAgent, clientId, algoId)

    private fun execute(
        url: String,
        body: String?,
        userAgent: String,
        clientId: String,
        algoId: String?,
    ): HttpResponse {
        var conn: HttpURLConnection? = null
        try {
            val target = URL(url)
            val raw = network?.openConnection(target) ?: target.openConnection()
            conn = (raw as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Connection", "close")
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", ACCEPT)
                setRequestProperty("Client-ID", clientId)
            }
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                val payload = body.toByteArray(Charsets.UTF_8)
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("CDC-Checksum", Cctp.md5Hex(payload))
                conn.setRequestProperty("Algo-ID", algoId.orEmpty())
                conn.setRequestProperty("CDC-SchoolId", portalHeaders.schoolId)
                conn.setRequestProperty("CDC-Domain", portalHeaders.domain)
                conn.setRequestProperty("CDC-Area", portalHeaders.area)
                conn.setFixedLengthStreamingMode(payload.size)
                conn.outputStream.use { it.write(payload) }
            }

            val code = conn.responseCode
            captureHeaders(conn)
            val location = conn.getHeaderField("Location")
            val rawBody = readBody(conn)

            val status = when {
                code == 204 -> NetStatus.SUCCESS
                code == 200 -> NetStatus.HAVE_RES
                code == 404 -> NetStatus.NOT_FOUND
                code in 300..399 && !location.isNullOrBlank() -> NetStatus.REDIRECT
                else -> {
                    AppLog.error("HTTP 响应错误, 响应码: $code")
                    NetStatus.ERROR
                }
            }
            return HttpResponse(
                status = status,
                code = code,
                body = rawBody?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() },
                bodyBytes = rawBody,
                location = location?.let { Cctp.resolveUrl(url, it) },
            )
        } catch (e: SocketTimeoutException) {
            AppLog.warn("请求超时: $url")
            return HttpResponse(NetStatus.TIMEOUT)
        } catch (e: Exception) {
            AppLog.error("请求失败: $url (${e.javaClass.simpleName}: ${e.message})")
            return HttpResponse(NetStatus.ERROR)
        } finally {
            conn?.disconnect()
        }
    }

    private fun captureHeaders(conn: HttpURLConnection) {
        if (portalHeaders.schoolId.isEmpty()) {
            conn.getHeaderField("schoolid")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                portalHeaders.schoolId = it
                AppLog.info("School Id: $it")
            }
        }
        if (portalHeaders.domain.isEmpty()) {
            conn.getHeaderField("domain")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                portalHeaders.domain = it
                AppLog.info("Domain: $it")
            }
        }
        if (portalHeaders.area.isEmpty()) {
            conn.getHeaderField("area")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                portalHeaders.area = it
                AppLog.info("Area: $it")
            }
        }
    }

    /**
     * 读取响应体，带长度上限。
     *
     * 正常响应最大的是 ZSM 交付包（实测约 25 KB），门户页也就几十 KB。
     * 加上限是为了不让异常/恶意的超长响应把进程撑爆——这里读的是
     * 认证前的网络，响应内容并不可信。
     */
    private fun readBody(conn: HttpURLConnection): ByteArray? {
        val stream: InputStream = try {
            conn.inputStream
        } catch (e: Exception) {
            conn.errorStream ?: return null
        }
        return runCatching {
            stream.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (buffer.size() + n > MAX_BODY_BYTES) {
                        AppLog.warn("响应体超过 ${MAX_BODY_BYTES / 1024} KB, 已截断")
                        buffer.write(chunk, 0, MAX_BODY_BYTES - buffer.size())
                        break
                    }
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val ACCEPT = "text/html,text/xml,application/xhtml+xml,application/x-javascript,*/*"
    }
}
