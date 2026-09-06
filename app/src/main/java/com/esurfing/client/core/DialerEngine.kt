package com.esurfing.client.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.esurfing.client.core.cipher.CipherFactory
import com.esurfing.client.core.cipher.DynamicZsm
import com.esurfing.client.core.cipher.SessionCipher
import com.esurfing.client.net.Cctp
import com.esurfing.client.net.HttpEngine
import com.esurfing.client.net.Identity
import com.esurfing.client.net.NetStatus
import com.esurfing.client.net.PortalHeaders
import com.esurfing.client.service.Sleeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DialerState {
    STOPPED,
    CHECKING,
    AUTHENTICATING,
    ONLINE,
    FAILED,
}

data class DialerStatus(
    val state: DialerState = DialerState.STOPPED,
    val message: String = "未运行",
    val algoId: String = "",
    val clientIp: String = "",
    val acIp: String = "",
    val ticket: String = "",
    val keepInterval: Long = 0,
    val authTimeMs: Long = 0,
    val lastHeartbeatMs: Long = 0,
    val beatFailures: Int = 0,
    /** 本次运行期间因为掉线而自动重连的次数。 */
    val reconnects: Int = 0,
    /** 最近一次被判定掉线的墙上时间。 */
    val lastDropMs: Long = 0,
    /** 校园网标志（wlanuserip 的前两段），仅供展示。 */
    val schoolSymbol: String = "",
)

/**
 * CCTP 拨号状态机：探测 → 取门户配置 → 初始化会话(取 Algo-ID) → 取 ticket → 登录 → 心跳。
 * 对应 C 版 DialerClient.c 的 dialer_app 循环，不包含防共享(excheck)部分。
 *
 * 等待通过注入的 [Sleeper] 完成，因此本类不关心 WakeLock / 闹钟这些细节。
 * 已认证后不做周期性 HTTP 探测——心跳本身就是活性检查，网络变化由
 * [onNetworkChanged] 事件驱动，这样一天的请求数从八万降到几十。
 */
object DialerEngine {

    /** 认证超过 1 天 23 小时 50 分后主动重新认证，避免被服务端踢下线。 */
    private const val REAUTH_AFTER_MS = 172_200_000L

    /** 服务端没给 keep-retry 时的心跳兜底间隔。 */
    private const val FALLBACK_BEAT_MS = 10 * 60_000L

    /** 心跳间隔下限，防止服务端下发异常小的值导致疯狂发包。 */
    private const val MIN_BEAT_MS = 10_000L

    /**
     * 省电优先模式下的探测间隔下限。
     * 每次探测都要把设备从 Doze 里唤醒，所以即使用户选了 20 秒，
     * 省电模式也按 45 秒执行——想要更快就切到稳定优先。
     */
    private const val MIN_PROBE_BATTERY_MS = 45_000L

    /** 单次等待的下限，避免算出 0 之后打成紧循环。 */
    private const val MIN_SLEEP_MS = 500L

    /** 单次等待上限，避免闹钟异常时循环彻底睡死。 */
    private const val MAX_SLEEP_SLICE_MS = 15 * 60_000L

    /** 心跳失败后的重试间隔。 */
    private const val BEAT_RETRY_DELAY_MS = 5_000L

    /** 登出总尝试次数：首次 + 最多 5 次重试，与 C 版 term() 一致。 */
    private const val LOGOUT_MAX_ATTEMPTS = 6

    /** 登出重试间隔，同样取自 C 版。 */
    private const val LOGOUT_RETRY_DELAY_MS = 1_000L

    /**
     * 已联网但没有我们建立的会话时的复查间隔。
     * 可以放得很宽，因为网络变化由 [onNetworkChanged] 事件驱动，不靠这个轮询发现。
     */
    private const val IDLE_RECHECK_MS = 5 * 60_000L

    private const val MAX_BEAT_FAILURES = 3

    /** 认证失败的退避阶梯（分钟），到顶后保持在最后一档持续重试。 */
    private val RETRY_BACKOFF_MIN = longArrayOf(1, 5, 10, 20, 30)

    /** 网络无响应时的退避上限。 */
    private const val MAX_TIMEOUT_BACKOFF_MS = 5 * 60_000L

    private const val MAX_REDIRECTS = 10

    private val portalHeaders = PortalHeaders()
    private val http = HttpEngine(portalHeaders)

    private val _status = MutableStateFlow(DialerStatus())
    val status: StateFlow<DialerStatus> = _status.asStateFlow()

    @Volatile
    private var stopRequested = false
    private var job: Job? = null

    @Volatile
    private var sleeper: Sleeper? = null

    private var identity: Identity = Cctp.newIdentity()
    private var cipher: SessionCipher? = null
    private var algoId: String = Cctp.NULL_ALGO_ID
    private var authUrl: String = ""
    private var ticketUrl: String = ""
    private var keepUrl: String = ""
    private var termUrl: String = ""
    private var ticket: String = ""
    private var clientIp: String = ""
    private var acIp: String = ""
    private var keepInterval: Long = 0

    /** 认证完成的墙上时间，仅用于界面展示。 */
    private var authTime: Long = 0

    /** 上次心跳的墙上时间，仅用于界面展示。 */
    private var lastBeat: Long = 0

    /**
     * 下面两个时间戳用 elapsedRealtime（单调递增的开机计时）而不是墙上时钟：
     * NTP 校正或用户改时间会让墙上时钟跳变，那样会导致心跳早发或睡过头。
     */
    private var authElapsed: Long = 0
    private var nextBeatElapsed: Long = 0
    private var nextProbeElapsed: Long = 0

    /** 上次连通性探测的时刻，用来实时推算下次到期时间。 */
    private var lastProbeElapsed: Long = 0

    /** 收到网络变化事件后，下一轮立刻探一次连通性而不是等到期。 */
    @Volatile
    private var forceProbe = false

    private var beatFailures = 0

    /**
     * 掉线自动重连的统计，只在 [start] 时清零：
     * 会话本身会被 [resetSession] 反复重建，这两个字段要跨会话保留，
     * 否则界面上永远显示 0，用户就看不出"到底被踢了几次"。
     */
    private var reconnects = 0
    private var lastDrop: Long = 0

    /** 循环因失败退出时的提示，避免被收尾的"已停止"覆盖掉。 */
    private var terminalMessage: String? = null

    /**
     * 校园网标志。跟 C 版的 g_school_network_symbol 一样是全局的：
     * 只在第一次拿到时赋值，不跟着会话重建而清空。
     */
    private var schoolSymbol: String = ""

    val isRunning: Boolean
        get() = job?.isActive == true

    fun start(context: Context, scope: CoroutineScope, sleeper: Sleeper) {
        if (isRunning) return
        val app = context.applicationContext
        stopRequested = false
        reconnects = 0
        lastDrop = 0
        schoolSymbol = ""
        this.sleeper = sleeper
        job = scope.launch {
            try {
                runLoop(app, sleeper)
            } finally {
                // 协程被取消（服务被系统销毁）时走不到 runLoop 的收尾，
                // 状态会永远停在"已连接"，界面据此显示"断开连接"却什么都没在跑。
                // 这里补一次终态，让界面和事实一致。
                val state = _status.value.state
                if (state != DialerState.STOPPED && state != DialerState.FAILED) {
                    publish(DialerState.STOPPED, "已停止")
                }
            }
        }
    }

    /** 请求停止并等待循环退出（退出前会尝试登出）。 */
    suspend fun stopAndJoin() {
        stopRequested = true
        sleeper?.wake()
        job?.join()
        job = null
        sleeper = null
    }

    /**
     * 网络状态发生变化（Wi-Fi 上线/掉线、门户拦截状态改变）。
     * 置位强制探测标记再唤醒循环——只唤醒是不够的，
     * 循环醒来后如果发现心跳还没到期就会接着睡，那这次事件等于白来。
     */
    fun onNetworkChanged(reason: String) {
        if (!isRunning) return
        AppLog.debug("网络变化: $reason")
        forceProbe = true
        sleeper?.wake()
    }

    private suspend fun runLoop(context: Context, sleeper: Sleeper) {
        AppLog.info("拨号服务已启动")
        // resetSession 里会生成一组新身份
        resetSession()
        terminalMessage = null
        AppLog.debug("主机名: ${identity.hostName}")
        publish(DialerState.CHECKING, "正在检测网络")

        var authRetry = 0
        var timeoutRetry = 0

        while (!stopRequested) {
            val settings = SettingsStore.settings.value
            http.network = if (settings.bindWifi) wifiNetwork(context) else null

            if (isSessionEstablished()) {
                val wait = serviceSession(settings)
                if (wait > 0) sleeper.sleep(minOf(wait, MAX_SLEEP_SLICE_MS))
                continue
            }

            when (probe(settings)) {
                NetStatus.SUCCESS -> {
                    timeoutRetry = 0
                    authRetry = 0
                    publish(DialerState.ONLINE, "已连接至互联网")
                    sleeper.sleep(IDLE_RECHECK_MS)
                }

                NetStatus.REDIRECT -> {
                    timeoutRetry = 0
                    AppLog.info("需要认证")
                    if (authenticate(settings)) {
                        authRetry = 0
                    } else {
                        // 明确的失败（账号密码错、算法不支持）已经在内部走 fail() 停机了，
                        // 走到这里基本都是网络抖动之类的临时问题，作为常驻守护应当一直重试，
                        // 而不是退避几次就彻底躺平等用户手动重启。
                        if (stopRequested) break
                        authRetry++
                        val waitMin = RETRY_BACKOFF_MIN[
                            (authRetry - 1).coerceAtMost(RETRY_BACKOFF_MIN.lastIndex),
                        ]
                        AppLog.error("认证失败 (第 $authRetry 次), $waitMin 分钟后重试")
                        publish(
                            DialerState.AUTHENTICATING,
                            "认证失败 $authRetry 次, $waitMin 分钟后重试",
                        )
                        sleeper.sleep(waitMin * 60_000)
                    }
                }

                NetStatus.TIMEOUT -> {
                    authRetry = 0
                    timeoutRetry++
                    val wait = (10_000L * timeoutRetry).coerceAtMost(MAX_TIMEOUT_BACKOFF_MS)
                    AppLog.warn("网络响应超时 (第 $timeoutRetry 次), ${wait / 1000} 秒后重试")
                    publish(DialerState.CHECKING, "网络无响应, 正在重试")
                    sleeper.sleep(wait)
                }

                else -> {
                    publish(DialerState.CHECKING, "网络错误, 等待重试")
                    sleeper.sleep(30_000)
                }
            }
        }

        val settings = SettingsStore.settings.value
        if (isSessionEstablished()) logout(settings)
        // 失败退出时保留失败原因和现场字段，否则用户只会看到一句"已停止"，等于把线索丢了
        val reason = terminalMessage
        if (reason != null) {
            publish(DialerState.FAILED, reason)
            resetSession()
        } else {
            resetSession()
            publish(DialerState.STOPPED, "已停止")
        }
        AppLog.info("拨号服务已停止")
    }

    /** 标记为终态失败：记下原因并请求退出循环。 */
    private fun fail(message: String) {
        terminalMessage = message
        stopRequested = true
        publish(DialerState.FAILED, message)
    }

    private fun isSessionEstablished(): Boolean = cipher != null && authTime != 0L

    /**
     * 维护一个已建立的会话：到期就发心跳，否则算出还能睡多久。
     * @return 建议的等待毫秒数；0 表示需要立刻重新走一轮循环。
     */
    private fun serviceSession(settings: AppSettings): Long {
        val now = SystemClock.elapsedRealtime()

        if (now - authElapsed >= REAUTH_AFTER_MS) {
            AppLog.warn("认证时长超限, 重新认证")
            logout(settings)
            resetSession()
            return 0
        }

        // 连通性复查。心跳只能证明 AC 还愿意跟我们说话，不能证明数据面还通：
        // 账号在别处登录、AC 单方面回收会话时，state.cgi 可能照常响应，
        // 而流量其实已经被重新拦截。所以必须真的去探一次外网。
        // 到期时间由"上次探测时刻 + 当前间隔"实时算出，而不是探完就固化下来：
        // 用户把间隔从 3 分钟改成 20 秒时，改动下一轮就生效，不用等旧的 3 分钟走完。
        nextProbeElapsed = lastProbeElapsed + probeIntervalMs(settings)
        val forced = forceProbe
        if (forced) forceProbe = false
        if (forced || now >= nextProbeElapsed) {
            lastProbeElapsed = SystemClock.elapsedRealtime()
            nextProbeElapsed = lastProbeElapsed + probeIntervalMs(settings)
            if (probe(settings) == NetStatus.REDIRECT) {
                // 会话还在、心跳也可能正常，但流量已经被重新拦截。
                // 最常见的原因就是账号被限制同时在线设备数，别的设备一登录就把这台踢了。
                noteDrop("连通性探测被拦截, 疑似账号在其它设备登录被踢下线")
                publish(DialerState.AUTHENTICATING, "连接已掉线, 正在重新认证")
                resetSession()
                return 0
            }
        }

        if (SystemClock.elapsedRealtime() >= nextBeatElapsed) {
            if (heartbeat(settings)) {
                beatFailures = 0
                nextBeatElapsed = SystemClock.elapsedRealtime() + beatIntervalMs()
            } else {
                beatFailures++
                publish(
                    DialerState.AUTHENTICATING,
                    "心跳失败, 正在重试 ($beatFailures/$MAX_BEAT_FAILURES)",
                )
                if (beatFailures >= MAX_BEAT_FAILURES) {
                    // 心跳连续失败通常意味着会话已被服务端丢弃（含闹钟迟到导致的超时），
                    // 直接重新认证而不是死磕心跳
                    noteDrop("心跳连续失败 $beatFailures 次, 会话已失效, 丢弃并重新认证")
                    beatFailures = 0
                    resetSession()
                    return 0
                }
                AppLog.warn("心跳失败 ($beatFailures/$MAX_BEAT_FAILURES), ${BEAT_RETRY_DELAY_MS / 1000} 秒后重试")
                nextBeatElapsed = SystemClock.elapsedRealtime() + BEAT_RETRY_DELAY_MS
            }
        }

        // 睡到心跳与探测里更早到期的那个
        val next = minOf(nextBeatElapsed, nextProbeElapsed)
        return (next - SystemClock.elapsedRealtime()).coerceAtLeast(MIN_SLEEP_MS)
    }

    /**
     * 连通性探测间隔，由设置里的"掉线检测间隔"决定。
     * 稳定优先模式本来就一直持锁，用户选多快就多快；
     * 省电模式下每次探测都要唤醒设备，所以有个 [MIN_PROBE_BATTERY_MS] 下限。
     */
    private fun probeIntervalMs(settings: AppSettings): Long {
        val wanted = settings.detectInterval.millis
        return when (settings.powerMode) {
            PowerMode.STABILITY -> wanted
            PowerMode.BATTERY -> wanted.coerceAtLeast(MIN_PROBE_BATTERY_MS)
        }
    }

    /**
     * 心跳间隔由服务端下发，这里加个下限：万一收到 0 或 1 秒之类的值，
     * 循环会退化成疯狂发包。上限交给 [MAX_SLEEP_SLICE_MS] 分片处理。
     */
    private fun beatIntervalMs(): Long {
        val fromServer = if (keepInterval > 0) keepInterval * 1000 else FALLBACK_BEAT_MS
        return fromServer.coerceAtLeast(MIN_BEAT_MS)
    }

    /**
     * 探测是否被门户拦截。
     *
     * 三种回应都要处理，只认 302 是不够的：
     * - 204 / 404：通了（C 版同样把 404 当作已联网）；
     * - 3xx 带 Location：被拦截，需要认证；
     * - 200 带正文：有的 AC 不发 302，直接把门户页当响应体返回。这种情况必须看
     *   正文里有没有门户标记，否则会一路掉进"网络错误"分支干等，永远不去认证。
     */
    private fun probe(settings: AppSettings): NetStatus {
        val resp = http.get(Cctp.DETECT_URL, settings.channel.userAgent, identity.clientId)
        return when (resp.status) {
            NetStatus.NOT_FOUND -> NetStatus.SUCCESS

            NetStatus.HAVE_RES -> {
                if (Cctp.looksLikePortalPage(resp.body)) {
                    AppLog.debug("探测返回 200 且含门户标记, 视为需要认证")
                    NetStatus.REDIRECT
                } else {
                    AppLog.verbose("探测返回 200, 未见门户标记, 视为已联网")
                    NetStatus.SUCCESS
                }
            }

            else -> resp.status
        }
    }

    private fun authenticate(settings: AppSettings): Boolean {
        if (settings.username.isBlank() || settings.password.isBlank()) {
            fail("请先填写账号和密码")
            AppLog.error("账号或密码为空")
            return false
        }
        // 账号密码是直接拼进 XML 的（与 C 版一致，不做转义）。含 XML 特殊字符时
        // 报文会变成非法 XML，服务端解析失败 → 认证一直失败且看不出原因。
        // 这里至少把线索打进日志。
        val illegal = "&<>\"'".filter { it in settings.username || it in settings.password }
        if (illegal.isNotEmpty()) {
            AppLog.warn("账号或密码含 XML 特殊字符 [$illegal], 服务端可能无法解析报文")
        }
        publish(DialerState.AUTHENTICATING, "正在获取门户配置")

        val ua = settings.channel.userAgent
        val portal = followToPortal(Cctp.DETECT_URL, ua) ?: run {
            if (!stopRequested) AppLog.error("获取门户页面失败")
            return false
        }
        val config = Cctp.between(portal, Cctp.PORTAL_CONFIG_START, Cctp.PORTAL_CONFIG_END) ?: run {
            AppLog.error("提取门户配置失败")
            return false
        }

        authUrl = Cctp.tagValue(config, "auth-url") ?: run {
            AppLog.error("提取 Auth URL 失败")
            return false
        }
        ticketUrl = Cctp.tagValue(config, "ticket-url") ?: run {
            AppLog.error("提取 Ticket URL 失败")
            return false
        }
        clientIp = Cctp.urlParam(ticketUrl, "wlanuserip").orEmpty()
        acIp = Cctp.urlParam(ticketUrl, "wlanacip").orEmpty()
        AppLog.info("Auth URL: $authUrl")
        AppLog.info("Ticket URL: $ticketUrl")
        AppLog.info("Client IP: $clientIp, AC IP: $acIp")
        if (clientIp.isEmpty() || acIp.isEmpty()) {
            AppLog.error("未能从 Ticket URL 提取 wlanuserip / wlanacip")
            return false
        }
        noteSchoolSymbol(clientIp)

        // 整套认证最多十几个请求、每个 10 秒超时；用户点了停止不该等上一两分钟
        if (stopRequested) return false
        if (!initSession(settings)) return false
        if (stopRequested) return false
        if (!requestTicket(ua)) return false
        if (stopRequested) return false
        if (!login(settings)) return false

        authTime = System.currentTimeMillis()
        authElapsed = SystemClock.elapsedRealtime()
        lastBeat = authTime
        beatFailures = 0
        forceProbe = false
        nextBeatElapsed = authElapsed + beatIntervalMs()
        lastProbeElapsed = authElapsed
        nextProbeElapsed = authElapsed + probeIntervalMs(settings)
        publish(DialerState.ONLINE, "已认证登录")
        AppLog.info("已认证登录")
        return true
    }

    /**
     * 首包 POST ticket.cgi，服务端回一个 ZSM 交付包，从里面拿到会话算法。
     *
     * 有两种交付包，处理方式完全不同：
     * - **静态**（Android / Linux / Windows 通道）：头部带一个 Algo-ID，
     *   到 [CipherFactory] 里查硬编码密钥表。
     * - **动态**（iOS / macOS 通道）：正文是 TEA + LZMA 压缩的 JS 模块，
     *   密钥每次会话都不一样，要现场解包；头部那个 UUID 只是模块 ID，查表查不到。
     *
     * 判定不只看用户选的通道，也看包本身的结构（[DynamicZsm.looksLikeDynamicZsm]）——
     * 服务端给什么由它决定，不由客户端的选择决定，两边都留了回退路径。
     */
    private fun initSession(settings: AppSettings): Boolean {
        publish(DialerState.AUTHENTICATING, "正在初始化会话")
        val userAgent = settings.channel.userAgent
        // 动态通道首包发空 body；静态通道发全零 Algo-ID
        val firstBody = if (settings.channel.dynamicZsm) "" else Cctp.NULL_ALGO_ID
        val resp = http.post(ticketUrl, firstBody, userAgent, identity.clientId, Cctp.NULL_ALGO_ID)
        if (resp.status != NetStatus.HAVE_RES || resp.bodyBytes == null) {
            AppLog.error("初始化会话失败")
            return false
        }
        val body = resp.bodyBytes
        val dynamic = DynamicZsm.looksLikeDynamicZsm(body)
        AppLog.debug("交付包 ${body.size} 字节, 动态模块: ${if (dynamic) "是" else "否"}")

        if (settings.channel.dynamicZsm || dynamic) {
            if (!settings.channel.dynamicZsm) {
                AppLog.warn("当前通道不是 iOS/macOS, 但服务端下发了动态模块, 按动态密钥解包")
            }
            val result = DynamicZsm.createCipher(body)
            if (result != null) {
                cipher = result.first
                algoId = result.second
                AppLog.info("动态 ZSM 会话已建立, 模块 ID: $algoId")
                return true
            }
            // 选了 iOS/macOS 却解不开：没有静态密钥可退，直接失败
            if (settings.channel.dynamicZsm) {
                fail("动态 ZSM 解包失败, 请把日志反馈上来")
                return false
            }
            AppLog.warn("动态 ZSM 解包失败, 回退到静态密钥表")
        }

        AppLog.debug("交付包头部: ${Cctp.deliveryHeader(body)}")
        val id = Cctp.extractAlgoId(body) ?: run {
            AppLog.error("无法从交付包解析 Algo-ID")
            publish(DialerState.AUTHENTICATING, "解析 Algo-ID 失败")
            return false
        }
        AppLog.info("Algo ID: $id")
        val created = CipherFactory.create(id) ?: run {
            // 查不到静态密钥时再试一次动态解包：有些 AC 会给结构不标准的动态模块
            AppLog.warn("静态密钥表里没有 $id, 尝试按动态模块解包")
            val fallback = DynamicZsm.createCipher(body)
            if (fallback != null) {
                cipher = fallback.first
                algoId = fallback.second
                return true
            }
            fail("服务器下发了尚未支持的算法: $id")
            return false
        }
        algoId = id
        cipher = created
        return true
    }

    private fun requestTicket(userAgent: String): Boolean {
        publish(DialerState.AUTHENTICATING, "正在获取 Ticket")
        val session = cipher ?: return false
        val payload = session.encrypt(Cctp.ticketXml(userAgent, identity, clientIp, acIp)) ?: run {
            AppLog.error("加密获取 Ticket XML 失败")
            return false
        }
        val resp = http.post(ticketUrl, payload, userAgent, identity.clientId, algoId)
        if (resp.status != NetStatus.HAVE_RES || resp.body.isNullOrEmpty()) {
            AppLog.error("获取 Ticket 响应失败")
            return false
        }
        val xml = session.decrypt(resp.body) ?: run {
            AppLog.error("解密 Ticket 内容失败")
            return false
        }
        AppLog.verbose("获取 Ticket 响应: $xml")
        ticket = Cctp.tagValue(xml, "ticket") ?: run {
            reportServerError(xml, "解析 Ticket 失败")
            return false
        }
        AppLog.info("Ticket: $ticket")
        return true
    }

    private fun login(settings: AppSettings): Boolean {
        publish(DialerState.AUTHENTICATING, "正在登录")
        val session = cipher ?: return false
        val ua = settings.channel.userAgent
        val xml = Cctp.loginXml(ua, identity, ticket, settings.username, settings.password)
        val payload = session.encrypt(xml) ?: run {
            AppLog.error("加密登录 XML 失败")
            return false
        }
        val resp = http.post(authUrl, payload, ua, identity.clientId, algoId)
        if (resp.status != NetStatus.HAVE_RES || resp.body.isNullOrEmpty()) {
            AppLog.error("登录响应失败")
            return false
        }
        val decrypted = session.decrypt(resp.body) ?: run {
            AppLog.error("解密登录响应失败")
            return false
        }
        AppLog.verbose("登录响应: $decrypted")

        keepUrl = Cctp.tagValue(decrypted, "keep-url") ?: run {
            reportServerError(decrypted, "登录失败")
            return false
        }
        termUrl = Cctp.tagValue(decrypted, "term-url").orEmpty()
        keepInterval = Cctp.tagValue(decrypted, "keep-retry")?.toLongOrNull() ?: 0
        AppLog.info("Keep-Url: $keepUrl")
        AppLog.info("Term-Url: $termUrl")
        AppLog.info("心跳间隔: $keepInterval 秒")
        return true
    }

    /**
     * 发送一次心跳。返回 false 表示服务端没有接受这次心跳，需要退避重试。
     *
     * 判定条件比"HTTP 200"严格得多：必须能解密、code 不是错误码、且带回
     * interval 节点。C 版 heartbeat() 在解析不到 interval 时就 return false，
     * 这里保持一致——否则会话早被服务端回收了，客户端还以为一切正常。
     */
    private fun heartbeat(settings: AppSettings): Boolean {
        val session = cipher ?: return false
        if (keepUrl.isEmpty()) return false
        val ua = settings.channel.userAgent
        val payload = session.encrypt(Cctp.keepAliveXml(ua, identity, clientIp, ticket))
            ?: return false
        val resp = http.post(keepUrl, payload, ua, identity.clientId, algoId)
        if (resp.status != NetStatus.HAVE_RES || resp.body.isNullOrEmpty()) {
            AppLog.error("心跳响应失败")
            return false
        }
        val xml = session.decrypt(resp.body) ?: run {
            AppLog.error("解密心跳内容失败")
            return false
        }
        AppLog.verbose("心跳响应: $xml")

        if (Cctp.isErrorResponse(xml)) {
            val code = Cctp.errorCode(xml)
            val reason = CctpErrors.describe(code.orEmpty()) ?: Cctp.tagValue(xml, "reason")
            AppLog.error("心跳被拒绝: code=$code${reason?.let { ", $it" } ?: ""}")
            return false
        }

        val interval = Cctp.heartbeatInterval(xml) ?: run {
            AppLog.error("心跳响应缺少 interval, 视为会话已失效")
            return false
        }
        keepInterval = interval
        lastBeat = System.currentTimeMillis()
        AppLog.info("心跳完成, 下一次: $keepInterval 秒后")
        publish(DialerState.ONLINE, "已认证登录")
        return true
    }

    /**
     * 登出。失败会重试，最多 [LOGOUT_MAX_ATTEMPTS] 次、每次隔 1 秒，与 C 版 term() 一致。
     *
     * 重试不是为了好看：登出没发出去，AC 那边的会话就还挂着，占着账号的
     * "同时在线设备数"。账号只允许一台设备时，下一次认证会被自己刚才那个
     * 残留会话挤掉，表现就是"断开后立即重连反而连不上"。
     *
     * 这里用 Thread.sleep 而不是 [Sleeper]：间隔只有 1 秒，不值得上闹铟，
     * 而且本函数也会从非 suspend 的 [serviceSession] 里被调用。
     * 代价是网络不通时停止服务最多多花几秒——C 版同样如此。
     */
    private fun logout(settings: AppSettings) {
        val session = cipher ?: return
        if (termUrl.isEmpty()) return
        val ua = settings.channel.userAgent
        val payload = session.encrypt(Cctp.keepAliveXml(ua, identity, clientIp, ticket)) ?: run {
            AppLog.error("加密登出 XML 失败")
            return
        }
        for (attempt in 1..LOGOUT_MAX_ATTEMPTS) {
            val resp = http.post(termUrl, payload, ua, identity.clientId, algoId)
            if (resp.status == NetStatus.HAVE_RES || resp.status == NetStatus.SUCCESS) {
                AppLog.info(if (attempt == 1) "已登出" else "已登出 (重试 ${attempt - 1} 次后成功)")
                return
            }
            if (attempt == LOGOUT_MAX_ATTEMPTS) break
            AppLog.warn(
                "登出失败 (${resp.status}), ${LOGOUT_RETRY_DELAY_MS / 1000} 秒后重试: " +
                    "第 $attempt 次, 最多 ${LOGOUT_MAX_ATTEMPTS - 1} 次",
            )
            Thread.sleep(LOGOUT_RETRY_DELAY_MS)
        }
        AppLog.error("登出失败, 已重试 ${LOGOUT_MAX_ATTEMPTS - 1} 次, 会话可能在服务端残留")
    }

    /**
     * 记下校园网标志 = wlanuserip 的前两段（如 10.23），对应 C 版 get_school_ip_symbol()。
     *
     * 纯展示用：C 版把它放进 web UI 的状态 JSON，用来一眼认出当前接入的是哪个
     * 校区 / 网段。C 版从重定向落地页 last_location 的 wlanuserip 取，而我们从
     * ticket-url 的同名参数取——同一个值，只是手里现成就有。提取规则在 [Cctp.schoolSymbol]。
     */
    private fun noteSchoolSymbol(ip: String) {
        if (schoolSymbol.isNotEmpty()) return
        val symbol = Cctp.schoolSymbol(ip) ?: run {
            AppLog.warn("从 wlanuserip 取不出校园网标志: $ip")
            return
        }
        schoolSymbol = symbol
        AppLog.info("获取到校园网标志: $symbol")
    }

    /** 服务端返回了 code/reason 时把它翻译成可读提示。 */
    private fun reportServerError(xml: String, prefix: String) {
        val code = Cctp.tagValue(xml, "code")
        val reason = Cctp.tagValue(xml, "reason")
        val known = code?.let { CctpErrors.describe(it) }
        val detail = listOfNotNull(
            code?.let { "code=$it" },
            known,
            reason?.takeIf { it != known },
        ).joinToString(", ")
        val message = if (detail.isEmpty()) prefix else "$prefix: $detail"
        AppLog.error(message)
        publish(DialerState.AUTHENTICATING, message)

        // 账号密码错误重试也没用，直接停下等用户改配置
        if (code?.trim() in FATAL_CODES) {
            fail(message)
        }
    }

    private fun followToPortal(start: String, userAgent: String): String? {
        var url = start
        repeat(MAX_REDIRECTS) {
            if (stopRequested) return null
            val resp = http.get(url, userAgent, identity.clientId)
            when (resp.status) {
                NetStatus.REDIRECT -> {
                    url = resp.location ?: return null
                    AppLog.verbose("重定向至: $url")
                }

                NetStatus.HAVE_RES -> return resp.body
                else -> return null
            }
        }
        AppLog.error("重定向次数过多")
        return null
    }

    /**
     * 丢弃会话，回到"未认证"状态。
     *
     * 会顺带**重新生成身份**（Client-ID / 主机名 / MAC）——这一点对应 C 版的
     * `reset()` = `clean()` + `refresh_states()`，不是可选项：AC 会把刚失效的会话
     * 和那一组 Client-ID/MAC 绑在一起，拿同一组身份去重认证会被拒。
     * 之前漏了这步，表现就是自动重连一直失败、必须手动断开再连（手动重启进程
     * 才会生成新身份）。
     */
    private fun resetSession() {
        cipher = null
        algoId = Cctp.NULL_ALGO_ID
        ticket = ""
        keepUrl = ""
        termUrl = ""
        keepInterval = 0
        authTime = 0
        authElapsed = 0
        lastBeat = 0
        nextBeatElapsed = 0
        nextProbeElapsed = 0
        lastProbeElapsed = 0
        forceProbe = false
        beatFailures = 0
        portalHeaders.reset()
        // 主机名/ostag 跟着当前通道走：iOS/macOS 要一直报成真机的样子，
        // 换身份时不能退回随机 hex, 否则服务端会看到"iPhone 突然改名"。
        val channel = SettingsStore.settings.value.channel
        identity = Cctp.newIdentity(channel.hostName, channel.osTag)
        AppLog.debug("已重置身份: Client-ID ${identity.clientId}, MAC ${identity.macAddress}")
    }

    /**
     * 找到用于绑定请求的 Wi-Fi 网络。
     *
     * 优先取当前活动网络（它才是系统实际在用的那一个）；活动网络不是 Wi-Fi 时
     * 再去枚举。只要求 INTERNET 能力而**不要求** VALIDATED：认证之前这张网
     * 恰恰是"未通过联网校验"的状态，要求 VALIDATED 会导致永远找不到网卡。
     *
     * allNetworks 已废弃，但替代的 registerNetworkCallback 是异步的，这里需要同步取值。
     */
    @Suppress("DEPRECATION")
    private fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null

        fun isUsableWifi(network: Network?): Boolean {
            val caps = network?.let { cm.getNetworkCapabilities(it) } ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        cm.activeNetwork?.takeIf { isUsableWifi(it) }?.let { return it }
        return cm.allNetworks.firstOrNull { isUsableWifi(it) }
    }

    private fun publish(state: DialerState, message: String) {
        _status.value = DialerStatus(
            state = state,
            message = message,
            algoId = algoId,
            clientIp = clientIp,
            acIp = acIp,
            ticket = ticket,
            keepInterval = keepInterval,
            authTimeMs = authTime,
            lastHeartbeatMs = lastBeat,
            beatFailures = beatFailures,
            reconnects = reconnects,
            lastDropMs = lastDrop,
            schoolSymbol = schoolSymbol,
        )
    }

    /**
     * 记一次掉线：计数、记时间、写日志，并立刻把统计推给界面。
     * 单独抽出来是为了让"心跳失败"和"探测被拦截"两条掉线路径口径一致。
     */
    private fun noteDrop(reason: String) {
        reconnects++
        lastDrop = System.currentTimeMillis()
        AppLog.error("$reason (第 $reconnects 次自动重连)")
    }

    /** 这些错误码重试无意义，需要用户干预。 */
    private val FATAL_CODES = setOf("140100", "140104", "140105", "140003")
}

/** CCTP 服务端错误码，取自官方客户端前端的提示表。 */
object CctpErrors {

    private val TABLE = mapOf(
        "140001" to "无效的请求",
        "140002" to "客户端版本不正确",
        "140003" to "客户端已被停用",
        "140010" to "客户端 IP 不正确",
        "140011" to "无效的 MAC 地址",
        "140012" to "无效或失效的 Client-ID",
        "140013" to "无效或失效的 Algo-ID",
        "140014" to "无效 ticket 或登录校验失败",
        "140015" to "校验值 (CDC-Checksum) 错误",
        "140100" to "账号或密码错误",
        "140101" to "无效挑战值",
        "140102" to "拨号请求被拒",
        "140103" to "网关接入数已满",
        "140104" to "用户不存在",
        "140105" to "账号受限",
        "140200" to "客户端密钥过期",
        "140300" to "等待终端发起",
        "140301" to "等待服务端验证",
        "140901" to "数据库连接失败",
        "140902" to "前置服务器连接失败",
        "140990" to "客户端服务器连接失败",
    )

    fun describe(code: String): String? = TABLE[code.trim()]
}
