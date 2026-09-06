package com.esurfing.client.core

/**
 * 拨号循环的时间计算。抽出来是为了能单测——这些数字算错了不会崩，
 * 只会表现为"用了一晚上莫名其妙掉线"，靠肉眼盯日志很难发现。
 */
object Timing {

    /** 服务端没给 keep-retry 时的心跳兜底间隔。 */
    const val FALLBACK_BEAT_MS = 10 * 60_000L

    /** 心跳间隔下限，防止服务端下发异常小的值导致疯狂发包。 */
    const val MIN_BEAT_MS = 10_000L

    /** 心跳提前量占服务端间隔的比例。 */
    const val BEAT_MARGIN_RATIO = 0.2

    /** 提前量的下限与上限。 */
    const val MIN_BEAT_MARGIN_MS = 15_000L
    const val MAX_BEAT_MARGIN_MS = 120_000L

    /**
     * 省电优先模式下的探测间隔下限。
     * 每次探测都要把设备从 Doze 里唤醒，所以即使用户填了 10 秒，
     * 省电模式也按 45 秒执行——想要更快就切到稳定优先。
     */
    const val MIN_PROBE_BATTERY_MS = 45_000L

    /**
     * 两次强制探测之间的最小间隔。
     *
     * 网络变化、屏幕解锁都会请求立刻探测，用户在电梯里进出信号区时
     * 这些事件会连成一串，没有这个下限就会连发一串一模一样的请求。
     */
    const val MIN_FORCED_PROBE_GAP_MS = 5_000L

    /**
     * 下一次心跳该在多久之后发。
     *
     * **不是**服务端给的间隔，而是要比它早一点。服务端的 interval 是"最迟什么时候
     * 必须收到下一个心跳"，掐着点发等于把网络延迟、闹钟迟到、Doze 唤醒延迟全押在
     * 零余量上：晚一秒，会话就被 AC 回收，而客户端这边心跳请求可能还显示成功。
     * 省电模式用的是非精确闹钟，迟到几十秒很正常，所以这个提前量是必须的。
     *
     * 代价很小：10 分钟的间隔提前 2 分钟，一天也就多发三次心跳。
     *
     * 有一处两难：服务端下发 1 秒之类的荒唐值时，"不超过截止时间"和"不疯狂发包"
     * 只能保一个。这里保后者——[MIN_BEAT_MS] 优先于服务端的值，宁可让这种明显
     * 异常的会话掉线，也不能按 1 秒一次去锤服务端。这也是加提前量之前就有的取舍。
     *
     * @param serverIntervalSec 服务端下发的 interval，秒；<= 0 表示没给。
     * @return 距下次发心跳的毫秒数。服务端间隔大于 [MIN_BEAT_MS] 时保证严格小于它。
     */
    fun beatIntervalMs(serverIntervalSec: Long): Long {
        val full = if (serverIntervalSec > 0) serverIntervalSec * 1000 else FALLBACK_BEAT_MS
        val margin = (full * BEAT_MARGIN_RATIO).toLong()
            .coerceIn(MIN_BEAT_MARGIN_MS, MAX_BEAT_MARGIN_MS)
        // 顺序要紧：先夹到服务端间隔以内（提前量不能反倒把心跳推到截止时间之后），
        // 最后再抬到下限——下限优先级最高，理由见上面的两难说明。
        return (full - margin).coerceAtMost(full).coerceAtLeast(MIN_BEAT_MS)
    }

    /**
     * 连通性探测间隔。
     * 稳定优先模式本来就一直持锁，用户填多快就多快；
     * 省电模式下每次探测都要唤醒设备，所以有个 [MIN_PROBE_BATTERY_MS] 下限。
     */
    fun probeIntervalMs(intervalSec: Int, powerMode: PowerMode): Long {
        val wanted = DetectRange.clamp(intervalSec) * 1000L
        return when (powerMode) {
            PowerMode.STABILITY -> wanted
            PowerMode.BATTERY -> wanted.coerceAtLeast(MIN_PROBE_BATTERY_MS)
        }
    }

    /** 省电模式是否会把用户填的间隔拖慢。界面据此提示，免得用户以为设置没生效。 */
    fun isProbeThrottled(intervalSec: Int, powerMode: PowerMode): Boolean =
        powerMode == PowerMode.BATTERY && DetectRange.clamp(intervalSec) * 1000L < MIN_PROBE_BATTERY_MS
}
