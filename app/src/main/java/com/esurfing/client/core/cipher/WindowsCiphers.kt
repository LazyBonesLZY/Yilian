package com.esurfing.client.core.cipher

/**
 * Windows 系算法（上游 PR #37，`src/cipher/algo/windows/`）。
 *
 * 只有一个新算法：三层 XTEA-CBC。其余 4 个 Algo-ID 复用 Linux 族的实现
 * （双层 AES ECB/CBC、双重 3DES-CBC），只是密钥/IV 不同。
 *
 * 块运算与 Linux 的 60639D8B 是同一套标准 XTEA（正 delta、大端字），
 * 所以直接用 [XteaCore]；区别只在外层：这里是 CBC，且密钥/IV 以字节数组给出
 * （48 字节 = 3 层 × 16 字节，IV 8 字节）。
 */
internal class XteaTripleCbcWindowsCipher(key: ByteArray, iv: ByteArray) : SessionCipher {

    /** 每层 16 字节密钥按大端读成 4 个字；C 版对字节数组直接 read_u32_be，无需再翻转。 */
    private val layers = Array(3) { layer ->
        IntArray(4) { i -> readBe32(key, layer * 16 + i * 4) }
    }
    private val iv = iv.copyOf(8)

    override fun encrypt(text: String): String {
        val data = zeroPadTo8Bytes(text.toByteArray(Charsets.UTF_8))
        if (data.isEmpty()) return ""
        val v = IntArray(2)
        val prev = iv.copyOf()
        var off = 0
        while (off < data.size) {
            v[0] = readBe32(data, off) xor readBe32(prev, 0)
            v[1] = readBe32(data, off + 4) xor readBe32(prev, 4)
            XteaCore.encBlock(v, layers[0])
            XteaCore.encBlock(v, layers[1])
            XteaCore.encBlock(v, layers[2])
            writeBe32(data, off, v[0])
            writeBe32(data, off + 4, v[1])
            data.copyInto(prev, 0, off, off + 8)
            off += 8
        }
        return data.toHexUpper()
    }

    override fun decrypt(hex: String): String? {
        val data = hex.hexToBytesOrNull() ?: return if (hex.isEmpty()) "" else null
        if (data.size % 8 != 0) return null
        val out = ByteArray(data.size)
        val v = IntArray(2)
        val prev = iv.copyOf()
        val cur = ByteArray(8)
        var off = 0
        while (off < data.size) {
            data.copyInto(cur, 0, off, off + 8)
            v[0] = readBe32(data, off)
            v[1] = readBe32(data, off + 4)
            XteaCore.decBlock(v, layers[2])
            XteaCore.decBlock(v, layers[1])
            XteaCore.decBlock(v, layers[0])
            writeBe32(out, off, v[0] xor readBe32(prev, 0))
            writeBe32(out, off + 4, v[1] xor readBe32(prev, 4))
            cur.copyInto(prev)
            off += 8
        }
        // C 版这里截到第一个 0 字节而不是去掉尾部 0。对零填充的 XML 两者等价，
        // 但还是照 C 的语义写，免得将来对不上。
        val end = out.indexOfFirst { it == 0.toByte() }.let { if (it < 0) out.size else it }
        return String(out, 0, end, Charsets.UTF_8)
    }
}

private fun zeroPadTo8Bytes(data: ByteArray): ByteArray {
    val total = (data.size + 7) and 7.inv()
    val out = ByteArray(total)
    data.copyInto(out)
    return out
}
