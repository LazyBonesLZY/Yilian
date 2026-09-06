package com.esurfing.client.core.cipher

/**
 * 教科书式 AES-128，供 Linux 系算法（45433DCF / 4BA5496A）使用。
 *
 * 与 [AesCore] 不同：那一套是 Android 算法集的改版实现，密钥扩展被改过、
 * 内部状态按行优先摆放；这里是标准 FIPS-197 的写法，状态按列优先。
 * 两者不能互换，所以分成两个对象而不是加参数。
 */
internal object StdAesCore {

    private val S = IntArray(256)
    private val INV_S = IntArray(256)

    init {
        // 用 GF(2^8) 求逆 + 仿射变换现算 S 盒，省掉两张 256 字节常量表
        var p = 1
        var q = 1
        do {
            p = p xor ((p shl 1) and 0xFF) xor (if (p and 0x80 != 0) 0x1B else 0)
            q = q xor (q shl 1); q = q xor (q shl 2); q = q xor (q shl 4); q = q and 0xFF
            if (q and 0x80 != 0) q = q xor 0x09
            val x = q xor rotl8(q, 1) xor rotl8(q, 2) xor rotl8(q, 3) xor rotl8(q, 4)
            S[p] = x xor 0x63
        } while (p != 1)
        S[0] = 0x63
        for (i in 0 until 256) INV_S[S[i]] = i
    }

    private fun rotl8(x: Int, n: Int) = ((x shl n) or (x ushr (8 - n))) and 0xFF

    private fun xtime(x: Int): Int = ((x shl 1) xor (if (x and 0x80 != 0) 0x1B else 0)) and 0xFF

    private fun mul(a: Int, b: Int): Int {
        var x = a
        var y = b
        var r = 0
        while (y != 0) {
            if (y and 1 != 0) r = r xor x
            x = xtime(x)
            y = y ushr 1
        }
        return r and 0xFF
    }

    /** 标准密钥扩展，产出 11 组轮密钥共 176 字节。 */
    fun expandKey(key: ByteArray, keyOffset: Int = 0): IntArray {
        val rk = IntArray(176)
        for (i in 0 until 16) rk[i] = key[keyOffset + i].toInt() and 0xFF
        var rcon = 1
        var i = 16
        while (i < 176) {
            val t = IntArray(4) { rk[i - 4 + it] }
            if (i % 16 == 0) {
                val tmp = t[0]
                t[0] = S[t[1]] xor rcon
                t[1] = S[t[2]]
                t[2] = S[t[3]]
                t[3] = S[tmp]
                rcon = xtime(rcon)
            }
            for (j in 0 until 4) rk[i + j] = rk[i - 16 + j] xor t[j]
            i += 4
        }
        return rk
    }

    fun encryptBlock(input: ByteArray, inOff: Int, output: ByteArray, outOff: Int, rk: IntArray) {
        val s = IntArray(16) { input[inOff + it].toInt() and 0xFF }
        addRoundKey(s, rk, 0)
        for (round in 1..10) {
            for (j in 0 until 16) s[j] = S[s[j]]
            shiftRows(s)
            if (round != 10) mixColumns(s)
            addRoundKey(s, rk, round * 16)
        }
        for (j in 0 until 16) output[outOff + j] = s[j].toByte()
    }

    fun decryptBlock(input: ByteArray, inOff: Int, output: ByteArray, outOff: Int, rk: IntArray) {
        val s = IntArray(16) { input[inOff + it].toInt() and 0xFF }
        addRoundKey(s, rk, 160)
        for (round in 9 downTo 0) {
            invShiftRows(s)
            for (j in 0 until 16) s[j] = INV_S[s[j]]
            addRoundKey(s, rk, round * 16)
            if (round != 0) invMixColumns(s)
        }
        for (j in 0 until 16) output[outOff + j] = s[j].toByte()
    }

    private fun addRoundKey(s: IntArray, rk: IntArray, off: Int) {
        for (j in 0 until 16) s[j] = s[j] xor rk[off + j]
    }

    private fun shiftRows(s: IntArray) {
        val t = s.copyOf()
        for (r in 1 until 4) for (c in 0 until 4) s[c * 4 + r] = t[((c + r) and 3) * 4 + r]
    }

    private fun invShiftRows(s: IntArray) {
        val t = s.copyOf()
        for (r in 1 until 4) for (c in 0 until 4) s[((c + r) and 3) * 4 + r] = t[c * 4 + r]
    }

    private fun mixColumns(s: IntArray) {
        for (c in 0 until 4) {
            val o = c * 4
            val a0 = s[o]; val a1 = s[o + 1]; val a2 = s[o + 2]; val a3 = s[o + 3]
            s[o] = mul(a0, 2) xor mul(a1, 3) xor a2 xor a3
            s[o + 1] = a0 xor mul(a1, 2) xor mul(a2, 3) xor a3
            s[o + 2] = a0 xor a1 xor mul(a2, 2) xor mul(a3, 3)
            s[o + 3] = mul(a0, 3) xor a1 xor a2 xor mul(a3, 2)
        }
    }

    private fun invMixColumns(s: IntArray) {
        for (c in 0 until 4) {
            val o = c * 4
            val a0 = s[o]; val a1 = s[o + 1]; val a2 = s[o + 2]; val a3 = s[o + 3]
            s[o] = mul(a0, 14) xor mul(a1, 11) xor mul(a2, 13) xor mul(a3, 9)
            s[o + 1] = mul(a0, 9) xor mul(a1, 14) xor mul(a2, 11) xor mul(a3, 13)
            s[o + 2] = mul(a0, 13) xor mul(a1, 9) xor mul(a2, 14) xor mul(a3, 11)
            s[o + 3] = mul(a0, 11) xor mul(a1, 13) xor mul(a2, 9) xor mul(a3, 14)
        }
    }
}
