package com.esurfing.client.core.cipher

/** 319FC5AB-EC0E-46B9-A252-2285F9DAE813：三层改版 TEA，ECB，零填充。 */
internal class TeaTripleEcbCipher(key: ByteArray) : SessionCipher {

    private val layers = TeaCore.loadKeyLayers(key.copyOf(TeaCore.KEY_SIZE))

    override fun encrypt(text: String): String {
        val data = text.toByteArray(Charsets.UTF_8)
        var total = (data.size + 7) and 0x7.inv()
        if (total == 0) total = TeaCore.BLOCK_SIZE
        val buf = ByteArray(total)
        data.copyInto(buf)
        val v = IntArray(2)
        var off = 0
        while (off < total) {
            v[0] = readBe32(buf, off)
            v[1] = readBe32(buf, off + 4)
            TeaCore.encLayer(v, layers[0])
            TeaCore.encLayer(v, layers[1])
            TeaCore.encLayer(v, layers[2])
            writeBe32(buf, off, v[0])
            writeBe32(buf, off + 4, v[1])
            off += 8
        }
        return buf.toHexUpper()
    }

    override fun decrypt(hex: String): String? {
        val data = hex.hexToBytesOrNull() ?: return null
        if (data.size % 8 != 0 || data.isEmpty()) return null
        val buf = data.copyOf()
        val v = IntArray(2)
        var off = 0
        while (off < buf.size) {
            v[0] = readBe32(buf, off)
            v[1] = readBe32(buf, off + 4)
            TeaCore.decLayer(v, layers[2])
            TeaCore.decLayer(v, layers[1])
            TeaCore.decLayer(v, layers[0])
            writeBe32(buf, off, v[0])
            writeBe32(buf, off + 4, v[1])
            off += 8
        }
        return buf.stripZeroTailToString()
    }
}

/**
 * 35101415-A20F-4DFE-B00B-0B4F3B2F8C66：三层改版 TEA，CBC，8 字节 IV，零填充。
 * 层序与 ECB 版相反（按汇编：加密 k2,k1,k0）。
 */
internal class TeaTripleCbcCipher(key: ByteArray, iv: ByteArray) : SessionCipher {

    private val layers = TeaCore.loadKeyLayers(key.copyOf(TeaCore.KEY_SIZE))
    private val iv = iv.copyOf(TeaCore.BLOCK_SIZE)

    override fun encrypt(text: String): String {
        val data = text.toByteArray(Charsets.UTF_8)
        var total = (data.size + 7) and 0x7.inv()
        if (total == 0) total = TeaCore.BLOCK_SIZE
        val buf = ByteArray(total)
        data.copyInto(buf)
        val prev = iv.copyOf()
        val v = IntArray(2)
        var off = 0
        while (off < total) {
            v[0] = readBe32(buf, off) xor readBe32(prev, 0)
            v[1] = readBe32(buf, off + 4) xor readBe32(prev, 4)
            TeaCore.encLayer(v, layers[2])
            TeaCore.encLayer(v, layers[1])
            TeaCore.encLayer(v, layers[0])
            writeBe32(buf, off, v[0])
            writeBe32(buf, off + 4, v[1])
            buf.copyInto(prev, 0, off, off + 8)
            off += 8
        }
        return buf.toHexUpper()
    }

    override fun decrypt(hex: String): String? {
        val data = hex.hexToBytesOrNull() ?: return null
        if (data.size % 8 != 0 || data.isEmpty()) return null
        val buf = data.copyOf()
        val prev = iv.copyOf()
        val v = IntArray(2)
        var off = 0
        while (off < buf.size) {
            v[0] = readBe32(buf, off)
            v[1] = readBe32(buf, off + 4)
            TeaCore.decLayer(v, layers[0])
            TeaCore.decLayer(v, layers[1])
            TeaCore.decLayer(v, layers[2])
            writeBe32(buf, off, v[0] xor readBe32(prev, 0))
            writeBe32(buf, off + 4, v[1] xor readBe32(prev, 4))
            data.copyInto(prev, 0, off, off + 8)
            off += 8
        }
        return buf.stripZeroTailToString()
    }
}
