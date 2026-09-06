package com.esurfing.client.core.cipher

import com.esurfing.client.core.AppLog
import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream

/**
 * iOS / macOS 通道的动态 ZSM 模块解包。
 *
 * 这两个通道不走"Algo-ID → 硬编码密钥表"那条路：ticket.cgi 首包返回的是一个
 * PacketTunnel / GDCV 的动态模块，密钥每次会话都不一样，藏在模块正文里。
 * 包结构（对应上游 PR #39 的 `ios_zsm.c`，源自 iOS 的 IZsmModLoad）：
 *
 * ```
 * [3 字节头][u8 len1][str1][u8 len2][str2 = 模块 UUID]
 * [5 字节 LZMA props][u32 LE packed: 高 4 位 type 必须是 2, 低 28 位为解压后长度]
 * [TEA 密文...]
 * ```
 *
 * TEA 密文用一把固定的 32 字节 ASCII 密钥（分两半、各 32 轮）解开，再 LZMA 解压，
 * 得到的明文里：
 * - `buf[0xFA]` = IV 长度
 * - `buf[0xFC]` = 密钥长度
 * - `buf[0xFF]` = 密钥偏移基址（密钥在 `buf[buf[0xFF]+1]`）
 * - `buf + 0x103` 起是一段 JS
 *
 * 用哪个算法由 JS 决定：通常写成 `var codex = 0xNN;` 再 `cdy(codex, ...)`。
 * 1..9 是 oCode，复用现有的 Android 算法实现；>= 16 是 nCode，上游也还没移植。
 */
internal object DynamicZsm {

    /** 模块头固定用这把密钥，两个 16 字节半各跑一遍。 */
    private val TEA_KEY = "Rirn53a;feb#UXES5ZrRBTGmYwml:fRt".toByteArray(Charsets.US_ASCII)

    /** 0x61C88647 = -0x9E3779B9，所以下面的循环里 sum 是"加负数"。 */
    private const val TEA_DELTA = 0x61C88647

    private const val JS_OFFSET = 0x103
    private const val MAX_UNPACKED = 0x8000000

    /** 解包结果：模块 UUID、会话密钥、IV、以及决定算法的那段 JS。 */
    class Blob(
        val algoId: String,
        val key: ByteArray,
        val iv: ByteArray?,
        val js: String,
    )

    /**
     * 快速判断 ticket 响应是不是动态模块。
     * 只看结构（两个 Pascal 串 + packed 的 type nibble == 2），不解密，代价很低。
     * 这样即使用户选的是 Android 通道、服务端却下发了动态模块，也能识别出来。
     */
    fun looksLikeDynamicZsm(data: ByteArray?): Boolean {
        if (data == null || data.size < 15) return false
        var offset = 3
        val len1 = data[offset++].toInt() and 0xFF
        if (offset + len1 + 1 > data.size) return false
        offset += len1
        val len2 = data[offset++].toInt() and 0xFF
        if (offset + len2 + 9 > data.size) return false
        offset += len2
        val packed = readLe32(data, offset + 5)
        val unpacked = packed and 0x0FFFFFFF
        return (packed ushr 28) == 2 && unpacked in 1..MAX_UNPACKED
    }

    /**
     * 解包动态模块并按 JS 里的 codex 建立会话加解密。
     * @return 成功时返回 [SessionCipher] 与模块 UUID，失败返回 null（原因已写进日志）。
     */
    fun createCipher(data: ByteArray?): Pair<SessionCipher, String>? {
        val blob = unwrap(data) ?: return null
        val type = parseCdyType(blob.js)
        if (type < 0) {
            AppLog.error("动态 ZSM 的 JS 里找不到 cdy 类型 (字面量或 var codex = 0xNN), 无法选择算法")
            AppLog.debug("JS: ${blob.js.take(400)}")
            return null
        }
        AppLog.info("动态 ZSM cdy 类型: $type")
        if (type >= 16) {
            AppLog.error("动态 ZSM 用的是 nCode 类型 $type, 目前只支持 oCode 1-9")
            return null
        }
        val cipher = createOCodeCipher(type, blob.key, blob.iv) ?: run {
            AppLog.error("无法为动态 ZSM 类型 $type 创建加解密实现")
            return null
        }
        AppLog.debug("动态 ZSM 加解密已就绪")
        return cipher to blob.algoId
    }

    /** 解开模块外层：头部 → TEA → LZMA → 取密钥/IV/JS。 */
    fun unwrap(data: ByteArray?): Blob? {
        if (data == null || data.size < 15) {
            AppLog.error("动态 ZSM 太短: ${data?.size ?: 0}")
            return null
        }
        AppLog.debug("ZSM 前 32 字节: ${data.take(32).joinToString(" ") { "%02X".format(it) }}")

        var offset = 3
        val len1 = data[offset++].toInt() and 0xFF
        if (offset + len1 + 1 > data.size) {
            AppLog.error("动态 ZSM str1 越界")
            return null
        }
        offset += len1
        val len2 = data[offset++].toInt() and 0xFF
        if (offset + len2 > data.size) {
            AppLog.error("动态 ZSM str2 越界")
            return null
        }
        val algoId = uuidAt(data, offset, len2) ?: run {
            AppLog.warn("动态 ZSM str2 不是 UUID (len=$len2), 仍继续解包")
            CipherFactory.NULL_ALGO_ID
        }
        if (algoId != CipherFactory.NULL_ALGO_ID) AppLog.info("动态 ZSM 模块 ID: $algoId")
        offset += len2

        val remain = data.size - offset
        if (remain <= 9) {
            AppLog.error("动态 ZSM 剩余长度不足: $remain")
            return null
        }
        val props = data.copyOfRange(offset, offset + 5)
        val packed = readLe32(data, offset + 5)
        val unpackedSize = packed and 0x0FFFFFFF
        if ((packed ushr 28) != 2 || unpackedSize !in 1..MAX_UNPACKED) {
            AppLog.error(
                "动态 ZSM packed 头非法: type=${packed ushr 28} size=$unpackedSize remain=$remain",
            )
            return null
        }

        // 末尾多留 8 字节：密文长度不一定是 8 的倍数，最后一块要能整块解。
        // C 版就是 calloc(cipher_len + 8) 之后照样按块解，这里保持一致，
        // 否则 cipher[cipher_len-1] 那个填充长度字节会算出不一样的值。
        val cipherLen = remain - 9
        val buf = ByteArray(cipherLen + 8)
        data.copyInto(buf, 0, offset + 9, offset + 9 + cipherLen)
        teaDecryptBuffer(buf, cipherLen)

        val pad = if (cipherLen > 0) buf[cipherLen - 1].toInt() and 0xFF else 0
        if (pad > cipherLen) {
            AppLog.error("动态 ZSM TEA 填充长度非法: $pad / $cipherLen")
            return null
        }

        val unpacked = lzmaDecode(buf, cipherLen - pad, props, unpackedSize) ?: return null

        // 头部这三个偏移是 iOS 那边写死的，越界就说明解出来的不是预期的模块
        val ivLen = unpacked[0xFA].toInt() and 0xFF
        val keyLen = unpacked[0xFC].toInt() and 0xFF
        val keyOff = unpacked[0xFF].toInt() and 0xFF
        if (keyOff + keyLen + ivLen >= 0xFA || unpacked.size <= JS_OFFSET) {
            AppLog.error(
                "动态 ZSM 明文头非法: keyOff=$keyOff keyLen=$keyLen ivLen=$ivLen 解压后=${unpacked.size}",
            )
            return null
        }

        val keyStart = keyOff + 1
        val key = unpacked.copyOfRange(keyStart, keyStart + keyLen)
        val ivStart = keyStart + keyLen
        // "noiv" 是模块显式声明不用 IV，不能当成 4 字节的 IV 用
        val iv = when {
            ivLen == 0 -> null
            ivLen == 4 && String(unpacked, ivStart, 4, Charsets.US_ASCII) == "noiv" -> null
            else -> unpacked.copyOfRange(ivStart, ivStart + ivLen)
        }

        // JS 是以 NUL 结尾的 C 字符串，取到第一个 0 为止
        var jsEnd = JS_OFFSET
        while (jsEnd < unpacked.size && unpacked[jsEnd] != 0.toByte()) jsEnd++
        val js = String(unpacked, JS_OFFSET, jsEnd - JS_OFFSET, Charsets.UTF_8)

        AppLog.info("动态 ZSM 解包成功: keyLen=$keyLen ivLen=${iv?.size ?: 0} jsLen=${js.length}")
        if (js.contains("pxs") || js.contains("txs")) {
            AppLog.warn("动态 ZSM 的 JS 调用了 pxs/txs, 目前只实现 cdy, 认证失败请反馈日志")
        }
        return Blob(algoId, key, iv, js)
    }

    /**
     * 5 字节 props = 1 字节 lc/lp/pb + 4 字节字典大小(LE)，正好是裸 LZMA1 流的头。
     *
     * 这里把长度传成 -1（未知）而不是 [unpackedSize]，然后自己读满就停。
     * 传已知长度的那个重载会对同一串字节报 CorruptedInputException，
     * 而 C 版的 LzmaDec、Python 的 lzma 模块都能正常解开——也就是说数据没问题，
     * 是这个重载的行为与之不合。未知长度 + 读满即止的语义与 C 的
     * `LZMA_FINISH_ANY` + `destLen` 一致，所以走这条路。
     */
    private fun lzmaDecode(
        cipher: ByteArray,
        srcLen: Int,
        props: ByteArray,
        unpackedSize: Int,
    ): ByteArray? {
        if (srcLen <= 0) {
            AppLog.error("动态 ZSM LZMA 输入长度为 $srcLen")
            return null
        }
        val dictSize = readLe32(props, 1)
        return try {
            LZMAInputStream(
                ByteArrayInputStream(cipher, 0, srcLen),
                -1L,
                props[0],
                dictSize,
            ).use { stream ->
                val out = ByteArray(unpackedSize)
                var read = 0
                while (read < unpackedSize) {
                    val n = stream.read(out, read, unpackedSize - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < unpackedSize) {
                    AppLog.error("动态 ZSM LZMA 解压不完整: $read / $unpackedSize")
                    null
                } else {
                    out
                }
            }
        } catch (e: Exception) {
            AppLog.error("动态 ZSM LZMA 解压失败: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * oCode 1..9 直接映射到现有的 Android 算法实现，只是密钥/IV 换成模块下发的。
     * 长度不符时按 C 版的做法截断或补零（并留一条日志），而不是直接失败——
     * 宁可发出去让服务端拒绝，也比连日志都没有更好定位。
     */
    private fun createOCodeCipher(type: Int, key: ByteArray, iv: ByteArray?): SessionCipher? = when (type) {
        1 -> AesDoubleEcbCipher(fit(key, 32, "key"))
        2 -> AesDoubleCbcCipher(fit(key, 32, "key"), fit(iv, 16, "iv"))
        3 -> {
            // iOS 的六层 DES 密钥顺序是 K4,K5,K6,K1,K2,K3；旋转 24 字节后就能复用 Android 实现
            val k = fit(key, 48, "key")
            val rotated = ByteArray(48)
            k.copyInto(rotated, 0, 24, 48)
            k.copyInto(rotated, 24, 0, 24)
            DesEcbSixCipher(rotated)
        }
        4 -> DesEdeDoubleCbcCipher(fit(key, 48, "key"), fit(iv, 8, "iv"))
        5 -> TeaTripleEcbCipher(fit(key, 48, "key"))
        6 -> TeaTripleCbcCipher(fit(key, 48, "key"), fit(iv, 8, "iv"))
        7 -> Sm4VariantEcbCipher(fit(key, 16, "key"))
        8 -> Sm4VariantCbcCipher(fit(key, 16, "key"), fit(iv, 16, "iv"))
        9 -> Snow3gVariantCipher(fit(key, 16, "key"), fit(iv, 16, "iv"))
        else -> null
    }

    private fun fit(src: ByteArray?, size: Int, what: String): ByteArray {
        if (src == null || src.isEmpty()) {
            AppLog.warn("动态 ZSM 的 $what 为空, 按 $size 字节零填充")
            return ByteArray(size)
        }
        if (src.size != size) {
            AppLog.warn("动态 ZSM 的 $what 长度 ${src.size}, 算法需要 $size, 将截断或补零")
        }
        return src.copyOf(size)
    }

    // ---- TEA ----

    /**
     * 与 [TeaCore] 不是同一套：这里密钥和数据都按小端读，sum 从 0xC6EF3720 递增到 0，
     * 而 TeaCore 是大端且解密下标另有算法。合并只会把两边都弄坏。
     */
    private fun teaDecryptBlock(key: ByteArray, keyOff: Int, buf: ByteArray, off: Int) {
        val k = IntArray(4) { readLe32(key, keyOff + it * 4) }
        var v0 = readLe32(buf, off)
        var v1 = readLe32(buf, off + 4)
        var sum = 0xC6EF3720.toInt()
        do {
            v1 -= ((v0 shl 4) xor (v0 ushr 5)) + (k[(sum ushr 11) and 3] + (v0 xor sum))
            sum += TEA_DELTA
            v0 -= (k[sum and 3] + (v1 xor sum)) + ((v1 shl 4) xor (v1 ushr 5))
        } while (sum != 0)
        writeLe32(buf, off, v0)
        writeLe32(buf, off + 4, v1)
    }

    private fun teaDecryptBuffer(buf: ByteArray, length: Int) {
        var off = 0
        while (off < length) {
            teaDecryptBlock(TEA_KEY, 0, buf, off)
            teaDecryptBlock(TEA_KEY, 16, buf, off)
            off += 8
        }
    }

    // ---- JS 解析 ----

    /**
     * 找出 `cdy(...)` 的第一个参数。
     *
     * 实际下发的模块几乎都写成：
     * ```
     * var codex = 0x05;
     * function e(v) { return cdy(codex, 0, cdckey, cdciv, v); }
     * ```
     * 也就是说第一个参数是变量名而不是字面量，所以既要认字面量，也要能顺着变量名
     * 回去找它的赋值。只用一个正则匹配 `cdy(数字` 是不够的。
     */
    fun parseCdyType(js: String): Int {
        val fromCodex = lookupIntVar(js, "codex")
        if (fromCodex >= 1) AppLog.info("动态 ZSM 的 JS codex = $fromCodex (0x%02X)".format(fromCodex))

        var first = -1
        var i = 0
        while (true) {
            val at = js.indexOf("cdy", i)
            if (at < 0) break
            i = at + 3
            // 必须是完整标识符，避免匹配到 xcdy / cdyz 之类
            if (at > 0 && isIdentCont(js[at - 1])) continue
            var q = at + 3
            if (q < js.length && isIdentCont(js[q])) continue
            q = skipWsAndComments(js, q)
            if (q >= js.length || js[q] != '(') continue
            q = skipWsAndComments(js, q + 1)

            var n = -1
            val lit = parseInt(js, q)
            if (lit != null) {
                n = lit.first
            } else if (q < js.length && isIdentStart(js[q])) {
                var e = q
                while (e < js.length && isIdentCont(js[e])) e++
                val name = js.substring(q, e)
                n = if (name == "codex" && fromCodex >= 0) fromCodex else lookupIntVar(js, name)
            }
            if (n < 1 || n > 32) continue
            if (first < 0) {
                first = n
            } else if (first != n) {
                AppLog.warn("动态 ZSM 的 JS 里有多个 cdy 类型: $first 和 $n, 采用 $first")
            }
        }
        if (first < 0 && fromCodex in 1..32) return fromCodex
        return first
    }

    /** 找 `name = <整数>` 的最后一次赋值，找不到返回 -1。 */
    private fun lookupIntVar(js: String, name: String): Int {
        if (name.isEmpty()) return -1
        var last = -1
        var i = 0
        while (true) {
            val at = js.indexOf(name, i)
            if (at < 0) break
            i = at + name.length
            if (at > 0 && isIdentCont(js[at - 1])) continue
            val after = at + name.length
            if (after < js.length && isIdentCont(js[after])) continue
            var q = skipWsAndComments(js, after)
            if (q >= js.length || js[q] != '=') continue
            q = skipWsAndComments(js, q + 1)
            val parsed = parseInt(js, q) ?: continue
            last = parsed.first
            i = parsed.second
        }
        return last
    }

    /** @return (值, 结束下标)，或 null。上限 32 与 C 版一致：codex 不可能更大。 */
    private fun parseInt(js: String, start: Int): Pair<Int, Int>? {
        if (start >= js.length) return null
        var p = start
        val radix: Int
        if (js.startsWith("0x", p, ignoreCase = true)) {
            p += 2
            radix = 16
        } else {
            radix = 10
        }
        val digitsStart = p
        while (p < js.length && Character.digit(js[p], radix) >= 0) p++
        if (p == digitsStart) return null
        val value = js.substring(digitsStart, p).toLongOrNull(radix) ?: return null
        if (value > 32) return null
        return value.toInt() to p
    }

    private fun skipWsAndComments(js: String, from: Int): Int {
        var p = from
        while (p < js.length) {
            val c = js[p]
            if (c.isWhitespace()) {
                p++
                continue
            }
            if (c == '/' && p + 1 < js.length && js[p + 1] == '/') {
                p += 2
                while (p < js.length && js[p] != '\n') p++
                continue
            }
            if (c == '/' && p + 1 < js.length && js[p + 1] == '*') {
                p += 2
                while (p + 1 < js.length && !(js[p] == '*' && js[p + 1] == '/')) p++
                if (p + 1 < js.length) p += 2 else p = js.length
                continue
            }
            break
        }
        return p
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isIdentCont(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    // ---- 小工具 ----

    private fun uuidAt(data: ByteArray, offset: Int, len: Int): String? {
        if (len != 36 || offset + 36 > data.size) return null
        val sb = StringBuilder(36)
        for (i in 0 until 36) {
            val c = (data[offset + i].toInt() and 0xFF).toChar()
            val isDash = (i == 8 || i == 13 || i == 18 || i == 23) && c == '-'
            if (!isDash && Character.digit(c, 16) < 0) return null
            sb.append(c.uppercaseChar())
        }
        return sb.toString()
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
}
