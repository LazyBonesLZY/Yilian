package com.esurfing.client.core.cipher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Linux 系算法集的固定向量比对。
 *
 * cipher_vectors_linux.tsv 由 C 版 src/cipher/algo/linux/ 直接跑出
 * （工具见 work-tmp/cipher_vectors_linux.c），格式与 Android 那份一致：
 * 算法名 / 明文下标 / 密文 hex。
 *
 * 这一族的 Algo-ID 由 AC 下发，跟客户端跑在什么系统上没关系，Android 端同样可能收到，
 * 所以必须和 Android 那族一样做逐字节校验。
 */
class LinuxCipherVectorTest {

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
        "lx_desede_cbc" -> DesEdeDoubleCbcCipher(
            KeyData.KEY_LX_DESEDE_CBC,
            KeyData.IV_LX_DESEDE_CBC_1,
            KeyData.IV_LX_DESEDE_CBC_2,
        )
        "lx_aes_ecb" -> AesEcbDoubleLinuxCipher(KeyData.KEY_LX_AES_ECB_1, KeyData.KEY_LX_AES_ECB_2)
        "lx_aes_cbc" -> AesCbcDoubleLinuxCipher(KeyData.KEY_LX_AES_CBC_1, KeyData.KEY_LX_AES_CBC_2)
        "lx_xtea" -> XteaTripleLinuxCipher(
            KeyData.KEY_LX_XTEA_1,
            KeyData.KEY_LX_XTEA_2,
            KeyData.KEY_LX_XTEA_3,
        )
        "lx_xtea_cbc3" -> XteaTripleCbcLinuxCipher(
            KeyData.KEY_LX_XTEA_CBC_1,
            KeyData.KEY_LX_XTEA_CBC_2,
            KeyData.KEY_LX_XTEA_CBC_3,
            KeyData.IV_LX_XTEA_CBC,
        )
        "lx_des_six" -> DesEcbSixCipher(KeyData.KEY_LX_DES_SIX)
        else -> error("未知算法: $name")
    }

    private class Vector(val name: String, val plain: String, val hex: String)

    private fun vectors(): List<Vector> {
        val text = checkNotNull(
            javaClass.classLoader!!.getResourceAsStream("cipher_vectors_linux.tsv"),
        ) { "缺少 cipher_vectors_linux.tsv" }.bufferedReader().readText()
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
        assertTrue("向量表为空", vectors.size >= 18)
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

    /** 六个 Algo-ID 都要能从工厂拿到实现，且能自洽地跑一个来回。 */
    @Test
    fun factoryResolvesEveryLinuxAlgoId() {
        val ids = listOf(
            "1A7343EC-7F9B-4570-BF58-16279A81116B",
            "4BA5496A-2123-46A7-85F2-35956EA7BE39",
            "45433DCF-9ECA-4BE5-83F2-F92BA0B4F291",
            "60639D8B-272E-4A4D-976E-AA270987A169",
            "AB6C8EBE-B8F8-4C08-8222-69A3B5E86A91",
            "B306E770-B7D5-49F2-A574-BCE2C5C650ED",
        )
        val text = "<request><user-agent>CCTP/linux/2104</user-agent></request>"
        for (id in ids) {
            val cipher = checkNotNull(CipherFactory.create(id)) { "工厂不认识 $id" }
            val hex = checkNotNull(cipher.encrypt(text)) { "$id 加密失败" }
            assertEquals(id, text, cipher.decrypt(hex))
        }
    }
}
