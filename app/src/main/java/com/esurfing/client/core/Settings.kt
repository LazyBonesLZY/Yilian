package com.esurfing.client.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 认证通道，对应原客户端的 User-Agent 与身份字段。
 *
 * [dynamicZsm] 为 true 的通道（iOS / macOS）不走"Algo-ID → 硬编码密钥表"：
 * ticket.cgi 首包返回一个动态模块，密钥每次会话都不一样，要现场解包出来。
 *
 * [hostName] / [osTag] 为 null 时沿用随机身份里的主机名（Android 通道的做法）；
 * iOS / macOS 通道要报成真机的样子，所以写死。
 */
enum class Channel(
    val label: String,
    val userAgent: String,
    val hostName: String? = null,
    val osTag: String? = null,
    val dynamicZsm: Boolean = false,
) {
    ANDROID_11("Android 11 (2104)", "CCTP/android11_64/2104"),
    ANDROID_VPN("Android VPN (2093)", "CCTP/android64_vpn/2093"),
    IOS(
        label = "iOS (4023)",
        userAgent = "CCTP/iOSdy/4023",
        hostName = "iPhone 14",
        osTag = "iPhone iOS 17.0",
        dynamicZsm = true,
    ),
    MACOS(
        label = "macOS (5019)",
        userAgent = "CCTP/macdy/5019",
        hostName = "MacBookPro",
        osTag = "macOS,14.4",
        dynamicZsm = true,
    ),
}

/** 保活策略：省电与及时性之间的取舍。 */
enum class PowerMode(val label: String, val summary: String) {
    /** 靠 AlarmManager 唤醒，只在真正收发时短暂持锁。心跳可能偶尔迟到，迟到后自动重认证。 */
    BATTERY("省电优先", "息屏后靠系统闹钟唤醒, 只在收发时唤醒 CPU"),

    /** 全程持 WakeLock，心跳绝不迟到，代价是持续耗电。 */
    STABILITY("稳定优先", "全程保持 CPU 唤醒, 心跳最准时, 更耗电"),
}

/**
 * 掉线检测间隔的取值范围（秒）。
 *
 * 账号被限制设备数时，别的设备一登录本机就会被 AC 踢下线，而"踢"是静默的：
 * 心跳可能仍然正常，只是流量被重新拦截。只有主动探测外网才能发现，
 * 所以这个间隔决定了"掉线多久后自动重连"。
 *
 * 上限 5 分钟：再长就谈不上"检测"了，掉线大半天才发现还不如手动重连。
 * 下限 10 秒：每次探测都是一个真实 HTTP 请求，更密只是徒增耗电。
 */
object DetectRange {
    const val MIN_SEC = 10
    const val MAX_SEC = 300
    const val STEP_SEC = 5
    const val DEFAULT_SEC = 45

    /** 滑块的离散档位数，Miuix 的 steps 是"两端之间的点数"，所以要减一。 */
    val STEPS = (MAX_SEC - MIN_SEC) / STEP_SEC - 1

    /** 落到合法范围并对齐步长。用户手改 SharedPreferences 也不会让循环失控。 */
    fun clamp(sec: Int): Int {
        val bounded = sec.coerceIn(MIN_SEC, MAX_SEC)
        val aligned = ((bounded - MIN_SEC + STEP_SEC / 2) / STEP_SEC) * STEP_SEC + MIN_SEC
        return aligned.coerceIn(MIN_SEC, MAX_SEC)
    }

    fun label(sec: Int): String = if (sec % 60 == 0 && sec >= 60) {
        "${sec / 60} 分钟"
    } else if (sec > 60) {
        "${sec / 60} 分 ${sec % 60} 秒"
    } else {
        "$sec 秒"
    }

    /** 1.2.0 及以前是四档枚举，升级上来时把旧值映射成秒。 */
    fun fromLegacyName(name: String?): Int = when (name) {
        "SEC_20" -> 20
        "SEC_45" -> 45
        "MIN_1" -> 90
        "MIN_3" -> 180
        else -> DEFAULT_SEC
    }
}

data class AppSettings(
    val username: String = "",
    val password: String = "",
    val channel: Channel = Channel.ANDROID_11,
    val autoStart: Boolean = false,
    val bindWifi: Boolean = true,
    val powerMode: PowerMode = PowerMode.BATTERY,
    /** 掉线检测间隔，秒。合法范围见 [DetectRange]。 */
    val detectIntervalSec: Int = DetectRange.DEFAULT_SEC,
    val logLevel: LogLevel = LogLevel.INFO,
    val logToFile: Boolean = true,
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
    /** 1.2.0 及以前的四档枚举，只在迁移时读一次。 */
    private const val KEY_DETECT_INTERVAL_LEGACY = "detect_interval"
    private const val KEY_DETECT_INTERVAL_SEC = "detect_interval_sec"
    private const val KEY_LOG_LEVEL = "log_level"
    private const val KEY_LOG_TO_FILE = "log_to_file"

    private lateinit var appContext: Context

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        LogStore.init(appContext)
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
            detectIntervalSec = DetectRange.clamp(
                if (prefs.contains(KEY_DETECT_INTERVAL_SEC)) {
                    prefs.getInt(KEY_DETECT_INTERVAL_SEC, DetectRange.DEFAULT_SEC)
                } else {
                    // 从旧版本升级上来：把四档枚举换算成秒，不要让用户的选择被默认值覆盖
                    DetectRange.fromLegacyName(prefs.getString(KEY_DETECT_INTERVAL_LEGACY, null))
                },
            ),
            logLevel = runCatching {
                LogLevel.valueOf(prefs.getString(KEY_LOG_LEVEL, LogLevel.INFO.name)!!)
            }.getOrDefault(LogLevel.INFO),
            logToFile = prefs.getBoolean(KEY_LOG_TO_FILE, true),
        )
        _settings.value = loaded
        AppLog.minLevel = loaded.logLevel
        LogStore.enabled = loaded.logToFile
    }

    fun update(settings: AppSettings) {
        _settings.value = settings
        AppLog.minLevel = settings.logLevel
        LogStore.enabled = settings.logToFile
        appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_USERNAME, settings.username)
            putString(KEY_PASSWORD, settings.password)
            putString(KEY_CHANNEL, settings.channel.name)
            putBoolean(KEY_AUTO_START, settings.autoStart)
            putBoolean(KEY_BIND_WIFI, settings.bindWifi)
            putString(KEY_POWER_MODE, settings.powerMode.name)
            putInt(KEY_DETECT_INTERVAL_SEC, DetectRange.clamp(settings.detectIntervalSec))
            putString(KEY_LOG_LEVEL, settings.logLevel.name)
            putBoolean(KEY_LOG_TO_FILE, settings.logToFile)
        }.apply()
    }
}
