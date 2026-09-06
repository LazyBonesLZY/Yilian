package com.esurfing.client.core.cipher

/**
 * 会话加解密接口。明文 XML 加密后以大写 hex 作为 HTTP body，
 * 响应 body 同样是 hex，解密后得到 XML。
 */
interface SessionCipher {
    fun encrypt(text: String): String?
    fun decrypt(hex: String): String?
}

private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

fun ByteArray.toHexUpper(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX_CHARS[v ushr 4]
        out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
    }
    return String(out)
}

fun String.hexToBytesOrNull(): ByteArray? {
    val text = trim()
    if (text.length % 2 != 0 || text.isEmpty()) return null
    val out = ByteArray(text.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(text[i * 2], 16)
        val lo = Character.digit(text[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

internal fun readBe32(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 24) or
        ((data[offset + 1].toInt() and 0xFF) shl 16) or
        ((data[offset + 2].toInt() and 0xFF) shl 8) or
        (data[offset + 3].toInt() and 0xFF)

internal fun writeBe32(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value ushr 24).toByte()
    data[offset + 1] = (value ushr 16).toByte()
    data[offset + 2] = (value ushr 8).toByte()
    data[offset + 3] = value.toByte()
}

/** 去掉尾部零填充并转成字符串（对应 C 版的 zero-padding 处理）。 */
internal fun ByteArray.stripZeroTailToString(): String {
    var len = size
    while (len > 0 && this[len - 1] == 0.toByte()) len--
    return String(this, 0, len, Charsets.UTF_8)
}
