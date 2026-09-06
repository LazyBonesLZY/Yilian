package com.esurfing.client.core.cipher

/**
 * XTEA 变体，供 Linux 系算法 60639D8B / AB6C8EBE 使用。
 *
 * 与 [TeaCore] 的区别：这里 delta 是标准的 0x9E3779B9（sum 递增），
 * 而 Android 那套用的是它的负值。两者的轮函数选密钥的下标算法也不同，
 * 不能合并。
 *
 * 两个变体的密钥字都要先做一次字节序翻转（C 版的 bswap32），
 * 这是原实现从汇编里带出来的行为，不是笔误。
 */
internal object XteaCore {

    private const val ROUNDS = 32
    private const val DELTA = 0x9E3779B9.toInt()

    /**
     * AB6C8EBE 用的是另一个 delta（0x61C88647，标准 delta 的负值），
     * 而且是往下减的。跟上面那个不是同一个常量，别合并。
     */
    private const val AB_DELTA = 0x61C88647

    private fun bswap(v: Int): Int =
        ((v ushr 24) and 0xFF) or ((v ushr 8) and 0xFF00) or
            ((v shl 8) and 0xFF0000) or (v shl 24)

    fun swappedKey(k: IntArray): IntArray = IntArray(4) { bswap(k[it]) }

    /** 60639D8B 用的块加密，v = [v0, v1] 就地更新，ks 需已 bswap。 */
    fun encBlock(v: IntArray, ks: IntArray) {
        var v0 = v[0]
        var v1 = v[1]
        var sum = 0
        for (i in 0 until ROUNDS) {
            v0 += ((v1 shl 4) xor (v1 ushr 5)) + (sum xor v1) + ks[sum and 3]
            sum += DELTA
            v1 += ((v0 shl 4) xor (v0 ushr 5)) + (v0 xor sum) + ks[(sum ushr 11) and 3]
        }
        v[0] = v0
        v[1] = v1
    }

    fun decBlock(v: IntArray, ks: IntArray) {
        var v0 = v[0]
        var v1 = v[1]
        var sum = DELTA * ROUNDS
        for (i in 0 until ROUNDS) {
            v1 -= ((v0 shl 4) xor (v0 ushr 5)) + (v0 xor sum) + ks[(sum ushr 11) and 3]
            sum -= DELTA
            v0 -= ((v1 shl 4) xor (v1 ushr 5)) + (sum xor v1) + ks[sum and 3]
        }
        v[0] = v0
        v[1] = v1
    }

    /**
     * AB6C8EBE 用的块运算。轮次推进方式和上面那套不一样：
     * sum 以 `j -= delta` 递减，密钥下标用 `(j-71)&3` 和 `(j+1013904242)>>>11 & 3`
     * 这类看着莫名其妙的表达式——它们是从汇编直译过来的，改成"更合理"的写法就对不上了。
     */
    fun ab6c8EncBlock(v: IntArray, ks: IntArray) {
        var v9 = bswap(v[0])
        var v10 = bswap(v[1])
        var j = 0
        var k6 = ks[0]
        var k8 = ks[3]
        val end = 0 - AB_DELTA * 32
        while (true) {
            v9 += ((16 * v10) xor (v10 ushr 5)) + (j xor v10) + k6
            v10 += ((16 * v9) xor (v9 ushr 5)) + ((j - AB_DELTA) xor v9) + k8
            if (end == j - AB_DELTA) break
            k6 = ks[(j - 71) and 3]
            k8 = ks[((j + 1013904242) ushr 11) and 3]
            j -= AB_DELTA
        }
        v[0] = bswap(v9)
        v[1] = bswap(v10)
    }

    fun ab6c8DecBlock(v: IntArray, ks: IntArray) {
        var v9 = bswap(v[0])
        var v10 = bswap(v[1])
        var i = AB_DELTA * -32
        while (i != 0) {
            val v14 = v10 - ((16 * v9) xor (v9 ushr 5)) - (v9 xor i)
            val v15 = i
            i += AB_DELTA
            v10 = v14 - ks[(v15 ushr 11) and 3]
            v9 -= ((16 * v10) xor (v10 ushr 5)) + (i xor v10) + ks[i and 3]
        }
        v[0] = bswap(v9)
        v[1] = bswap(v10)
    }
}
