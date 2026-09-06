package com.esurfing.client.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.esurfing.client.core.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 拨号循环的等待机制。
 *
 * 拆成接口是因为省电与及时性无法兼得：
 * - [AlarmSleeper] 等待期间放开 WakeLock，靠 AlarmManager 唤醒；
 * - [WakeLockSleeper] 全程持锁，delay() 一定按时返回。
 *
 * 两者都支持被 [wake] 提前唤醒（网络变化、用户点了停止）。
 */
interface Sleeper {
    /** 等待至多 [ms] 毫秒；被 [wake] 打断时提前返回。 */
    suspend fun sleep(ms: Long)

    /** 提前结束当前的等待。可从任意线程调用。 */
    fun wake()

    /** 循环退出时清理（取消闹钟、释放锁）。 */
    fun shutdown()
}

/** 进程内共享的 WakeLock，acquire/release 可重入。 */
class WakeLockHolder(context: Context) {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var lock: PowerManager.WakeLock? = null

    val isHeld: Boolean
        get() = lock?.isHeld == true

    /**
     * @param timeoutMs 超时自动释放，防止逻辑漏掉 release 时把电耗光。
     *   传 0 表示不设超时（稳定优先模式用）。
     */
    @Synchronized
    fun acquire(timeoutMs: Long = 0) {
        if (lock == null) {
            lock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply {
                setReferenceCounted(false)
            }
        }
        val wl = lock ?: return
        // 带超时的申请要重新计时：锁是非引用计数的，重复 acquire 会重置超时。
        // 若在这里因"已持有"提前返回，超时就一直从第一次 acquire 起算，
        // 循环忙过那个时长后锁会被系统悄悄收走。
        if (timeoutMs > 0) {
            wl.acquire(timeoutMs)
            return
        }
        if (!wl.isHeld) wl.acquire()
    }

    @Synchronized
    fun release() {
        lock?.let { if (it.isHeld) it.release() }
    }

    private companion object {
        const val TAG = "ESurfingClient:dialer"
    }
}

/**
 * 记录"在没人等待的时候来过一次唤醒"，避免丢事件。
 *
 * 网络回调可能正好落在循环忙于收发的窗口里，那时 [Sleeper.wake] 无处可醒；
 * 如果不记下来，接着就会睡满整个心跳周期，把刚发生的网络变化白白错过。
 */
private class WakeLatch {

    private val flag = AtomicBoolean(false)

    fun signal() = flag.set(true)

    /** 取出并清空标记。 */
    fun consume(): Boolean = flag.getAndSet(false)
}

/**
 * 省电实现：等待期间释放 WakeLock，让系统正常进入休眠，靠 AlarmManager 把设备唤醒。
 *
 * 两个刻意的选择：
 * - 用 `setAndAllowWhileIdle`（非精确）而不是 `setExactAndAllowWhileIdle`：后者在
 *   Android 12+ 需要 SCHEDULE_EXACT_ALARM，而 Android 14 起该权限对新装应用默认不授予，
 *   会直接抛 SecurityException。非精确闹钟在 Doze 下可能迟到几分钟，由调用方通过
 *   "心跳失败即重认证"兜底，不去和系统的省电策略较劲。
 * - 用 ELAPSED_REALTIME_WAKEUP 而不是 RTC_WAKEUP：墙上时钟会被 NTP 校正或用户改动，
 *   而开机计时单调递增，不会因为时间跳变导致睡过头。
 */
class AlarmSleeper(
    private val context: Context,
    private val wakeLock: WakeLockHolder,
) : Sleeper {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val latch = WakeLatch()

    @Volatile
    private var pending: CompletableDeferred<Unit>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLog.verbose("闹钟唤醒")
            wake()
        }
    }

    private val alarmIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(ACTION_WAKE).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    init {
        val filter = IntentFilter(ACTION_WAKE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    override suspend fun sleep(ms: Long) {
        if (ms <= 0) return
        // 忙碌期间来过唤醒就不睡了，直接回去干活
        if (latch.consume()) return

        val gate = CompletableDeferred<Unit>()
        pending = gate
        // 赋值 pending 之后再查一次，堵住"赋值前一刻来的唤醒"这个窗口
        if (latch.consume()) {
            pending = null
            return
        }

        scheduleAlarm(ms)
        // 释放锁之后设备才能真正休眠，这是省电的关键一步
        wakeLock.release()
        try {
            // 闹钟是主唤醒源；withTimeoutOrNull 只是兜底，Doze 下它本身也会被推迟
            withTimeoutOrNull(ms + GRACE_MS) { gate.await() }
        } finally {
            pending = null
            cancelAlarm()
            // 被取消（服务销毁）时不要再抢锁，否则没人来释放
            if (currentCoroutineContext().isActive) {
                wakeLock.acquire(WORK_LOCK_TIMEOUT_MS)
            }
        }
    }

    override fun wake() {
        val gate = pending
        if (gate != null) {
            gate.complete(Unit)
        } else {
            latch.signal()
        }
    }

    override fun shutdown() {
        cancelAlarm()
        runCatching { context.unregisterReceiver(receiver) }
        wakeLock.release()
    }

    private fun scheduleAlarm(ms: Long) {
        val at = SystemClock.elapsedRealtime() + ms
        runCatching {
            alarmManager?.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent)
        }.onFailure {
            AppLog.error("设置闹钟失败: ${it.message}")
        }
    }

    private fun cancelAlarm() {
        runCatching { alarmManager?.cancel(alarmIntent) }
    }

    private companion object {
        const val ACTION_WAKE = "com.esurfing.client.action.ALARM_WAKE"
        const val REQUEST_CODE = 2001

        /** 闹钟迟到的宽限，超过就让 delay 兜底返回。 */
        const val GRACE_MS = 30_000L

        /** 醒来后持锁的上限：正常一轮收发几秒就够，超时自动释放以防泄漏。 */
        const val WORK_LOCK_TIMEOUT_MS = 3 * 60_000L
    }
}

/** 稳定实现：全程持锁，delay() 按时返回，代价是持续耗电。 */
class WakeLockSleeper(private val wakeLock: WakeLockHolder) : Sleeper {

    private val latch = WakeLatch()

    @Volatile
    private var pending: CompletableDeferred<Unit>? = null

    init {
        wakeLock.acquire()
    }

    override suspend fun sleep(ms: Long) {
        if (ms <= 0) return
        if (latch.consume()) return

        val gate = CompletableDeferred<Unit>()
        pending = gate
        if (latch.consume()) {
            pending = null
            return
        }
        try {
            withTimeoutOrNull(ms) { gate.await() }
        } finally {
            pending = null
        }
    }

    override fun wake() {
        val gate = pending
        if (gate != null) {
            gate.complete(Unit)
        } else {
            latch.signal()
        }
    }

    override fun shutdown() {
        wakeLock.release()
    }
}
