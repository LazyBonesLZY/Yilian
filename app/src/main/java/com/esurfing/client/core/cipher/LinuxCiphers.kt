package com.esurfing.client.core.cipher

/**
 * Linux 系算法集（C 版 src/cipher/algo/linux/）。
 *
 * 这一族与 Android 那族看着很像，但细节处处不同：AES 用标准密钥扩展而不是改版的、
 * XTEA 的 delta 是正的、AES-CBC 会把每层的全零 IV 也拼进密文。所以两族分开实现，
 * 只有结构真正一致的（六层 DES-ECB、双重 3DES-CBC）才复用 Android 那边的类。
 *
 * 服务端下发哪一族的 Algo-ID 由 AC 决定，与客户端跑在什么系统上无关，
 * 所以 Android 客户端同样可能收到这些 ID。
 */

private fun zeroPad(data: ByteArray, block: Int): ByteArray {
    val padding = (block - data.size % block) % block
    val out = ByteArray(data.size + padding)
    data.copyInto(out)
    return out
}

private fun readLe32(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16) or
        (data[offset + 3].toInt() shl 24)

private fun writeLe32(data: ByteArray, offset: Int, value: Int) {
    data[offset] = value.toByte()
    data[offset + 1] = (value ushr 8).toByte()
    data[offset + 2] = (value ushr 16).toByte()
    data[offset + 3] = (value ushr 24).toByte()
}

/**
 * 4BA5496A-2123-46A7-85F2-35956EA7BE39：双层 AES-128-ECB（Linux）。
 * 加密先用 key2 再用 key1，解密反过来。
 */
internal class AesEcbDoubleLinuxCipher(key1: ByteArray, key2: ByteArray) : SessionCipher {

    private val rk1 = StdAesCore.expandKey(key1)
    private val rk2 = StdAesCore.expandKey(key2)

    override fun encrypt(text: String): String {
        val buf = zeroPad(text.toByteArray(Charsets.UTF_8), 16)
        val out = ByteArray(buf.size)
        val tmp = ByteArray(16)
        var i = 0
        while (i < buf.size) {
            StdAesCore.encryptBlock(buf, i, tmp, 0, rk2)
            StdAesCore.encryptBlock(tmp, 0, out, i, rk1)
            i += 16
        }
        return out.toHexUpper()
    }

    override fun decrypt(hex: String): String? {
        val data = hex.hexToBytesOrNull() ?: return null
        if (data.isEmpty() || data.size % 16 != 0) return null
        val out = ByteArray(data.size)
        val tmp = ByteArray(16)
        var i = 0
        while (i < data.size) {
            StdAesCore.decryptBlock(data, i, tmp, 0, rk1)
            StdAesCore.decryptBlock(tmp, 0, out, i, rk2)
            i += 16
        }
        return out.stripZeroTailToString()
    }
}

/**
 * 45433DCF-9ECA-4BE5-83F2-F92BA0B4F291：双层 AES-128-CBC（Linux）。
 *
 * 两层的 IV 都是全零，而且每层都把 IV 原样拼在密文前面——所以密文比明文多 32 字节，
 * 开头固定是 32 个 0。这看着很像 bug，但 C 版就是这么发的，服务端也这么收，
 * 照抄即可，不要"优化"掉。
 */
internal class AesCbcDoubleLinuxCipher(key1: ByteArray, key2: ByteArray) : SessionCipher {

    private val rk1 = StdAesCore.expandKey(key1)
    private val rk2 = StdAesCore.expandKey(key2)

    private fun cbcEncrypt(input: ByteArray, output: ByteArray, outOff: Int, rk: IntArray) {
        val prev = ByteArray(16)
        val x = ByteArray(16)
        var i = 0
        while (i < input.size) {
            for (j in 0 until 16) x[j] = (input[i + j].toInt() xor prev[j].toInt()).toByte()
            StdAesCore.encryptBlock(x, 0, output, outOff + i, rk)
            output.copyInto(prev, 0, outOff + i, outOff + i + 16)
            i += 16
        }
    }

    private fun cbcDecrypt(input: ByteArray, inOff: Int, len: Int, output: ByteArray, rk: IntArray) {
        val prev = ByteArray(16)
        val x = ByteArray(16)
        var i = 0
        while (i < len) {
            StdAesCore.decryptBlock(input, inOff + i, x, 0, rk)
            for (j in 0 until 16) output[i + j] = (x[j].toInt() xor prev[j].toInt()).toByte()
            input.copyInto(prev, 0, inOff + i, inOff + i + 16)
            i += 16
        }
    }

    override fun encrypt(text: String): String {
        val padded = zeroPad(text.toByteArray(Charsets.UTF_8), 16)
        val stage1 = ByteArray(16 + padded.size)
        cbcEncrypt(padded, stage1, 16, rk1)
        val stage2 = ByteArray(16 + stage1.size)
        cbcEncrypt(stage1, stage2, 16, rk2)
        return stage2.toHexUpper()
    }

    override fun decrypt(hex: String): String? {
        val data = hex.hexToBytesOrNull() ?: return null
        // 至少要有两层各自的 IV 块
        if (data.size < 32 || data.size % 16 != 0) return null
        val stage1 = ByteArray(data.size - 16)
        cbcDecrypt(data, 16, stage1.size, stage1, rk2)
        if (stage1.size < 32) return null
        val out = ByteArray(stage1.size - 16)
        cbcDecrypt(stage1, 16, out.size, out, rk1)
        return out.stripZeroTailToString()
    }
}

/**
 * 60639D8B-272E-4A4D-976E-AA270987A169：三层 XTEA-ECB（Linux），大端字。
 */
internal class XteaTripleLinuxCipher(key1: IntArray, key2: IntArray, key3: IntArray) : SessionCipher {

    private val k1 = XteaCore.swappedKey(key1)
    private val k2 = XteaCore.swappedKey(key2)
    private val k3 = XteaCore.swappedKey(key3)

    override fun encrypt(text: String): String {
        val buf = zeroPad(text.toByteArray(Charsets.UTF_8), 8)
        val v = IntArray(2)
        var i = 0
        while (i < buf.size) {
            v[0] = readBe32(buf, i)
            v[1] = readBe32(buf, i + 4)
            XteaCore.encBlock(v, k1)
            XteaCore.encBlock(v, k2)
            XteaCore.encBlock(v, k3)
            writeBe32(buf, i, v[0])
            writeBe32(buf, i + 4, v[1])
            i += 8
        }
        return buf.toHexUpper()
    }

    override fun decrypt(hex: String): String? {
        val buf = hex.hexToBytesOrNull() ?: return null
        if (buf.isEmpty() || buf.size % 8 != 0) return null
        val v = IntArray(2)
        var i = 0
        while (i < buf.size) {
            v[0] = readBe32(buf, i)
            v[1] = readBe32(buf, i + 4)
            XteaCore.decBlock(v, k3)
            XteaCore.decBlock(v, k2)
            XteaCore.decBlock(v, k1)
            writeBe32(buf, i, v[0])
            writeBe32(buf, i + 4, v[1])
            i += 8
        }
        return buf.stripZeroTailToString()
    }
}

/**
 * AB6C8EBE-B8F8-4C08-8222-69A3B5E86A91：三层 XTEA-CBC（Linux），小端字。
 * 加密方向按 k2 → k1 → k0，链接值取密文块。
 */
internal class XteaTripleCbcLinuxCipher(
    key0: IntArray,
    key1: IntArray,
    key2: IntArray,
    iv: IntArray,
) : SessionCipher {

    private val k0 = XteaCore.swappedKey(key0)
    private val k1 = XteaCore.swappedKey(key1)
    private val k2 = XteaCore.swappedKey(key2)
    private val iv0 = iv[0]
    private val iv1 = iv[1]

    override fun encrypt(text: String): String {
        val buf = zeroPad(text.toByteArray(Charsets.UTF_8), 8)
        val v = IntArray(2)
        var chain0 = iv0
        var chain1 = iv1
        var i = 0
        while (i < buf.size) {
            v[0] = readLe32(buf, i) xor chain0
            v[1] = readLe32(buf, i + 4) xor chain1
            XteaCore.ab6c8EncBlock(v, k2)
            XteaCore.ab6c8EncBlock(v, k1)
            XteaCore.ab6c8EncBlock(v, k0)
            writeLe32(buf, i, v[0])
            writeLe32(buf, i + 4, v[1])
            chain0 = v[0]
            chain1 = v[1]
            i += 8
        }
        return buf.toHexUpper()
    }

    override fun decrypt(hex: String): String? {
        val buf = hex.hexToBytesOrNull() ?: return null
        if (buf.isEmpty() || buf.size % 8 != 0) return null
        val v = IntArray(2)
        var prev0 = iv0
        var prev1 = iv1
        var i = 0
        while (i < buf.size) {
            v[0] = readLe32(buf, i)
            v[1] = readLe32(buf, i + 4)
            val c0 = v[0]
            val c1 = v[1]
            XteaCore.ab6c8DecBlock(v, k0)
            XteaCore.ab6c8DecBlock(v, k1)
            XteaCore.ab6c8DecBlock(v, k2)
            writeLe32(buf, i, v[0] xor prev0)
            writeLe32(buf, i + 4, v[1] xor prev1)
            prev0 = c0
            prev1 = c1
            i += 8
        }
        return buf.stripZeroTailToString()
    }
}
