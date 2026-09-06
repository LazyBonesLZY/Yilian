package com.esurfing.client.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Algo-ID 解析的回归测试。
 *
 * 头部样本按实机抓包的结构构造：首包 ticket.cgi 的响应是 ASCII 头 + NUL + 二进制载荷，
 * `]` 之前的 36 个字符才是 Algo-ID（其中的 64 位十六进制串是一次性会话材料，
 * 这里换成了合成值）。
 * 早期实现把整个两万多字节的包按 UTF-8 转字符串再取末尾 37 字符，
 * 拿到的是二进制尾巴的乱码，于是必然报"不支持的 Algo-ID"。
 */
class CctpAlgoIdTest {

    private val sampleHeader =
        "001@3F1A0C7E5B2D48960A7C1E3F5D8B2A46C0E91D73B85F2A4C6E08D19B3C7A5E24" +
            "\$FF5F345F-B580-43DF-9ED3-D92FAE586133]"

    /** 还原交付包：ASCII 头 + NUL + 二进制载荷。 */
    private fun deliveryPacket(header: String, payloadSize: Int = 4096): ByteArray {
        val head = header.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(payloadSize) { (it * 31 + 7).toByte() }
        return head + byteArrayOf(0, 0, 0, 1) + payload
    }

    @Test
    fun parsesAlgoIdFromDeliveryHeader() {
        val id = Cctp.extractAlgoId(deliveryPacket(sampleHeader))
        assertEquals("FF5F345F-B580-43DF-9ED3-D92FAE586133", id)
    }

    /** 载荷里含高位字节时，按字符串解析会错位，按字节解析不受影响。 */
    @Test
    fun unaffectedByNonUtf8Payload() {
        val head = sampleHeader.toByteArray(Charsets.US_ASCII)
        val nasty = ByteArray(2048) { 0xC3.toByte() }
        val id = Cctp.extractAlgoId(head + byteArrayOf(0) + nasty)
        assertEquals("FF5F345F-B580-43DF-9ED3-D92FAE586133", id)
    }

    /** 没有 `]` 时退回 C 版 load_cipher 的取尾语义。 */
    @Test
    fun fallsBackToTailSemantics() {
        val header = "001@DEADBEEF\$07E824B2-9E5C-4D1B-BBB0-5E07C251E4AA]"
        assertEquals(
            "07E824B2-9E5C-4D1B-BBB0-5E07C251E4AA",
            Cctp.extractAlgoId(deliveryPacket(header)),
        )
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Cctp.extractAlgoId(null))
        assertNull(Cctp.extractAlgoId(ByteArray(0)))
        assertNull(Cctp.extractAlgoId(ByteArray(64) { 0x41 }))
    }

    @Test
    fun headerIsReadableForDiagnostics() {
        val text = Cctp.deliveryHeader(deliveryPacket(sampleHeader))
        assertEquals(sampleHeader, text)
    }
}
