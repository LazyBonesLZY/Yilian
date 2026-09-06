package com.esurfing.client.core.cipher

import com.esurfing.client.core.AppLog

/** Algo-ID 到会话加解密实现的映射，取自 C 版 CipherFactory 的 Android 算法集。 */
object CipherFactory {

    const val NULL_ALGO_ID = "00000000-0000-0000-0000-000000000000"

    fun create(algoId: String): SessionCipher? = when (algoId.uppercase()) {
        "07E824B2-9E5C-4D1B-BBB0-5E07C251E4AA" ->
            Snow3gVariantCipher(KeyData.KEY_SNOW3G, KeyData.IV_SNOW3G)

        "319FC5AB-EC0E-46B9-A252-2285F9DAE813" ->
            TeaTripleEcbCipher(KeyData.KEY_TEA_ECB)

        "35101415-A20F-4DFE-B00B-0B4F3B2F8C66" ->
            TeaTripleCbcCipher(KeyData.KEY_TEA_CBC, KeyData.IV_TEA_CBC)

        "D6544CFE-F2DE-459B-9B77-0F2B367EF169" ->
            Sm4VariantCbcCipher(KeyData.KEY_SM4_CBC, KeyData.IV_SM4_CBC)

        "D755A536-B551-468C-BD87-322182B223D4" ->
            Sm4VariantEcbCipher(KeyData.KEY_SM4_ECB)

        "BB2EA626-590B-4C42-82BE-E052FCBBB88E" ->
            AesDoubleCbcCipher(KeyData.KEY_AES_CBC, KeyData.IV_AES_CBC)

        "DEABB8C8-A2BC-48CA-8ED0-8CDF1BD62F61" ->
            AesDoubleEcbCipher(KeyData.KEY_AES_ECB)

        "9ABF4D29-34DB-4CE9-BB8C-7E371D637758" ->
            DesEdeDoubleCbcCipher(KeyData.KEY_DESEDE_CBC, KeyData.IV_DESEDE_CBC)

        "AD8BB5B0-0E72-4198-A362-96D52C1B7ED1" ->
            DesEcbSixCipher(KeyData.KEY_DES_SIX)

        else -> {
            AppLog.error("不支持的 Algo-ID: $algoId")
            null
        }
    }
}
