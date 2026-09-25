package com.esurfing.client.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 探测地址与 C 版 CheckNetwork.c 对齐。
 * 判定本身在 DialerEngine 里，这里守住「地址顺序」和「IP 钉死」两条不会被改回去的约定。
 */
class ProbeRotationTest {

    @Test
    fun probeOrderMatchesCVersion() {
        assertEquals(
            listOf(
                "http://connect.rom.miui.com/generate_204",
                "http://1.1.1.1",
                "http://14.146.227.141:7001",
                "http://121.8.177.212:7001",
            ),
            Cctp.PROBE_URLS,
        )
        assertEquals(Cctp.PROBE_URLS.first(), Cctp.DETECT_URL)
    }

    @Test
    fun miuiProbeIsPinnedAwayFromCampusDns() {
        assertEquals("connect.rom.miui.com", Cctp.MIUI_PROBE_HOST)
        assertEquals("220.181.104.183", Cctp.MIUI_PROBE_IP)
    }
}
