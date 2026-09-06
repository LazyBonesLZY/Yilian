package com.esurfing.client.core.cipher

/**
 * 改版 TEA：32 轮，delta 取 0x61C88647（标准 delta 的负值），因此 sum 递减。
 * 密钥 48 字节 = 3 层 × 4 个大端 32 位字。
 * 三层 ECB / 三层 CBC 两个算法共用。
 */
internal object TeaCore {

    const val BLOCK_SIZE = 8
    const val KEY_SIZE = 48

    private const val DELTA = 0x61C88647
    private val SUM_END = 0xC6EF3720.toInt()
    private val DEC_KIDX_INIT = 0x28B7BD67

    /** 把 48 字节密钥拆成 3 层、每层 4 个大端字。 */
    fun loadKeyLayers(key: ByteArray): Array<IntArray> =
        Array(3) { layer -> IntArray(4) { i -> readBe32(key, (layer * 4 + i) * 4) } }

    /** 单层加密，v 为 [v0, v1]，就地更新。 */
    fun encLayer(v: IntArray, k: IntArray) {
        var a = v[0]
        var b = v[1]
        var sum = 0
        do {
            a += (b xor sum) + k[sum and 3] + ((b shl 4) xor (b ushr 5))
            sum -= DELTA
            b += k[(sum ushr 11) and 3] + (a xor sum) + ((a shl 4) xor (a ushr 5))
        } while (sum != SUM_END)
        v[0] = a
        v[1] = b
    }

    /** 单层解密。kidx 初值取自汇编（-31 * delta），与 IDA 伪代码不同。 */
    fun decLayer(v: IntArray, k: IntArray) {
        var a = v[0]
        var b = v[1]
        var sum = SUM_END
        var kidx = DEC_KIDX_INIT
        do {
            b -= (a xor sum) + ((a shl 4) xor (a ushr 5)) + k[(sum ushr 11) and 3]
            sum += DELTA
            a -= k[kidx and 3] + (b xor kidx) + ((b shl 4) xor (b ushr 5))
            kidx += DELTA
        } while (sum != 0)
        v[0] = a
        v[1] = b
    }
}
