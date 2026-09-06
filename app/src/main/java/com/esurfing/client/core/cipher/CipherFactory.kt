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

        // ---- Linux 系算法集 ----
        // Algo-ID 由 AC 下发，与客户端跑在什么系统上无关，所以这些同样要认。

        "1A7343EC-7F9B-4570-BF58-16279A81116B" ->
            DesEdeDoubleCbcCipher(
                KeyData.KEY_LX_DESEDE_CBC,
                KeyData.IV_LX_DESEDE_CBC_1,
                KeyData.IV_LX_DESEDE_CBC_2,
            )

        "4BA5496A-2123-46A7-85F2-35956EA7BE39" ->
            AesEcbDoubleLinuxCipher(KeyData.KEY_LX_AES_ECB_1, KeyData.KEY_LX_AES_ECB_2)

        "45433DCF-9ECA-4BE5-83F2-F92BA0B4F291" ->
            AesCbcDoubleLinuxCipher(KeyData.KEY_LX_AES_CBC_1, KeyData.KEY_LX_AES_CBC_2)

        "60639D8B-272E-4A4D-976E-AA270987A169" ->
            XteaTripleLinuxCipher(
                KeyData.KEY_LX_XTEA_1,
                KeyData.KEY_LX_XTEA_2,
                KeyData.KEY_LX_XTEA_3,
            )

        "AB6C8EBE-B8F8-4C08-8222-69A3B5E86A91" ->
            XteaTripleCbcLinuxCipher(
                KeyData.KEY_LX_XTEA_CBC_1,
                KeyData.KEY_LX_XTEA_CBC_2,
                KeyData.KEY_LX_XTEA_CBC_3,
                KeyData.IV_LX_XTEA_CBC,
            )

        "B306E770-B7D5-49F2-A574-BCE2C5C650ED" ->
            DesEcbSixCipher(KeyData.KEY_LX_DES_SIX)

        // ---- 旧版 Android 算法集（C 版 android_old，那边整体注释掉了）----
        // 这九个 Algo-ID 的密文与上面九个逐字节相同：密钥材料一样，
        // 只是数组切分位置不同，算法取用的顺序正好补偿回来。
        // 已用 C 版实测向量验证（LinuxCipherVectorTest.legacyAlgoIdsMatchCurrentOnes），
        // 所以直接复用现有实现，不需要再写一套。

        "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E" ->
            AesDoubleCbcCipher(KeyData.KEY_AES_CBC, KeyData.IV_AES_CBC)

        "A474B1C2-3DE0-4EA2-8C5F-7093409CE6C4" ->
            AesDoubleEcbCipher(KeyData.KEY_AES_ECB)

        "5BFBA864-BBA9-42DB-8EAD-49B5F412BD81" ->
            DesEdeDoubleCbcCipher(KeyData.KEY_DESEDE_CBC, KeyData.IV_DESEDE_CBC)

        "6E0B65FF-0B5B-459C-8FCE-EC7F2BEA9FF5" ->
            DesEcbSixCipher(KeyData.KEY_DES_SIX)

        "B809531F-0007-4B5B-923B-4BD560398113" ->
            Snow3gVariantCipher(KeyData.KEY_SNOW3G, KeyData.IV_SNOW3G)

        "F3974434-C0DD-4C20-9E87-DDB6814A1C48" ->
            Sm4VariantCbcCipher(KeyData.KEY_SM4_CBC, KeyData.IV_SM4_CBC)

        "ED382482-F72C-4C41-A76D-28EEA0F1F2AF" ->
            Sm4VariantEcbCipher(KeyData.KEY_SM4_ECB)

        "B3047D4E-67DF-4864-A6A5-DF9B9E525C79" ->
            TeaTripleEcbCipher(KeyData.KEY_TEA_ECB)

        "C32C68F9-CA81-4260-A329-BBAFD1A9CCD1" ->
            TeaTripleCbcCipher(KeyData.KEY_TEA_CBC, KeyData.IV_TEA_CBC)

        // ---- Windows 系算法集（上游 PR #37）----
        // 只有三层 XTEA-CBC 是新算法，其余复用 Linux 族的实现，只换密钥/IV。

        "03F8A638-5C23-418B-972C-A2BA6927EF77" ->
            XteaTripleCbcWindowsCipher(KeyData.KEY_WIN_03F8A638, KeyData.IV_WIN_03F8A638)

        "079637D7-A2A2-41CE-A50D-4CAD3B2334E7" ->
            XteaTripleCbcWindowsCipher(KeyData.KEY_WIN_079637D7, KeyData.IV_WIN_079637D7)

        "0A2375CB-1F91-4064-B00F-1CF3A1AF6E4A" ->
            XteaTripleCbcWindowsCipher(KeyData.KEY_WIN_0A2375CB, KeyData.IV_WIN_0A2375CB)

        "11734889-14D8-48FA-ACEC-36452CA3FE8D" ->
            XteaTripleCbcWindowsCipher(KeyData.KEY_WIN_11734889, KeyData.IV_WIN_11734889)

        "CF750526-3D99-44BE-A0DE-09DEADC97D52" ->
            XteaTripleCbcWindowsCipher(KeyData.KEY_WIN_CF750526, KeyData.IV_WIN_CF750526)

        "FC05D786-59A7-4469-B276-0D9B89EAD057" ->
            XteaTripleCbcWindowsCipher(KeyData.KEY_WIN_FC05D786, KeyData.IV_WIN_FC05D786)

        "054DDD03-911E-49F5-89D6-EFBF5055FBFF" ->
            DesEdeDoubleCbcCipher(
                KeyData.KEY_WIN_054DDD03,
                KeyData.IV_WIN_054DDD03,
                KeyData.IV_WIN_054DDD03,
            )

        "066474E5-503E-4B82-98C4-DF4483DAF0B5" ->
            AesCbcDoubleLinuxCipher(
                KeyData.KEY_WIN_066474E5_1,
                KeyData.KEY_WIN_066474E5_2,
                KeyData.IV_WIN_066474E5,
            )

        "083B005A-7ACA-419A-AC00-6929C0AADB55" ->
            AesEcbDoubleLinuxCipher(KeyData.KEY_WIN_083B005A_1, KeyData.KEY_WIN_083B005A_2)

        "08BDB042-5D25-4397-875F-357E9F7700C8" ->
            AesEcbDoubleLinuxCipher(KeyData.KEY_WIN_08BDB042_1, KeyData.KEY_WIN_08BDB042_2)

        else -> {
            AppLog.error("不支持的 Algo-ID: $algoId")
            null
        }
    }
}
