package com.esurfing.client.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 认证通道，对应原客户端的 User-Agent 选择。 */
enum class Channel(val label: String, val userAgent: String) {
    ANDROID_11("Android 11 (2104)", "CCTP/android11_64/2104"),
    ANDROID_VPN("Android VPN (2093)", "CCTP/android64_vpn/2093"),
}

/** 保活策略：省电与及时性之间的取舍。 */
enum class PowerMode(val label: String, val summary: String) {
    /** 靠 AlarmManager 唤醒，只在真正收发时短暂持锁。心跳可能偶尔迟到，迟到后自动重认证。 */
    BATTERY("省电优先", "息屏后靠系统闹钟唤醒, 只在收发时唤醒 CPU"),

    /** 全程持 WakeLock，心跳绝不迟到，代价是持续耗电。 */
    STABILITY("稳定优先", "全程保持 CPU 唤醒, 心跳最准时, 更耗电"),
}

/**
 * 掉线检测间隔。
 *
 * 账号被限制设备数时，别的设备一登录本机就会被 AC 踢下线，而"踢"是静默的：
 * 心跳可能仍然正常，只是流量被重新拦截。只有主动探测外网才能发现，
 * 所以这个间隔决定了"掉线多久后自动重连"。
 */
enum class DetectInterval(val label: String, val millis: Long) {
    SEC_20("20 秒", 20_000L),
    SEC_45("45 秒", 45_000L),
    MIN_1("1.5 分钟", 90_000L),
    MIN_3("3 分钟", 180_000L),
}

data class AppSettings(
    val username: String = "",
    val password: String = "",
    val channel: Channel = Channel.ANDROID_11,
    val autoStart: Boolean = false,
    val bindWifi: Boolean = true,
    val powerMode: PowerMode = PowerMode.BATTERY,
    val detectInterval: DetectInterval = DetectInterval.SEC_45,
    val logLevel: LogLevel = LogLevel.INFO,
)

/** SharedPreferences 持久化的全局配置。 */
object SettingsStore {

    private const val FILE = "settings"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_BIND_WIFI = "bind_wifi"
    private const val KEY_POWER_MODE = "power_mode"
    private const val KEY_DETECT_INTERVAL = "detect_interval"
    private const val KEY_LOG_LEVEL = "log_level"

    private lateinit var appContext: Context

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val loaded = AppSettings(
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
            channel = runCatching {
                Channel.valueOf(prefs.getString(KEY_CHANNEL, Channel.ANDROID_11.name)!!)
            }.getOrDefault(Channel.ANDROID_11),
            autoStart = prefs.getBoolean(KEY_AUTO_START, false),
            bindWifi = prefs.getBoolean(KEY_BIND_WIFI, true),
            powerMode = runCatching {
                PowerMode.valueOf(prefs.getString(KEY_POWER_MODE, PowerMode.BATTERY.name)!!)
            }.getOrDefault(PowerMode.BATTERY),
            detectInterval = runCatching {
                DetectInterval.valueOf(
                    prefs.getString(KEY_DETECT_INTERVAL, DetectInterval.SEC_45.name)!!,
                )
            }.getOrDefault(DetectInterval.SEC_45),
            logLevel = runCatching {
                LogLevel.valueOf(prefs.getString(KEY_LOG_LEVEL, LogLevel.INFO.name)!!)
            }.getOrDefault(LogLevel.INFO),
        )
        _settings.value = loaded
        AppLog.minLevel = loaded.logLevel
    }

    fun update(settings: AppSettings) {
        _settings.value = settings
        AppLog.minLevel = settings.logLevel
        appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_USERNAME, settings.username)
            putString(KEY_PASSWORD, settings.password)
            putString(KEY_CHANNEL, settings.channel.name)
            putBoolean(KEY_AUTO_START, settings.autoStart)
            putBoolean(KEY_BIND_WIFI, settings.bindWifi)
            putString(KEY_POWER_MODE, settings.powerMode.name)
            putString(KEY_DETECT_INTERVAL, settings.detectInterval.name)
            putString(KEY_LOG_LEVEL, settings.logLevel.name)
        }.apply()
    }
}
