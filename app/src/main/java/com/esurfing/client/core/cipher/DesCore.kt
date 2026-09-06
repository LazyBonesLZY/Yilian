package com.esurfing.client.core.cipher

/**
 * 标准 DES 块运算，被六层 DES-ECB 与双层 3DES-CBC 两个算法共用。
 * 直接按 C 版的位数组实现翻译，保持 S 盒索引计算方式一致。
 */
internal object DesCore {

    private val IP = intArrayOf(
        58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4,
        62, 54, 46, 38, 30, 22, 14, 6, 64, 56, 48, 40, 32, 24, 16, 8,
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
        61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
    )
    private val FP = intArrayOf(
        40, 8, 48, 16, 56, 24, 64, 32, 39, 7, 47, 15, 55, 23, 63, 31,
        38, 6, 46, 14, 54, 22, 62, 30, 37, 5, 45, 13, 53, 21, 61, 29,
        36, 4, 44, 12, 52, 20, 60, 28, 35, 3, 43, 11, 51, 19, 59, 27,
        34, 2, 42, 10, 50, 18, 58, 26, 33, 1, 41, 9, 49, 17, 57, 25,
    )
    private val PC1 = intArrayOf(
        57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18,
        10, 2, 59, 51, 43, 35, 27, 19, 11, 3, 60, 52, 44, 36,
        63, 55, 47, 39, 31, 23, 15, 7, 62, 54, 46, 38, 30, 22,
        14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 28, 20, 12, 4,
    )
    private val E_SELECT = intArrayOf(
        32, 1, 2, 3, 4, 5, 4, 5, 6, 7, 8, 9, 8, 9, 10, 11,
        12, 13, 12, 13, 14, 15, 16, 17, 16, 17, 18, 19, 20, 21, 20, 21,
        22, 23, 24, 25, 24, 25, 26, 27, 28, 29, 28, 29, 30, 31, 32, 1,
    )
    private val PC2 = intArrayOf(
        14, 17, 11, 24, 1, 5, 3, 28, 15, 6, 21, 10, 23, 19, 12, 4,
        26, 8, 16, 7, 27, 20, 13, 2, 41, 52, 31, 37, 47, 55, 30, 40,
        51, 45, 33, 48, 44, 49, 39, 56, 34, 53, 46, 42, 50, 36, 29, 32,
    )
    private val P = intArrayOf(
        16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10,
        2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25,
    )
    private val SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    private val SBOX = intArrayOf(
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
    )

    private fun bytesToBits(input: ByteArray, offset: Int, outBits: IntArray) {
        var idx = 0
        for (b in 0 until 8) {
            val value = input[offset + b].toInt() and 0xFF
            for (i in 7 downTo 0) outBits[idx++] = (value ushr i) and 1
        }
    }

    private fun bitsToBytes(inBits: IntArray, output: ByteArray, offset: Int) {
        for (b in 0 until 8) {
            var v = 0
            for (i in 0 until 8) v = (v shl 1) or (inBits[b * 8 + i] and 1)
            output[offset + b] = v.toByte()
        }
    }

    private fun applyPerm(dst: IntArray, src: IntArray, table: IntArray, count: Int) {
        for (i in 0 until count) dst[i] = src[table[i] - 1] and 1
    }

    private fun rotateCd(cd: IntArray, shift: Int) {
        val c = cd.copyOfRange(0, 28)
        val d = cd.copyOfRange(28, 56)
        for (i in 0 until 28) {
            cd[i] = c[(i + shift) % 28]
            cd[28 + i] = d[(i + shift) % 28]
        }
    }

    private fun roundF(r: IntArray, subkey: IntArray, out32: IntArray) {
        val expanded = IntArray(48)
        applyPerm(expanded, r, E_SELECT, 48)
        for (i in 0 until 48) expanded[i] = expanded[i] xor subkey[i]
        val sOut = IntArray(32)
        for (box in 0 until 8) {
            val p = box * 6
            val idx = (box shl 6) + expanded[p + 4] +
                2 * (expanded[p + 3] + 2 * (expanded[p + 2] + 2 * (expanded[p + 1] + 2 * (expanded[p + 5] + 2 * expanded[p]))))
            val value = SBOX[idx] and 0x0F
            sOut[box * 4] = (value ushr 3) and 1
            sOut[box * 4 + 1] = (value ushr 2) and 1
            sOut[box * 4 + 2] = (value ushr 1) and 1
            sOut[box * 4 + 3] = value and 1
        }
        applyPerm(out32, sOut, P, 32)
    }

    /** 为 8 字节密钥生成 16 轮子密钥（每轮 48 位）。 */
    fun keySchedule(key: ByteArray, offset: Int): Array<IntArray> {
        val keyBits = IntArray(64)
        bytesToBits(key, offset, keyBits)
        val cd = IntArray(56)
        applyPerm(cd, keyBits, PC1, 56)
        return Array(16) { round ->
            rotateCd(cd, SHIFTS[round])
            IntArray(48).also { applyPerm(it, cd, PC2, 48) }
        }
    }

    private fun blockProcess(
        input: ByteArray,
        inOffset: Int,
        output: ByteArray,
        outOffset: Int,
        subkeys: Array<IntArray>,
        encrypt: Boolean,
    ) {
        val inBits = IntArray(64)
        bytesToBits(input, inOffset, inBits)
        val ip = IntArray(64)
        applyPerm(ip, inBits, IP, 64)
        var l = ip.copyOfRange(0, 32)
        var r = ip.copyOfRange(32, 64)
        val f = IntArray(32)
        for (round in 0 until 16) {
            roundF(r, subkeys[if (encrypt) round else 15 - round], f)
            val newR = IntArray(32) { l[it] xor f[it] }
            l = r
            r = newR
        }
        val preOut = IntArray(64)
        r.copyInto(preOut, 0)
        l.copyInto(preOut, 32)
        val outBits = IntArray(64)
        applyPerm(outBits, preOut, FP, 64)
        bitsToBytes(outBits, output, outOffset)
    }

    fun encryptBlock(input: ByteArray, inOffset: Int, output: ByteArray, outOffset: Int, key: ByteArray, keyOffset: Int) =
        blockProcess(input, inOffset, output, outOffset, keySchedule(key, keyOffset), true)

    fun decryptBlock(input: ByteArray, inOffset: Int, output: ByteArray, outOffset: Int, key: ByteArray, keyOffset: Int) =
        blockProcess(input, inOffset, output, outOffset, keySchedule(key, keyOffset), false)

    fun encryptBlock(input: ByteArray, output: ByteArray, key: ByteArray, keyOffset: Int) =
        encryptBlock(input, 0, output, 0, key, keyOffset)

    fun decryptBlock(input: ByteArray, output: ByteArray, key: ByteArray, keyOffset: Int) =
        decryptBlock(input, 0, output, 0, key, keyOffset)
}
