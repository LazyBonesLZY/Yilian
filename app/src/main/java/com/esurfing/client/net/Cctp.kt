package com.esurfing.client.net

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 认证会话里需要随请求携带的身份字段。
 *
 * [osTag] 单独存一份而不是复用 [hostName]：Android 通道两者相同（都是那串随机 hex），
 * 但 iOS / macOS 通道要报成 "iPhone iOS 17.0" 这样的系统标识，与主机名不是一回事。
 */
data class Identity(
    val clientId: String,
    val hostName: String,
    val macAddress: String,
    val osTag: String = hostName,
)

/** CCTP 报文组包与解析，逐字对应 C 版 PlatformUtils.c 的实现。 */
object Cctp {

    const val NULL_ALGO_ID = "00000000-0000-0000-0000-000000000000"

    /** 探测地址，与 C 版相同：重定向即需认证，404/204 即已联网。 */
    const val DETECT_URL = "http://223.5.5.5"

    const val PORTAL_CONFIG_START = "<!--//config.campus.js.chinatelecom.com"
    const val PORTAL_CONFIG_END = "//config.campus.js.chinatelecom.com-->"

    private val random = SecureRandom()

    fun md5Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        val sb = StringBuilder(32)
        for (b in digest) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }

    fun localTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /**
     * 每次认证前重新生成一组随机身份（与 C 版 refresh_states 一致）。
     *
     * MAC 与 Client-ID 始终随机；主机名与 ostag 若通道给了固定值就用它
     * （iOS / macOS 要伪装成真机），否则沿用随机 hex。
     */
    fun newIdentity(fixedHostName: String? = null, fixedOsTag: String? = null): Identity {
        val hostBytes = ByteArray(5).also { random.nextBytes(it) }
        hostBytes[0] = (hostBytes[0].toInt() and 0xFE).toByte()
        val hostName = fixedHostName
            ?: hostBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        val macBytes = ByteArray(6).also { random.nextBytes(it) }
        macBytes[0] = (macBytes[0].toInt() and 0xFE).toByte()
        val mac = macBytes.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }

        val idBytes = ByteArray(16).also { random.nextBytes(it) }
        idBytes[6] = ((idBytes[6].toInt() and 0x0F) or 0x40).toByte()
        idBytes[8] = ((idBytes[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = idBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val clientId = buildString {
            append(hex, 0, 8); append('-')
            append(hex, 8, 12); append('-')
            append(hex, 12, 16); append('-')
            append(hex, 16, 20); append('-')
            append(hex, 20, 32)
        }
        return Identity(clientId, hostName, mac, fixedOsTag ?: hostName)
    }

    fun ticketXml(userAgent: String, identity: Identity, clientIp: String, acIp: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<request>\n")
        append("    <user-agent>").append(userAgent).append("</user-agent>\n")
        append("    <client-id>").append(identity.clientId).append("</client-id>\n")
        append("    <local-time>").append(localTime()).append("</local-time>\n")
        append("    <host-name>").append(identity.hostName).append("</host-name>\n")
        append("    <ipv4>").append(clientIp).append("</ipv4>\n")
        append("    <ipv6></ipv6>\n")
        append("    <mac>").append(identity.macAddress).append("</mac>\n")
        append("    <ostag>").append(identity.osTag).append("</ostag>\n")
        append("    <gwip>").append(acIp).append("</gwip>\n")
        append("</request>\n")
    }

    fun loginXml(
        userAgent: String,
        identity: Identity,
        ticket: String,
        username: String,
        password: String,
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<request>\n")
        append("    <user-agent>").append(userAgent).append("</user-agent>\n")
        append("    <client-id>").append(identity.clientId).append("</client-id>\n")
        append("    <ticket>").append(ticket).append("</ticket>\n")
        append("    <local-time>").append(localTime()).append("</local-time>\n")
        append("    <userid>").append(username).append("</userid>\n")
        append("    <passwd>").append(password).append("</passwd>\n")
        append("</request>\n")
    }

    /** 心跳与登出共用同一份报文。 */
    fun keepAliveXml(
        userAgent: String,
        identity: Identity,
        clientIp: String,
        ticket: String,
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<request>\n")
        append("    <user-agent>").append(userAgent).append("</user-agent>\n")
        append("    <client-id>").append(identity.clientId).append("</client-id>\n")
        append("    <local-time>").append(localTime()).append("</local-time>\n")
        append("    <host-name>").append(identity.hostName).append("</host-name>\n")
        append("    <ipv4>").append(clientIp).append("</ipv4>\n")
        append("    <ticket>").append(ticket).append("</ticket>\n")
        append("    <ipv6></ipv6>\n")
        append("    <mac>").append(identity.macAddress).append("</mac>\n")
        append("    <ostag>").append(identity.osTag).append("</ostag>\n")
        append("</request>\n")
    }

    fun parseTag(xml: String?, tag: String): String? {
        if (xml == null) return null
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val from = start + open.length
        val end = xml.indexOf(close, from)
        if (end < 0 || end == from) return null
        return xml.substring(from, end)
    }

    fun between(text: String?, startTag: String, endTag: String): String? {
        if (text == null) return null
        val start = text.indexOf(startTag)
        if (start < 0) return null
        val from = start + startTag.length
        val end = text.indexOf(endTag, from)
        if (end < 0) return null
        return text.substring(from, end)
    }

    fun cleanCdata(text: String?): String? = between(text, "<![CDATA[", "]]>")

    /**
     * 正文看起来像门户拦截页吗？
     *
     * 用于处理"AC 不回 302 而是直接返回 200 + 门户页"的情形。
     * 只认门户自己的配置标记与 ticket-url 这类特征节点，
     * 避免把正常网页误判成门户。
     */
    fun looksLikePortalPage(body: String?): Boolean {
        val text = body ?: return false
        return text.contains(PORTAL_CONFIG_START) ||
            text.contains("<ticket-url>") ||
            text.contains("config.campus.js.chinatelecom.com")
    }

    /**
     * 响应里的错误码。`0` 表示成功；服务端在会话失效时会回非零码。
     * @return null 表示响应里没有 code 节点（部分成功响应就是不带的）。
     */
    fun errorCode(xml: String?): String? = tagValue(xml, "code")

    /** 响应里的 code 是否表示失败。没有 code 视为成功。 */
    fun isErrorResponse(xml: String?): Boolean {
        val code = errorCode(xml) ?: return false
        return code != "0"
    }

    /**
     * 心跳响应里的下次间隔（秒）。
     *
     * 缺少 `interval` 节点即视为心跳未被接受——C 版 heartbeat() 在解析失败时
     * 直接 return false，这里保持一致。曾经这里写成"解析不到就沿用旧值并当作成功"，
     * 结果会话早被服务端丢掉了，客户端却一直以为心跳正常，界面显示已认证但上不了网。
     */
    fun heartbeatInterval(xml: String?): Long? =
        tagValue(xml, "interval")?.toLongOrNull()?.takeIf { it > 0 }

    /** 取 CDATA 内容，没有 CDATA 包裹时退回原文。 */
    fun tagValue(xml: String?, tag: String): String? {
        val raw = parseTag(xml, tag) ?: return null
        return (cleanCdata(raw) ?: raw).trim().takeIf { it.isNotEmpty() }
    }

    fun urlParam(url: String?, name: String): String? {        if (url == null) return null
        val marker = "$name="
        val start = url.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        var end = from
        while (end < url.length && url[end] != '&' && url[end] != '#') end++
        return url.substring(from, end).takeIf { it.isNotEmpty() }
    }

    /** 把 Location 头的相对地址补全成绝对地址（对应 C 版 resolve_url）。 */
    /**
     * 校园网标志 = wlanuserip 的前两段（如 `10.23`），对应 C 版 get_school_ip_symbol()。
     *
     * 纯展示用：C 版把它放进 web UI 的状态 JSON，用来一眼认出当前接入的是哪个
     * 校区 / 网段。长度上限跟着 C 版 SCHOOL_NETWORK_SYMBOL(8) 的缓冲走；合法 IPv4
     * 的前两段最长也就 `255.255` 七个字符，超了就说明抽到的不是 IP。
     *
     * @return 取不到时返回 null。
     */
    fun schoolSymbol(ip: String?): String? {
        if (ip == null) return null
        val firstDot = ip.indexOf('.')
        val secondDot = if (firstDot < 0) -1 else ip.indexOf('.', firstDot + 1)
        if (secondDot <= 0) return null
        return ip.substring(0, secondDot).takeIf { it.length < 8 }
    }

    fun resolveUrl(base: String, ref: String): String {
        if (ref.isEmpty()) return base
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        if (ref.startsWith("//")) {
            val schemeEnd = base.indexOf("://")
            return if (schemeEnd >= 0) base.substring(0, schemeEnd) + ":" + ref else "http:$ref"
        }
        val schemeEnd = base.indexOf("://")
        val hostStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val pathStart = base.indexOf('/', hostStart)
        if (ref.startsWith("/")) {
            return if (pathStart >= 0) base.substring(0, pathStart) + ref else base + ref
        }
        if (pathStart < 0) return "$base/$ref"
        val queryStart = base.indexOf('?', pathStart)
        val end = if (queryStart >= 0) queryStart else base.length
        val lastSlash = base.lastIndexOf('/', end - 1).coerceAtLeast(pathStart)
        return base.substring(0, lastSlash) + "/" + ref
    }

    /**
     * 首包 ticket.cgi 返回的 ZSM 交付包结构（实测 24898 字节的抓包）：
     *
     * ```
     * 001@<64 位 hex>$<36 字符 Algo-ID>]<二进制载荷...>
     * ```
     *
     * 关键点：Algo-ID 在**头部的 ASCII 区**，`]` 之后紧跟 NUL 与二进制数据。
     * C 版是靠 `strlen` 在第一个 NUL 处截断、再取末尾 37 字节拿到它的，
     * 所以整个包**不能**先按 UTF-8 转字符串再取尾巴（二进制会被替换字符打乱）。
     *
     * 这里按结构直接取 `$` 与 `]` 之间的内容，并保留 C 版的取尾语义作为兜底。
     */
    fun extractAlgoId(body: ByteArray?): String? {
        if (body == null || body.isEmpty()) return null
        // 只看第一个 NUL 之前的 ASCII 头（无 NUL 时限制在合理窗口内）
        val nul = body.indexOf(0)
        val headEnd = if (nul >= 0) nul else minOf(body.size, MAX_HEADER_SCAN)
        if (headEnd < ALGO_ID_LENGTH + 1) return null
        val head = String(body, 0, headEnd, Charsets.US_ASCII)

        val dollar = head.lastIndexOf('$')
        val bracket = head.indexOf(']', startIndex = dollar + 1)
        if (dollar >= 0 && bracket - dollar - 1 == ALGO_ID_LENGTH) {
            val id = head.substring(dollar + 1, bracket)
            if (looksLikeGuid(id)) return id
        }

        // 兜底：与 C 版 load_cipher 一致，取 NUL 截断后的末尾 37 字节的前 36 字节
        if (head.length >= ALGO_ID_LENGTH + 1) {
            val id = head.substring(head.length - ALGO_ID_LENGTH - 1, head.length - 1)
            if (looksLikeGuid(id)) return id
        }
        return null
    }

    /** 交付包头部的可读部分，用于诊断（不含二进制载荷）。 */
    fun deliveryHeader(body: ByteArray?): String {
        if (body == null || body.isEmpty()) return "<空>"
        val nul = body.indexOf(0)
        val end = if (nul >= 0) nul else minOf(body.size, MAX_HEADER_SCAN)
        return String(body, 0, end, Charsets.US_ASCII)
    }

    private fun looksLikeGuid(text: String): Boolean =
        text.length == ALGO_ID_LENGTH &&
            text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '-' } &&
            text.count { it == '-' } == 4

    private const val ALGO_ID_LENGTH = 36
    private const val MAX_HEADER_SCAN = 512
}
