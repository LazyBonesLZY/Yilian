package com.esurfing.client.core.cipher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Windows 系算法集的固定向量比对（上游 PR #37 新增的 10 个 Algo-ID）。
 *
 * cipher_vectors_windows.tsv 由 PR 的 C 实现直接跑出（工具见 work-tmp/pr37/gen.c），
 * 但**打了一个补丁**：PR 把双层 AES-CBC 的 IV 改成参数时，漏掉了往密文前缀写 IV 的
 * 那两行 memcpy，缓冲区又是 malloc 的，于是前 16 字节是未初始化堆内存——每次运行
 * 结果都不同，连原本正常的 45433DCF 也被带坏。取向量前把那两行补回去了，
 * 补回后 45433DCF 与既有向量逐字节一致，说明"前缀 = IV"就是原本的语义。
 */
class WindowsCipherVectorTest {

    /** 与 C 侧取向量时用的明文完全相同，顺序即 tsv 里的下标。 */
    private val samples = listOf(
        "",
        "a",
        "12345678",
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<request>\n" +
            "    <user-agent>CCTP/android11_64/2104</user-agent>\n" +
            "</request>\n",
    )

    /** tsv 里的算法名 → Algo-ID，实现一律经工厂取，顺带验证映射没写漏。 */
    private val algoIdOf = mapOf(
        "win_xtea_03F8A638" to "03F8A638-5C23-418B-972C-A2BA6927EF77",
        "win_xtea_079637D7" to "079637D7-A2A2-41CE-A50D-4CAD3B2334E7",
        "win_xtea_0A2375CB" to "0A2375CB-1F91-4064-B00F-1CF3A1AF6E4A",
        "win_xtea_11734889" to "11734889-14D8-48FA-ACEC-36452CA3FE8D",
        "win_xtea_CF750526" to "CF750526-3D99-44BE-A0DE-09DEADC97D52",
        "win_xtea_FC05D786" to "FC05D786-59A7-4469-B276-0D9B89EAD057",
        "win_desede_054DDD03" to "054DDD03-911E-49F5-89D6-EFBF5055FBFF",
        "win_aes_cbc_066474E5" to "066474E5-503E-4B82-98C4-DF4483DAF0B5",
        "win_aes_ecb_083B005A" to "083B005A-7ACA-419A-AC00-6929C0AADB55",
        "win_aes_ecb_08BDB042" to "08BDB042-5D25-4397-875F-357E9F7700C8",
    )

    private class Vector(val name: String, val plain: String, val hex: String)

    private fun vectors(): List<Vector> {
        val text = checkNotNull(
            javaClass.classLoader!!.getResourceAsStream("cipher_vectors_windows.tsv"),
        ) { "缺少 cipher_vectors_windows.tsv" }.bufferedReader().readText()
        return text.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val (name, idx, hex) = line.split('\t')
                Vector(name, samples[idx.toInt()], hex)
            }
            .toList()
    }

    private fun cipherOf(name: String): SessionCipher {
        val id = checkNotNull(algoIdOf[name]) { "未知算法: $name" }
        return checkNotNull(CipherFactory.create(id)) { "工厂不认识 $id" }
    }

    @Test
    fun encryptMatchesCReference() {
        val vectors = vectors()
        assertTrue("向量表为空", vectors.size >= 30)
        for (v in vectors) {
            assertEquals("${v.name} / ${v.plain.length} 字节", v.hex, cipherOf(v.name).encrypt(v.plain))
        }
    }

    @Test
    fun decryptRestoresPlaintext() {
        for (v in vectors()) {
            assertEquals(v.name, v.plain, cipherOf(v.name).decrypt(v.hex))
        }
    }

    @Test
    fun factoryResolvesEveryWindowsAlgoId() {
        for ((name, id) in algoIdOf) {
            assertNotNull("$name ($id) 未被工厂识别", CipherFactory.create(id))
        }
    }

    /**
     * 双层 AES-CBC 的密文必须以 IV 开头，并且同一明文每次加密结果相同。
     * 这条就是冲着 PR #37 那个未初始化堆内存的 bug 来的：一旦有人照抄了那个写法，
     * 前 16 字节会变成随机堆内容，这里立刻失败。
     */
    @Test
    fun aesCbcPrefixIsIvAndDeterministic() {
        val id = "066474E5-503E-4B82-98C4-DF4483DAF0B5"
        val text = "<request><user-agent>CCTP/windows/2104</user-agent></request>"
        val first = checkNotNull(CipherFactory.create(id)!!.encrypt(text))
        val second = checkNotNull(CipherFactory.create(id)!!.encrypt(text))
        assertEquals("同一明文两次加密结果应当一致", first, second)
        assertEquals(
            "密文前 16 字节应当就是 IV",
            KeyData.IV_WIN_066474E5.toHexUpper(),
            first.take(32),
        )
    }

    /** 三层 XTEA-CBC 的空明文按 C 版返回空串（而不是 null）。 */
    @Test
    fun emptyPlaintextYieldsEmptyHex() {
        val cipher = CipherFactory.create("03F8A638-5C23-418B-972C-A2BA6927EF77")!!
        assertEquals("", cipher.encrypt(""))
    }
}
