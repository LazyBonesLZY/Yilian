package com.esurfing.client.core.cipher

private fun pkcs7Pad(data: ByteArray): ByteArray {
    val pad = Sm4Core.BLOCK_SIZE - (data.size % Sm4Core.BLOCK_SIZE)
    val out = ByteArray(data.size + pad)
    data.copyInto(out)
    out.fill(pad.toByte(), data.size, out.size)
    return out
}

private fun pkcs7Strip(data: ByteArray): ByteArray? {
    val pad = data[data.size - 1].toInt() and 0xFF
    if (pad < 1 || pad > Sm4Core.BLOCK_SIZE || pad > data.size) return null
    return data.copyOfRange(0, data.size - pad)
}

/** D6544CFE-F2DE-459B-9B77-0F2B367EF169：SM4 变体 CBC，PKCS7 填充。 */
internal class Sm4VariantCbcCipher(key: ByteArray, iv: ByteArray) : SessionCipher {

    private val key = key.copyOf(16)
    private val iv = iv.copyOf(16)

    override fun encrypt(text: String): String {
        val buf = pkcs7Pad(text.toByteArray(Charsets.UTF_8))
        val rk = Sm4Core.keySchedule(key, forward = true)
        val prev = iv.copyOf()
        val block = ByteArray(16)
        var off = 0
        while (off < buf.size) {
            for (i in 0 until 16) block[i] = (buf[off + i].toInt() xor prev[i].toInt()).toByte()
            Sm4Core.processBlock(rk, block, 0, buf, off)
            buf.copyInto(prev, 0, off, off + 16)
            off += 16
        }
        return buf.toHexUpper()
    }

    override fun decrypt(hex: String): String? {
        val data = hex.hexToBytesOrNull() ?: return null
        if (data.size % 16 != 0 || data.isEmpty()) return null
        val out = ByteArray(data.size)
        val rk = Sm4Core.keySchedule(key, forward = false)
        val prev = iv.copyOf()
        val dec = ByteArray(16)
        var off = 0
        while (off < data.size) {
            Sm4Core.processBlock(rk, data, off, dec, 0)
            for (i in 0 until 16) out[off + i] = (dec[i].toInt() xor prev[i].toInt()).toByte()
            data.copyInto(prev, 0, off, off + 16)
            off += 16
        }
        return pkcs7Strip(out)?.toString(Charsets.UTF_8)
    }
}

/** D755A536-B551-468C-BD87-322182B223D4：SM4 变体 ECB，PKCS7 填充，无 IV。 */
internal class Sm4VariantEcbCipher(key: ByteArray) : SessionCipher {

    private val key = key.copyOf(16)

    override fun encrypt(text: String): String {
        val buf = pkcs7Pad(text.toByteArray(Charsets.UTF_8))
        val rk = Sm4Core.keySchedule(key, forward = true)
        var off = 0
        while (off < buf.size) {
            Sm4Core.processBlock(rk, buf, off, buf, off)
            off += 16
        }
        return buf.toHexUpper()
    }

    override fun decrypt(hex: String): String? {
        val data = hex.hexToBytesOrNull() ?: return null
        if (data.size % 16 != 0 || data.isEmpty()) return null
        val out = data.copyOf()
        val rk = Sm4Core.keySchedule(key, forward = false)
        var off = 0
        while (off < out.size) {
            Sm4Core.processBlock(rk, out, off, out, off)
            off += 16
        }
        return pkcs7Strip(out)?.toString(Charsets.UTF_8)
    }
}
