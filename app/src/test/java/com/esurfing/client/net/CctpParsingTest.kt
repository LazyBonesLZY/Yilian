package com.esurfing.client.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 门户报文解析的纯逻辑测试。
 * 这些函数在跟随 BAS 重定向、解析门户配置时被反复使用，
 * 出错的表现是"莫名其妙连不上"，所以值得钉死。
 */
class CctpParsingTest {

    // ---- resolveUrl：Location 头可能是绝对地址、根相对、协议相对或路径相对 ----

    @Test
    fun resolvesAbsoluteUrlAsIs() {
        assertEquals(
            "http://198.51.100.9:7001/ticket.cgi",
            Cctp.resolveUrl("http://223.5.5.5/", "http://198.51.100.9:7001/ticket.cgi"),
        )
    }

    @Test
    fun resolvesRootRelativeAgainstHost() {
        assertEquals(
            "http://10.0.0.1:8080/portal/login",
            Cctp.resolveUrl("http://10.0.0.1:8080/detect/index.html", "/portal/login"),
        )
    }

    @Test
    fun resolvesProtocolRelativeKeepingScheme() {
        assertEquals(
            "http://other.host/x",
            Cctp.resolveUrl("http://10.0.0.1/a/b", "//other.host/x"),
        )
    }

    @Test
    fun resolvesPathRelativeAgainstCurrentDirectory() {
        assertEquals(
            "http://10.0.0.1/a/next.cgi",
            Cctp.resolveUrl("http://10.0.0.1/a/index.html", "next.cgi"),
        )
    }

    /** base 带查询串时，相对路径要接在路径部分后面，不能把 query 也当成路径。 */
    @Test
    fun ignoresQueryWhenResolvingRelative() {
        assertEquals(
            "http://10.0.0.1/a/next.cgi",
            Cctp.resolveUrl("http://10.0.0.1/a/index.html?wlanuserip=1.2.3.4", "next.cgi"),
        )
    }

    @Test
    fun resolvesRootRelativeWhenBaseHasNoPath() {
        assertEquals(
            "http://223.5.5.5/generate204",
            Cctp.resolveUrl("http://223.5.5.5", "/generate204"),
        )
    }

    // ---- urlParam：wlanuserip / wlanacip 是认证的必需参数 ----

    @Test
    fun extractsUrlParams() {
        val url = "http://198.51.100.9:7001/ticket.cgi?wlanacip=198.51.100.7" +
            "&wlanuserip=192.0.2.33&clientmac=aa:bb:cc:dd:ee:ff&vlan=100.0"
        assertEquals("198.51.100.7", Cctp.urlParam(url, "wlanacip"))
        assertEquals("192.0.2.33", Cctp.urlParam(url, "wlanuserip"))
        assertEquals("100.0", Cctp.urlParam(url, "vlan"))
        assertNull(Cctp.urlParam(url, "nonexistent"))
    }

    /** 最后一个参数后面没有 & ，也要能取到。 */
    @Test
    fun extractsLastUrlParam() {
        assertEquals("gd", Cctp.urlParam("http://h/x?a=1&domain=gd", "domain"))
    }

    // ---- tagValue：门户配置里的 URL 都包在 CDATA 里 ----

    @Test
    fun unwrapsCdata() {
        val xml = "<config><auth-url><![CDATA[http://198.51.100.9:7001/auth.cgi]]></auth-url></config>"
        assertEquals("http://198.51.100.9:7001/auth.cgi", Cctp.tagValue(xml, "auth-url"))
    }

    /** 心跳响应里的 interval 是裸文本，没有 CDATA 包裹。 */
    @Test
    fun readsPlainTagValue() {
        assertEquals("120", Cctp.tagValue("<response><interval>120</interval></response>", "interval"))
    }

    @Test
    fun returnsNullForMissingOrEmptyTag() {
        assertNull(Cctp.tagValue("<a><b>1</b></a>", "c"))
        assertNull(Cctp.tagValue("<a><b></b></a>", "b"))
    }

    /** 真实门户配置片段：一次性取出认证要用的三个字段。 */
    @Test
    fun parsesRealPortalConfig() {
        val config = "<config>" +
            "<ticket-url><![CDATA[http://198.51.100.9:7001/ticket.cgi?wlanacip=198.51.100.7" +
            "&wlanuserip=192.0.2.33&clientmac=aa:bb:cc:dd:ee:ff]]></ticket-url>" +
            "<auth-url><![CDATA[http://198.51.100.9:7001/auth.cgi]]></auth-url>" +
            "<state-url><![CDATA[http://198.51.100.9:7001/state.cgi]]></state-url>" +
            "</config>"
        val ticketUrl = Cctp.tagValue(config, "ticket-url")
        assertEquals("http://198.51.100.9:7001/auth.cgi", Cctp.tagValue(config, "auth-url"))
        assertEquals("192.0.2.33", Cctp.urlParam(ticketUrl, "wlanuserip"))
        assertEquals("198.51.100.7", Cctp.urlParam(ticketUrl, "wlanacip"))
    }

    // ---- CDC-Checksum ----

    @Test
    fun md5MatchesKnownVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Cctp.md5Hex(ByteArray(0)))
        assertEquals("0cc175b9c0f1b6a831c399e269772661", Cctp.md5Hex("a".toByteArray()))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Cctp.md5Hex("abc".toByteArray()))
    }

    // ---- 随机身份 ----

    @Test
    fun generatesWellFormedIdentity() {
        val id = Cctp.newIdentity()
        assertEquals(36, id.clientId.length)
        // UUID v4 变体位
        assertEquals('4', id.clientId[14])
        assertEquals(true, id.clientId[19] in "89ab")
        assertEquals(10, id.hostName.length)
        assertEquals(17, id.macAddress.length)
        // MAC 首字节需为单播（最低位为 0）
        val firstOctet = id.macAddress.substring(0, 2).toInt(16)
        assertEquals(0, firstOctet and 1)
    }

    @Test
    fun identitiesAreDistinct() {
        val a = Cctp.newIdentity()
        val b = Cctp.newIdentity()
        assertEquals(false, a.clientId == b.clientId)
        assertEquals(false, a.macAddress == b.macAddress)
    }

    // ---- 心跳响应判定 ----

    /**
     * 这一组是为"界面显示已认证但上不了网"那个 bug 钉的桩。
     * 旧实现只要 HTTP 200 且能解密就算心跳成功，缺少 interval 也照样沿用旧间隔，
     * 于是会话被服务端回收后客户端毫无察觉。
     */
    @Test
    fun acceptsHealthyHeartbeat() {
        val xml = "<response><code>0</code><interval>100</interval></response>"
        assertEquals(false, Cctp.isErrorResponse(xml))
        assertEquals(100L, Cctp.heartbeatInterval(xml))
    }

    @Test
    fun rejectsHeartbeatWithoutInterval() {
        assertNull(Cctp.heartbeatInterval("<response><code>0</code></response>"))
    }

    @Test
    fun rejectsHeartbeatWithNonNumericOrZeroInterval() {
        assertNull(Cctp.heartbeatInterval("<response><interval>abc</interval></response>"))
        assertNull(Cctp.heartbeatInterval("<response><interval>0</interval></response>"))
        assertNull(Cctp.heartbeatInterval("<response><interval>-5</interval></response>"))
    }

    @Test
    fun detectsErrorResponse() {
        // 会话被服务端丢弃时的典型回包
        assertEquals(true, Cctp.isErrorResponse("<response><code>140014</code></response>"))
        assertEquals("140014", Cctp.errorCode("<response><code>140014</code></response>"))
    }

    /** 没有 code 节点的响应不能被误判成失败——部分成功响应确实不带 code。 */
    @Test
    fun treatsMissingCodeAsSuccess() {
        assertEquals(false, Cctp.isErrorResponse("<response><interval>60</interval></response>"))
        assertNull(Cctp.errorCode("<response><interval>60</interval></response>"))
    }

    // ---- 门户拦截页识别 ----

    /**
     * 有的 AC 不回 302，而是直接用 200 把门户页当响应体返回。
     * 认不出来的话状态机会一路掉进"网络错误"分支干等，永远不去认证。
     * 下面的正文取自实机抓包 analysis/runtime_probe/index.body.bin 的特征片段。
     */
    @Test
    fun recognisesPortalInterceptPage() {
        val page = "<HTML><HEAD><TITLE></TITLE><!--//config.campus.js.chinatelecom.com " +
            "<config><ticket-url><![CDATA[http://198.51.100.9:7001/ticket.cgi]]></ticket-url>" +
            "</config>//config.campus.js.chinatelecom.com--></HEAD></HTML>"
        assertEquals(true, Cctp.looksLikePortalPage(page))
    }

    /** 普通网页不能被误判成门户，否则已联网时会被反复拖去认证。 */
    @Test
    fun doesNotMistakeOrdinaryPageForPortal() {
        assertEquals(false, Cctp.looksLikePortalPage("<html><body>hello</body></html>"))
        assertEquals(false, Cctp.looksLikePortalPage(""))
        assertEquals(false, Cctp.looksLikePortalPage(null))
    }

    // ---- 校园网标志 ----

    /** 对应 C 版 get_school_ip_symbol()：取 wlanuserip 的前两段。 */
    @Test
    fun extractsSchoolSymbolFromClientIp() {
        assertEquals("10.23", Cctp.schoolSymbol("10.23.45.67"))
        assertEquals("192.168", Cctp.schoolSymbol("192.168.1.2"))
        assertEquals("255.255", Cctp.schoolSymbol("255.255.255.255"))
    }

    /** 取不到时必须返回 null，而不是拿半截的东西当标志。 */
    @Test
    fun rejectsMalformedClientIp() {
        assertNull("只有一段", Cctp.schoolSymbol("10"))
        assertNull("只有一个点", Cctp.schoolSymbol("10.23"))
        assertNull("空串", Cctp.schoolSymbol(""))
        assertNull(Cctp.schoolSymbol(null))
        // 长度上限跟 C 版 SCHOOL_NETWORK_SYMBOL(8) 的缓冲一致
        assertNull("前两段超过缓冲", Cctp.schoolSymbol("1234.5678.1.1"))
    }

    // ---- 错误码 ----

    @Test
    fun describesKnownErrorCodes() {
        assertEquals("账号或密码错误", com.esurfing.client.core.CctpErrors.describe("140100"))
        assertEquals("无效或失效的 Algo-ID", com.esurfing.client.core.CctpErrors.describe("140013"))
        assertNull(com.esurfing.client.core.CctpErrors.describe("999999"))
    }
}
