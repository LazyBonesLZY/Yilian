package com.esurfing.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import com.esurfing.client.R
import com.esurfing.client.core.AppLog
import com.esurfing.client.core.DialerEngine
import com.esurfing.client.core.DialerState
import com.esurfing.client.core.PowerMode
import com.esurfing.client.core.SettingsStore
import com.esurfing.client.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 在前台服务里跑 [DialerEngine]。
 *
 * 前台类型用 specialUse 而非 dataSync：targetSdk >= 35 时 dataSync 每 24 小时
 * 只允许累计运行 6 小时，超时系统会调 onTimeout 并终止进程。
 */
class DialerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化启停，避免两者交错。 */
    private val lifecycle = Mutex()

    private lateinit var wakeLock: WakeLockHolder
    private var sleeper: Sleeper? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 本次服务实例是否已经真正启动过拨号循环。
     *
     * status 是 StateFlow，初始值就是 STOPPED，onCreate 里的收集器会立刻收到它。
     * 若不加这个门闩，收集器会在引擎还没来得及启动时就把服务 stopSelf 掉，
     * 表现为"点了开始认证却毫无反应"。
     */
    @Volatile
    private var engineStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        wakeLock = WakeLockHolder(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在启动"))
        scope.launch {
            DialerEngine.status.collectLatest { status ->
                notificationManager().notify(NOTIFICATION_ID, buildNotification(status.message))
                // 引擎跑过并且已经走到终态，服务才没有继续存在的意义
                val terminal = status.state == DialerState.STOPPED || status.state == DialerState.FAILED
                if (engineStarted && terminal && !DialerEngine.isRunning) {
                    AppLog.debug("拨号循环已结束, 停止前台服务")
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 启停都排进同一把锁，避免"先停再启"时新的启动被尚未退出的旧循环挡掉。
        // stopSelf(startId) 只在没有更新的启动请求时才真正停止服务，正是为这个竞态设计的。
        when (intent?.action) {
            ACTION_STOP -> scope.launch {
                lifecycle.withLock {
                    stopEngineLocked()
                    stopSelf(startId)
                }
            }

            else -> scope.launch {
                lifecycle.withLock { startEngineLocked() }
            }
        }
        return START_STICKY
    }

    private fun startEngineLocked() {
        if (DialerEngine.isRunning) {
            AppLog.debug("拨号循环已在运行, 忽略重复启动")
            return
        }
        // 上一轮的 Sleeper 可能还挂着 BroadcastReceiver 和 WakeLock，先收干净再建新的，
        // 否则反复启停会把接收器一个个泄漏出去
        sleeper?.shutdown()
        val mode = SettingsStore.settings.value.powerMode
        AppLog.info("保活策略: ${mode.label}")
        val created = when (mode) {
            PowerMode.BATTERY -> AlarmSleeper(this, wakeLock)
            PowerMode.STABILITY -> WakeLockSleeper(wakeLock)
        }
        sleeper = created
        registerNetworkCallback()
        DialerEngine.start(this, scope, created)
        engineStarted = true
    }

    private suspend fun stopEngineLocked() {
        engineStarted = false
        DialerEngine.stopAndJoin()
        unregisterNetworkCallback()
        sleeper?.shutdown()
        sleeper = null
        wakeLock.release()
    }

    /**
     * specialUse 没有时长上限，正常不会走到这里；
     * 万一系统仍然回调，按约定立刻停止，否则进程会被强杀。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.warn("前台服务被系统限时终止 (type=$fgsType), 正在停止")
        scope.launch {
            lifecycle.withLock {
                stopEngineLocked()
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        // 顺序要紧：先取消协程，被取消的 sleep() 才不会在 finally 里重新抢锁；
        // 之后再 shutdown/release 才能保证锁真的放掉。
        scope.cancel()
        sleeper?.shutdown()
        sleeper = null
        wakeLock.release()
        super.onDestroy()
    }

    /**
     * 用网络回调代替定时轮询：Wi-Fi 上线/掉线、门户拦截状态变化时立刻复查。
     * 这是免费的系统事件，比每分钟发一次 HTTP 探测省电得多。
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                DialerEngine.onNetworkChanged("网络可用")
            }

            override fun onLost(network: Network) {
                DialerEngine.onNetworkChanged("网络断开")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // VALIDATED 从有到无通常就是被门户拦截了，正是需要重认证的时刻
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val portal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                if (!validated || portal) {
                    DialerEngine.onNetworkChanged("联网校验失败或被门户拦截")
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { AppLog.error("注册网络回调失败: ${it.message}") }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        networkCallback = null
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "dialer"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.esurfing.client.action.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DialerService::class.java))
        }

        fun stop(context: Context) {
            val intent = Intent(context, DialerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
