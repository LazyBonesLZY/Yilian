package com.esurfing.client.core.cipher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 旧版 Android 算法集（C 版 src/cipher/algo/android_old/）的固定向量比对。
 *
 * 这九个 Algo-ID 在 C 版里连同密钥一起被整体注释掉了（标注"已弃用"），
 * 但服务端仍有可能下发，所以这边要认。
 *
 * 关键结论：把 C 版的 android_old 实现跑出来的密文和 android 那套逐条对比，
 * 结果**完全一致**。原因是密钥材料本来就是同一批，只是数组切分位置不同
 * （例如旧的 key1 就是新的 key[24..47]），而算法取用的顺序正好补偿回来。
 * 因此这里不需要再写九套实现，直接复用现有的即可——本测试就是这个结论的守卫：
 * 一旦哪天有人改坏了映射或密钥，这里会立刻红掉。
 *
 * cipher_vectors_legacy.tsv 由 work-tmp/cipher_vectors_old.c 生成。
 */
class LegacyCipherVectorTest {

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

    /** tsv 里的算法名 → 对应的旧版 Algo-ID，用工厂来取实现。 */
    private val algoIdOf = mapOf(
        "old_aes_cbc" to "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E",
        "old_aes_ecb" to "A474B1C2-3DE0-4EA2-8C5F-7093409CE6C4",
        "old_desede_cbc" to "5BFBA864-BBA9-42DB-8EAD-49B5F412BD81",
        "old_desede_ecb" to "6E0B65FF-0B5B-459C-8FCE-EC7F2BEA9FF5",
        "old_zuc" to "B809531F-0007-4B5B-923B-4BD560398113",
        "old_sm4_cbc" to "F3974434-C0DD-4C20-9E87-DDB6814A1C48",
        "old_sm4_ecb" to "ED382482-F72C-4C41-A76D-28EEA0F1F2AF",
        "old_xtea" to "B3047D4E-67DF-4864-A6A5-DF9B9E525C79",
        "old_xtea_iv" to "C32C68F9-CA81-4260-A329-BBAFD1A9CCD1",
    )

    private class Vector(val name: String, val plain: String, val hex: String)

    private fun vectors(): List<Vector> {
        val text = checkNotNull(
            javaClass.classLoader!!.getResourceAsStream("cipher_vectors_legacy.tsv"),
        ) { "缺少 cipher_vectors_legacy.tsv" }.bufferedReader().readText()
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
        return checkNotNull(CipherFactory.create(id)) { "工厂不认识旧版 Algo-ID $id" }
    }

    @Test
    fun encryptMatchesCReference() {
        val vectors = vectors()
        assertEquals("向量条数不对", 27, vectors.size)
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

    /** 九个旧 ID 都要能从工厂拿到实现，别因为漏了一条分支而在现场才发现。 */
    @Test
    fun factoryResolvesEveryLegacyAlgoId() {
        for ((name, id) in algoIdOf) {
            assertNotNull("$name ($id) 未被工厂识别", CipherFactory.create(id))
        }
    }

    /**
     * 守住"旧 ID 复用新实现"这个前提：每个旧 ID 的输出必须与它对应的现行 ID 完全一致。
     * 这条断言若失败，说明两族其实并不等价，映射就不能再这么写。
     */
    @Test
    fun legacyAlgoIdsMatchCurrentOnes() {
        val equivalent = mapOf(
            "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E" to "BB2EA626-590B-4C42-82BE-E052FCBBB88E",
            "A474B1C2-3DE0-4EA2-8C5F-7093409CE6C4" to "DEABB8C8-A2BC-48CA-8ED0-8CDF1BD62F61",
            "5BFBA864-BBA9-42DB-8EAD-49B5F412BD81" to "9ABF4D29-34DB-4CE9-BB8C-7E371D637758",
            "6E0B65FF-0B5B-459C-8FCE-EC7F2BEA9FF5" to "AD8BB5B0-0E72-4198-A362-96D52C1B7ED1",
            "B809531F-0007-4B5B-923B-4BD560398113" to "07E824B2-9E5C-4D1B-BBB0-5E07C251E4AA",
            "F3974434-C0DD-4C20-9E87-DDB6814A1C48" to "D6544CFE-F2DE-459B-9B77-0F2B367EF169",
            "ED382482-F72C-4C41-A76D-28EEA0F1F2AF" to "D755A536-B551-468C-BD87-322182B223D4",
            "B3047D4E-67DF-4864-A6A5-DF9B9E525C79" to "319FC5AB-EC0E-46B9-A252-2285F9DAE813",
            "C32C68F9-CA81-4260-A329-BBAFD1A9CCD1" to "35101415-A20F-4DFE-B00B-0B4F3B2F8C66",
        )
        for ((legacy, current) in equivalent) {
            val a = checkNotNull(CipherFactory.create(legacy))
            val b = checkNotNull(CipherFactory.create(current))
            for (sample in samples.drop(1)) {
                assertEquals("$legacy 与 $current 输出不一致", b.encrypt(sample), a.encrypt(sample))
            }
        }
    }
}
