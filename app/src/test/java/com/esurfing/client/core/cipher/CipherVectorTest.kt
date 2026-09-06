package com.esurfing.client.core.cipher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定向量比对。cipher_vectors.tsv 由 C 版 esurfingclient 的 android 算法集直接跑出
 * （工具见 work-tmp/cipher_vectors.c），每行是 算法名 / 明文下标 / 密文 hex，
 * 用来确认 Kotlin 移植与原实现逐字节一致。
 */
class CipherVectorTest {

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

    private fun cipherOf(name: String): SessionCipher = when (name) {
        "snow3g" -> Snow3gVariantCipher(KeyData.KEY_SNOW3G, KeyData.IV_SNOW3G)
        "tea_ecb" -> TeaTripleEcbCipher(KeyData.KEY_TEA_ECB)
        "tea_cbc" -> TeaTripleCbcCipher(KeyData.KEY_TEA_CBC, KeyData.IV_TEA_CBC)
        "sm4_cbc" -> Sm4VariantCbcCipher(KeyData.KEY_SM4_CBC, KeyData.IV_SM4_CBC)
        "sm4_ecb" -> Sm4VariantEcbCipher(KeyData.KEY_SM4_ECB)
        "aes_cbc" -> AesDoubleCbcCipher(KeyData.KEY_AES_CBC, KeyData.IV_AES_CBC)
        "aes_ecb" -> AesDoubleEcbCipher(KeyData.KEY_AES_ECB)
        "desede_cbc" -> DesEdeDoubleCbcCipher(KeyData.KEY_DESEDE_CBC, KeyData.IV_DESEDE_CBC)
        "des_six" -> DesEcbSixCipher(KeyData.KEY_DES_SIX)
        else -> error("未知算法: $name")
    }

    private class Vector(val name: String, val plain: String, val hex: String)

    private fun vectors(): List<Vector> {
        val text = checkNotNull(javaClass.classLoader!!.getResourceAsStream("cipher_vectors.tsv")) {
            "缺少 cipher_vectors.tsv"
        }.bufferedReader().readText()
        return text.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val (name, idx, hex) = line.split('\t')
                Vector(name, samples[idx.toInt()], hex)
            }
            .toList()
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
}
