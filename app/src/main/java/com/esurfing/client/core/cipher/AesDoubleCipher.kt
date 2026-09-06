package com.esurfing.client.core.cipher

/**
 * BB2EA626-590B-4C42-82BE-E052FCBBB88E：双层 AES-128-CBC。
 * 每层都把 IV 拼在密文头部，明文零填充到 16 字节对齐。
 */
internal class AesDoubleCbcCipher(key: ByteArray, iv: ByteArray) : SessionCipher {

    private val key = key.copyOf(32)
    private val iv = iv.copyOf(16)

    private fun layerEncrypt(keyOffset: Int, data: ByteArray): ByteArray {
        var total = (data.size + 15) and 0xF.inv()
        if (total == 0) total = 16
        val out = ByteArray(total + 16)
        iv.copyInto(out, 0)
        data.copyInto(out, 16)
        val sk = AesCore.expandKey(key, keyOffset)
        val prev = iv.copyOf()
        val block = ByteArray(16)
        var off = 0
        while (off < total) {
            for (i in 0 until 16) block[i] = (out[16 + off + i].toInt() xor prev[i].toInt()).toByte()
            AesCore.encryptBlock(sk, block, 0, out, 16 + off)
            out.copyInto(prev, 0, 16 + off, 32 + off)
            off += 16
        }
        return out
    }

    private fun layerDecrypt(keyOffset: Int, data: ByteArray): ByteArray? {
        if (data.size % 16 != 0 || data.size < 32) return null
        val total = data.size - 16
        val out = ByteArray(total)
        val sk = AesCore.expandKey(key, keyOffset)
        val prev = data.copyOfRange(0, 16)
        val dec = ByteArray(16)
        var off = 0
        while (off < total) {
            AesCore.decryptBlock(sk, data, 16 + off, dec, 0)
            for (i in 0 until 16) out[off + i] = (dec[i].toInt() xor prev[i].toInt()).toByte()
            data.copyInto(prev, 0, 16 + off, 32 + off)
            off += 16
        }
        return out
    }

    override fun encrypt(text: String): String {
        val first = layerEncrypt(0, text.toByteArray(Charsets.UTF_8))
        return layerEncrypt(16, first).toHexUpper()
    }

    override fun decrypt(hex: String): String? {
        val bytes = hex.hexToBytesOrNull() ?: return null
        val outer = layerDecrypt(16, bytes) ?: return null
        return layerDecrypt(0, outer)?.stripZeroTailToString()
    }
}

/**
 * DEABB8C8-A2BC-48CA-8ED0-8CDF1BD62F61：双层 AES-128-ECB。
 * 与 CBC 版共用内核，但不链接、不拼 IV，零填充。
 */
internal class AesDoubleEcbCipher(key: ByteArray) : SessionCipher {

    private val key = key.copyOf(32)

    private fun layerEncrypt(keyOffset: Int, data: ByteArray): ByteArray {
        var total = (data.size + 15) and 0xF.inv()
        if (total == 0) total = 16
        val out = ByteArray(total)
        data.copyInto(out)
        val sk = AesCore.expandKey(key, keyOffset)
        var off = 0
        while (off < total) {
            AesCore.encryptBlock(sk, out, off, out, off)
            off += 16
        }
        return out
    }

    private fun layerDecrypt(keyOffset: Int, data: ByteArray): ByteArray? {
        if (data.size % 16 != 0 || data.isEmpty()) return null
        val out = data.copyOf()
        val sk = AesCore.expandKey(key, keyOffset)
        var off = 0
        while (off < out.size) {
            AesCore.decryptBlock(sk, out, off, out, off)
            off += 16
        }
        return out
    }

    override fun encrypt(text: String): String {
        val first = layerEncrypt(0, text.toByteArray(Charsets.UTF_8))
        return layerEncrypt(16, first).toHexUpper()
    }

    override fun decrypt(hex: String): String? {
        val bytes = hex.hexToBytesOrNull() ?: return null
        val outer = layerDecrypt(16, bytes) ?: return null
        return layerDecrypt(0, outer)?.stripZeroTailToString()
    }
}
