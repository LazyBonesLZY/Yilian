package com.esurfing.client.core.cipher

private const val DES_BLOCK = 8

private fun zeroPadTo8(data: ByteArray): ByteArray {
    var total = (data.size + 7) and 0x7.inv()
    if (total == 0) total = DES_BLOCK
    val out = ByteArray(total)
    data.copyInto(out)
    return out
}

/**
 * AD8BB5B0-0E72-4198-A362-96D52C1B7ED1：六层 DES-ECB。
 * 每个 8 字节块依次 E(K4) D(K5) E(K6) E(K1) D(K2) E(K3)，K1..K6 为 48 字节密钥的六段。
 */
internal class DesEcbSixCipher(key: ByteArray) : SessionCipher {

    private val key = key.copyOf(48)

    override fun encrypt(text: String): String {
        val buf = zeroPadTo8(text.toByteArray(Charsets.UTF_8))
        val a = ByteArray(8)
        val b = ByteArray(8)
        var off = 0
        while (off < buf.size) {
            DesCore.encryptBlock(buf, off, a, 0, key, 24)
            DesCore.decryptBlock(a, 0, b, 0, key, 32)
            DesCore.encryptBlock(b, 0, a, 0, key, 40)
            DesCore.encryptBlock(a, 0, b, 0, key, 0)
            DesCore.decryptBlock(b, 0, a, 0, key, 8)
            DesCore.encryptBlock(a, 0, buf, off, key, 16)
            off += 8
        }
        return buf.toHexUpper()
    }

    override fun decrypt(hex: String): String? {
        val data = hex.hexToBytesOrNull() ?: return null
        if (data.size % 8 != 0 || data.isEmpty()) return null
        val buf = data.copyOf()
        val a = ByteArray(8)
        val b = ByteArray(8)
        var off = 0
        while (off < buf.size) {
            DesCore.decryptBlock(buf, off, a, 0, key, 16)
            DesCore.encryptBlock(a, 0, b, 0, key, 8)
            DesCore.decryptBlock(b, 0, a, 0, key, 0)
            DesCore.decryptBlock(a, 0, b, 0, key, 40)
            DesCore.encryptBlock(b, 0, a, 0, key, 32)
            DesCore.decryptBlock(a, 0, buf, off, key, 24)
            off += 8
        }
        return buf.stripZeroTailToString()
    }
}

/**
 * 9ABF4D29-34DB-4CE9-BB8C-7E371D637758：双重 3DES-CBC。
 * 第一轮用 key[24..47]，第二轮用 key[0..23]，两轮各自从 IV 重新链接。
 */
internal class DesEdeDoubleCbcCipher(key: ByteArray, iv: ByteArray) : SessionCipher {

    private val key = key.copyOf(48)
    private val iv = iv.copyOf(DES_BLOCK)

    private fun cbcPass(buf: ByteArray, keyBase: Int) {
        val prev = iv.copyOf()
        val t = ByteArray(8)
        val a = ByteArray(8)
        val b = ByteArray(8)
        var off = 0
        while (off < buf.size) {
            for (i in 0 until 8) t[i] = (buf[off + i].toInt() xor prev[i].toInt()).toByte()
            DesCore.encryptBlock(t, 0, a, 0, key, keyBase)
            DesCore.decryptBlock(a, 0, b, 0, key, keyBase + 8)
            DesCore.encryptBlock(b, 0, buf, off, key, keyBase + 16)
            buf.copyInto(prev, 0, off, off + 8)
            off += 8
        }
    }

    private fun cbcPassInverse(buf: ByteArray, keyBase: Int) {
        val a = ByteArray(8)
        val b = ByteArray(8)
        val c = ByteArray(8)
        var off = buf.size - 8
        while (off >= 0) {
            DesCore.decryptBlock(buf, off, a, 0, key, keyBase + 16)
            DesCore.encryptBlock(a, 0, b, 0, key, keyBase + 8)
            DesCore.decryptBlock(b, 0, c, 0, key, keyBase)
            for (i in 0 until 8) {
                val prev = if (off == 0) iv[i] else buf[off - 8 + i]
                buf[off + i] = (c[i].toInt() xor prev.toInt()).toByte()
            }
            off -= 8
        }
    }

    override fun encrypt(text: String): String {
        val buf = zeroPadTo8(text.toByteArray(Charsets.UTF_8))
        cbcPass(buf, 24)
        cbcPass(buf, 0)
        return buf.toHexUpper()
    }

    override fun decrypt(hex: String): String? {
        val data = hex.hexToBytesOrNull() ?: return null
        if (data.size % 8 != 0 || data.isEmpty()) return null
        val buf = data.copyOf()
        cbcPassInverse(buf, 0)
        cbcPassInverse(buf, 24)
        return buf.stripZeroTailToString()
    }
}
