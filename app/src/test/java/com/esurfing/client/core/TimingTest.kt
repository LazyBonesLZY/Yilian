package com.esurfing.client.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心跳提前量与探测间隔的回归测试。
 *
 * 这些数字算错了不会崩、不会报错，只会表现为"用了一晚上莫名其妙掉线"，
 * 是最难靠肉眼发现的一类 bug，所以边界要钉死。
 */
class TimingTest {

    // ---- 心跳提前量 ----

    /** 核心不变式：永远早于服务端给的截止时间，绝不等于、更不能晚于。 */
    @Test
    fun heartbeatAlwaysLeavesMarginBeforeDeadline() {
        for (sec in longArrayOf(30, 60, 120, 300, 600, 1800, 3600)) {
            val deadline = sec * 1000
            val actual = Timing.beatIntervalMs(sec)
            assertTrue("interval=$sec 应早于截止时间, 实际 $actual/$deadline", actual < deadline)
            assertTrue("interval=$sec 不该早得离谱", actual >= deadline / 2)
        }
    }

    @Test
    fun heartbeatMarginIsProportionalWithinBounds() {
        // 600 秒 * 20% = 120 秒, 正好落在上限上
        assertEquals(480_000L, Timing.beatIntervalMs(600))
        // 300 秒 * 20% = 60 秒, 落在上下限之间, 按比例走
        assertEquals(240_000L, Timing.beatIntervalMs(300))
        // 3600 秒 * 20% = 720 秒, 被上限压到 120 秒
        assertEquals(3_600_000L - 120_000L, Timing.beatIntervalMs(3600))
        // 60 秒 * 20% = 12 秒, 被下限抬到 15 秒
        assertEquals(45_000L, Timing.beatIntervalMs(60))
    }

    /** 服务端没给 interval 时用兜底值，同样要留提前量。 */
    @Test
    fun fallsBackWhenServerGivesNothing() {
        val expected = Timing.FALLBACK_BEAT_MS - Timing.MAX_BEAT_MARGIN_MS
        assertEquals(expected, Timing.beatIntervalMs(0))
        assertEquals(expected, Timing.beatIntervalMs(-1))
    }

    /**
     * 服务端下发异常小的值时不能退化成疯狂发包，也不能因为减去提前量变成 0 或负数
     * （那会让循环空转）。
     *
     * 注意这里下限**优先于**服务端的值：interval=1 秒时我们照样 10 秒一次，
     * 宁可让这种明显异常的会话掉线，也不去按秒锤服务端。
     */
    @Test
    fun clampsAbsurdlySmallIntervals() {
        for (sec in longArrayOf(1, 5, 10, 15, 20)) {
            val actual = Timing.beatIntervalMs(sec)
            assertTrue("interval=$sec 不能低于下限, 实际 $actual", actual >= Timing.MIN_BEAT_MS)
        }
        // 下限以上的值才谈得上提前量
        assertTrue(Timing.beatIntervalMs(20) < 20_000L)
        // 下限以下的值由下限接管，允许超过服务端间隔
        assertEquals(Timing.MIN_BEAT_MS, Timing.beatIntervalMs(1))
    }

    // ---- 探测间隔 ----

    @Test
    fun stabilityModeUsesExactUserValue() {
        assertEquals(10_000L, Timing.probeIntervalMs(10, PowerMode.STABILITY))
        assertEquals(45_000L, Timing.probeIntervalMs(45, PowerMode.STABILITY))
        assertEquals(300_000L, Timing.probeIntervalMs(300, PowerMode.STABILITY))
    }

    /** 省电模式有下限：每次探测都要把设备从 Doze 里唤醒。 */
    @Test
    fun batteryModeEnforcesFloor() {
        assertEquals(Timing.MIN_PROBE_BATTERY_MS, Timing.probeIntervalMs(10, PowerMode.BATTERY))
        assertEquals(Timing.MIN_PROBE_BATTERY_MS, Timing.probeIntervalMs(45, PowerMode.BATTERY))
        // 比下限大的值不受影响
        assertEquals(180_000L, Timing.probeIntervalMs(180, PowerMode.BATTERY))
    }

    /** 被下限拖慢时界面要提示，否则用户以为自己的设置没生效。 */
    @Test
    fun reportsWhenBatteryModeThrottles() {
        assertTrue(Timing.isProbeThrottled(20, PowerMode.BATTERY))
        assertFalse(Timing.isProbeThrottled(45, PowerMode.BATTERY))
        assertFalse(Timing.isProbeThrottled(180, PowerMode.BATTERY))
        assertFalse("稳定优先从不拖慢", Timing.isProbeThrottled(10, PowerMode.STABILITY))
    }

    // ---- 取值范围 ----

    @Test
    fun clampsOutOfRangeValues() {
        assertEquals(DetectRange.MIN_SEC, DetectRange.clamp(0))
        assertEquals(DetectRange.MIN_SEC, DetectRange.clamp(-99))
        assertEquals(DetectRange.MAX_SEC, DetectRange.clamp(99999))
    }

    @Test
    fun alignsToStep() {
        assertEquals(45, DetectRange.clamp(45))
        assertEquals(45, DetectRange.clamp(44))
        assertEquals(45, DetectRange.clamp(46))
        // 对齐后仍须落在合法区间内
        for (raw in -50..400) {
            val v = DetectRange.clamp(raw)
            assertTrue("$raw -> $v 越界", v in DetectRange.MIN_SEC..DetectRange.MAX_SEC)
            assertEquals("$raw -> $v 未对齐步长", 0, (v - DetectRange.MIN_SEC) % DetectRange.STEP_SEC)
        }
    }

    /** 从 1.2.0 升级上来时，四档枚举要换算成秒，不能被默认值盖掉。 */
    @Test
    fun migratesLegacyEnumNames() {
        assertEquals(20, DetectRange.fromLegacyName("SEC_20"))
        assertEquals(45, DetectRange.fromLegacyName("SEC_45"))
        assertEquals(90, DetectRange.fromLegacyName("MIN_1"))
        assertEquals(180, DetectRange.fromLegacyName("MIN_3"))
        assertEquals(DetectRange.DEFAULT_SEC, DetectRange.fromLegacyName(null))
        assertEquals(DetectRange.DEFAULT_SEC, DetectRange.fromLegacyName("WHATEVER"))
    }

    @Test
    fun formatsLabels() {
        assertEquals("10 秒", DetectRange.label(10))
        assertEquals("45 秒", DetectRange.label(45))
        assertEquals("1 分钟", DetectRange.label(60))
        assertEquals("1 分 30 秒", DetectRange.label(90))
        assertEquals("5 分钟", DetectRange.label(300))
    }
}
