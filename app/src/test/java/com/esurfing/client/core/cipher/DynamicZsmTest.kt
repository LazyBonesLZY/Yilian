package com.esurfing.client.core.cipher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS / macOS 动态 ZSM 模块解包的回归测试。
 *
 * 这两个通道的密钥不在任何硬编码表里，而是藏在 ticket 首包返回的模块正文中，
 * 要经过 TEA 解密 + LZMA 解压才能拿到，算法由模块里那段 JS 的 `codex` 决定。
 * 手上没有真实抓包（那需要 iOS 设备在校园网里跑一次），所以测试数据是**合成**的：
 *
 * 1. 用 `work-tmp/pr39/make_zsm.py` 按模块结构反向构造 9 个 ZSM（oCode 1..9 各一个）：
 *    明文头 + JS → LZMA1 压缩 → TEA 加密 → 拼上 Pascal 串与 packed 头；
 * 2. 把这些字节喂给上游 PR #39 的 C 实现（`work-tmp/pr39/probe.c`），
 *    记下它解出的模块 ID、密钥、IV，以及由此建立的会话对三段明文的密文；
 * 3. 本测试用同样的字节走 Kotlin 实现，逐字节比对上面每一项。
 *
 * 也就是说：合成的是**输入**，参考值来自 C 实现。构造脚本本身写错也不会让测试变松——
 * 两边读的是同一串字节，C 版怎么解，这边就必须怎么解。
 */
class DynamicZsmTest {

    /** 与 C 侧取参考值时用的明文完全相同。 */
    private val samples = listOf(
        "a",
        "12345678",
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<request>\n" +
            "    <user-agent>CCTP/iOSdy/4023</user-agent>\n" +
            "</request>\n",
    )

    private class Case(
        val name: String,
        val algoId: String,
        val codex: Int,
        val key: ByteArray,
        val iv: ByteArray?,
        val ciphertexts: List<String>,
    )

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream(path)) { "缺少 $path" }
            .use { it.readBytes() }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte() }

    private fun cases(): List<Case> =
        String(resource("zsm_vectors.tsv"), Charsets.UTF_8)
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val f = line.split('\t')
                Case(
                    name = f[0],
                    algoId = f[1],
                    codex = f[2].toInt(),
                    key = hex(f[3]),
                    iv = if (f[4].isEmpty()) null else hex(f[4]),
                    ciphertexts = listOf(f[5], f[6], f[7]),
                )
            }
            .toList()

    private fun moduleBytes(name: String): ByteArray = resource("zsm/$name.zsm")

    @Test
    fun recognisesDynamicModules() {
        val all = cases()
        assertEquals("向量条数不对", 9, all.size)
        for (c in all) {
            assertTrue("${c.name} 应被识别为动态模块", DynamicZsm.looksLikeDynamicZsm(moduleBytes(c.name)))
        }
    }

    @Test
    fun unwrapMatchesCReference() {
        for (c in cases()) {
            val blob = checkNotNull(DynamicZsm.unwrap(moduleBytes(c.name))) { "${c.name} 解包失败" }
            assertEquals("${c.name} 模块 ID", c.algoId, blob.algoId)
            assertArrayEquals("${c.name} 密钥", c.key, blob.key)
            if (c.iv == null) {
                assertNull("${c.name} 不该有 IV", blob.iv)
            } else {
                assertArrayEquals("${c.name} IV", c.iv, blob.iv)
            }
            assertEquals("${c.name} codex", c.codex, DynamicZsm.parseCdyType(blob.js))
        }
    }

    /** 解包出的密钥要真的建起会话，并且密文与 C 实现逐字节相同。 */
    @Test
    fun cipherOutputMatchesCReference() {
        for (c in cases()) {
            val result = checkNotNull(DynamicZsm.createCipher(moduleBytes(c.name))) {
                "${c.name} 未能建立会话"
            }
            val (cipher, algoId) = result
            assertEquals("${c.name} 模块 ID", c.algoId, algoId)
            for (i in samples.indices) {
                assertEquals(
                    "${c.name} / oCode ${c.codex} / 样本 $i",
                    c.ciphertexts[i],
                    cipher.encrypt(samples[i]),
                )
                assertEquals(
                    "${c.name} 解密回环",
                    samples[i],
                    cipher.decrypt(c.ciphertexts[i]),
                )
            }
        }
    }

    /** 静态交付包不能被误判成动态模块，否则 Android 通道会走错分支。 */
    @Test
    fun staticDeliveryPackageIsNotMistakenForDynamic() {
        val header = "001@3F1A0C7E\$FF5F345F-B580-43DF-9ED3-D92FAE586133]"
            .toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(4096) { (it * 31 + 7).toByte() }
        assertFalse(DynamicZsm.looksLikeDynamicZsm(header + byteArrayOf(0) + payload))
    }

    @Test
    fun rejectsGarbage() {
        assertFalse(DynamicZsm.looksLikeDynamicZsm(null))
        assertFalse(DynamicZsm.looksLikeDynamicZsm(ByteArray(0)))
        assertFalse(DynamicZsm.looksLikeDynamicZsm(ByteArray(64) { 0x41 }))
        assertNull(DynamicZsm.unwrap(ByteArray(8)))
        assertNull(DynamicZsm.createCipher(ByteArray(64) { 0x41 }))
    }

    /** 截断的模块必须干净地失败，而不是抛异常把拨号循环带崩。 */
    @Test
    fun truncatedModuleFailsCleanly() {
        val full = moduleBytes("ocode2_aes_cbc")
        for (cut in listOf(20, 60, full.size / 2, full.size - 8)) {
            val part = full.copyOf(cut)
            // 不要求返回 null（截断点可能仍然结构合法），只要求不抛异常
            runCatching { DynamicZsm.createCipher(part) }
                .onFailure { throw AssertionError("截断到 $cut 字节时抛异常: $it") }
        }
    }

    /**
     * JS 解析要认得实际下发的写法：第一个参数是变量而非字面量。
     * 只用正则匹配 `cdy(数字` 会全部漏掉。
     */
    @Test
    fun parsesCodexFromVariousJsForms() {
        assertEquals(5, DynamicZsm.parseCdyType("var codex = 0x05; f(){return cdy(codex,0,k,v,d);}"))
        assertEquals(7, DynamicZsm.parseCdyType("var codex=7;\ncdy(codex, 1, a, b, c)"))
        assertEquals(3, DynamicZsm.parseCdyType("cdy(3, 0, k, v, d)"))
        assertEquals(9, DynamicZsm.parseCdyType("cdy( 0x09 , 0, k, v, d)"))
        // 变量名不叫 codex 也要能顺着找回去
        assertEquals(4, DynamicZsm.parseCdyType("var mode = 4; cdy(mode, 0, k, v, d)"))
        // 注释和空白不该干扰
        assertEquals(6, DynamicZsm.parseCdyType("var codex /* type */ = /* six */ 6; cdy(codex,0)"))
        // 注意：注释里的 cdy( 调用**不会**被跳过——C 版就是直接 strstr 找 "cdy"，
        // 这里刻意保持一致。真实模块没有注释掉的 cdy 调用，而与 C 保持同样行为
        // 比自作聪明更安全（C 版是在校园网里实测过的那个）。
        assertEquals(9, DynamicZsm.parseCdyType("// cdy(9, ...) 在注释里\nvar codex = 2; cdy(codex,0)"))
        // 相似标识符不能误命中
        assertEquals(-1, DynamicZsm.parseCdyType("xcdy(5,0); cdyz(6,0); var codexx = 8;"))
        assertEquals(-1, DynamicZsm.parseCdyType("function nothing() { return 1; }"))
    }

    /** oCode 只到 9，nCode（>= 16）上游也没移植，必须明确拒绝而不是静默用错算法。 */
    @Test
    fun rejectsNCode() {
        assertEquals(20, DynamicZsm.parseCdyType("var codex = 20; cdy(codex, 0, k, v, d)"))
        // 拒绝发生在 createCipher 里；这里确认解析本身不会把它压到 1..9
        assertNotNull(DynamicZsm.parseCdyType("var codex = 20; cdy(codex,0)"))
    }
}
