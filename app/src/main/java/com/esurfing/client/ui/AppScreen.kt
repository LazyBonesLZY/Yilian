package com.esurfing.client.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esurfing.client.BuildConfig
import com.esurfing.client.core.AppLog
import com.esurfing.client.core.AppSettings
import com.esurfing.client.core.BatteryOptimization
import com.esurfing.client.core.Channel
import com.esurfing.client.core.DetectRange
import com.esurfing.client.core.DialerEngine
import com.esurfing.client.core.DialerState
import com.esurfing.client.core.DialerStatus
import com.esurfing.client.core.LogEntry
import com.esurfing.client.core.LogLevel
import com.esurfing.client.core.LogStore
import com.esurfing.client.core.PowerMode
import com.esurfing.client.core.SettingsStore
import com.esurfing.client.core.Timing
import com.esurfing.client.service.DialerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
fun AppRoot() {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MiuixTheme(colors = colors) {
        MainScreen()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("连接", MiuixIcons.Link),
    LOG("日志", MiuixIcons.ListView),
    SETTINGS("设置", MiuixIcons.Settings),
}

@Composable
private fun MainScreen() {
    val status by DialerEngine.status.collectAsState()
    val settings by SettingsStore.settings.collectAsState()
    var tab by remember { mutableStateOf(Tab.HOME) }
    // 每个页面各自一份：否则在长页面把标题滚起来后切到短页面，标题会卡在收起状态展不开
    val scrollBehavior = key(tab) { MiuixScrollBehavior() }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = tab.label,
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = entry.icon,
                        label = entry.label,
                    )
                }
            }
        },
    ) { padding ->
        // 让标题栏跟随内容滚动收起
        val scroll = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        when (tab) {
            Tab.HOME -> HomePage(scroll, padding, status, settings)
            Tab.LOG -> LogPage(scroll, padding)
            Tab.SETTINGS -> SettingsPage(scroll, padding, settings)
        }
    }
}

@Composable
private fun HomePage(
    modifier: Modifier,
    padding: PaddingValues,
    status: DialerStatus,
    settings: AppSettings,
) {
    val context = LocalContext.current
    val running = status.state != DialerState.STOPPED && status.state != DialerState.FAILED
    val visual = status.state.visual()
    val canStart = settings.username.isNotBlank() && settings.password.isNotBlank()

    // 相对时间是普通函数，不会自己变；省电模式下心跳可能几分钟一次，
    // 没有这个计时器，"上次心跳"会长时间停在"0 秒前"骗人。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = System.currentTimeMillis()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item {
            HeroCard(
                icon = visual.icon,
                title = visual.label,
                subtitle = status.message,
                accent = visual.accent,
                footer = {
                    Button(
                        onClick = {
                            if (running) DialerService.stop(context) else DialerService.start(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = running || canStart,
                        colors = if (running) {
                            ButtonDefaults.buttonColors()
                        } else {
                            ButtonDefaults.buttonColorsPrimary()
                        },
                    ) {
                        Text(
                            text = if (running) "断开连接" else "开始认证",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (running) {
                                MiuixTheme.colorScheme.onSecondaryVariant
                            } else {
                                MiuixTheme.colorScheme.onPrimary
                            },
                        )
                    }
                },
            )
        }

        if (!canStart) {
            item {
                Spacer(Modifier.height(10.dp))
                WarningBanner("尚未填写账号密码, 请先到「设置」页配置")
            }
        }

        item { SmallTitle(text = "账号") }
        item {
            InfoCard(
                listOf(
                    Triple("用户名", settings.username.ifBlank { "未设置" }, false),
                    Triple("认证通道", settings.channel.label, false),
                    Triple("User-Agent", settings.channel.userAgent, true),
                    Triple("保活策略", settings.powerMode.label, false),
                    Triple("掉线检测", DetectRange.label(settings.detectIntervalSec), false),
                ),
            )
        }

        item { SmallTitle(text = "会话") }
        item {
            InfoCard(
                buildList {
                    add(Triple("本机 IP", status.clientIp.ifBlank { "—" }, true))
                    add(Triple("AC IP", status.acIp.ifBlank { "—" }, true))
                    // 没取到就不占一行：它对拨号没有任何影响，只是个认网段的参考
                    if (status.schoolSymbol.isNotEmpty()) {
                        add(Triple("校园网标志", status.schoolSymbol, true))
                    }
                    add(Triple("Algo-ID", status.algoId.ifBlank { "—" }, true))
                    add(Triple("Ticket", status.ticket.ifBlank { "—" }, true))
                    add(
                        Triple(
                            "心跳间隔",
                            if (status.keepInterval > 0) {
                                // 两个数都要给：只显示服务端间隔, 会看不懂日志里为什么提前发
                                val actual = Timing.beatIntervalMs(status.keepInterval) / 1000
                                "${status.keepInterval} 秒 (提前到 $actual 秒)"
                            } else {
                                "—"
                            },
                            false,
                        ),
                    )
                    add(Triple("上次心跳", relativeTime(status.lastHeartbeatMs, now), false))
                    add(Triple("认证时间", absoluteTime(status.authTimeMs), false))
                    // 没掉过线就不占地方，掉过才显示——这两行是排查"被踢"的主要依据
                    if (status.reconnects > 0) {
                        add(Triple("自动重连", "${status.reconnects} 次", false))
                        add(Triple("上次掉线", relativeTime(status.lastDropMs, now), false))
                    }
                },
            )
        }
    }
}

@Composable
private fun WarningBanner(text: String) {
    val accent = MiuixTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, fontSize = 13.sp, color = accent)
    }
}

@Composable
private fun LogPage(modifier: Modifier, padding: PaddingValues) {
    val entries by AppLog.entries.collectAsState()
    val shown = remember(entries) { entries.asReversed() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 用系统的"保存文件"而不是 FileProvider 分享：不需要在清单里配 provider，
    // 用户自己挑存哪儿，也不会把日志遗留在共享目录里。
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { LogStore.exportTo(it) } ?: 0L
                }
            }
            bytes.onSuccess { AppLog.info("日志已导出 ($it 字节)") }
                .onFailure { AppLog.error("导出日志失败: ${it.javaClass.simpleName}") }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "共 ${entries.size} 条",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        text = "导出",
                        onClick = { exporter.launch("yilian-${logFileStamp()}.log") },
                        minWidth = 64.dp,
                        minHeight = 34.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        text = "清空",
                        onClick = { AppLog.clear() },
                        minWidth = 64.dp,
                        minHeight = 34.dp,
                    )
                }
            }
        }

        if (shown.isEmpty()) {
            item { EmptyHint("暂无日志") }
        } else {
            // 每条日志各占一个 lazy item，靠首尾圆角拼出"一整张卡"的观感。
            // 之前把 800 条塞进单个 item，进日志页时要一次性组合全部行，会明显卡顿。
            itemsIndexed(shown, key = { _, e -> e.id }) { index, entry ->
                LogRow(
                    entry = entry,
                    isFirst = index == 0,
                    isLast = index == shown.lastIndex,
                    showDivider = index > 0,
                )
            }
        }
    }
}

@Composable
private fun LogRow(
    entry: LogEntry,
    isFirst: Boolean,
    isLast: Boolean,
    showDivider: Boolean,
) {
    val radius = CardRadius
    val shape = RoundedCornerShape(
        topStart = if (isFirst) radius else 0.dp,
        topEnd = if (isFirst) radius else 0.dp,
        bottomStart = if (isLast) radius else 0.dp,
        bottomEnd = if (isLast) radius else 0.dp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceContainer, shape),
    ) {
        if (showDivider) RowDivider()
        Column(modifier = Modifier.padding(horizontal = CardPadding, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.level.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = levelColor(entry.level),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.time,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(text = entry.message, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SettingsPage(modifier: Modifier, padding: PaddingValues, settings: AppSettings) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item { SmallTitle(text = "账号") }
        item {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(CardPadding)) {
                TextField(
                    value = settings.username,
                    onValueChange = { SettingsStore.update(settings.copy(username = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = "用户名",
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                PasswordField(settings)
            }
        }

        item { SmallTitle(text = "认证") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                WindowDropdownPreference(
                    items = Channel.entries.map { it.label },
                    selectedIndex = settings.channel.ordinal,
                    title = "认证通道",
                    summary = settings.channel.userAgent,
                    onSelectedIndexChange = {
                        SettingsStore.update(settings.copy(channel = Channel.entries[it]))
                    },
                )
                RowDivider()
                SwitchPreference(
                    checked = settings.bindWifi,
                    onCheckedChange = { SettingsStore.update(settings.copy(bindWifi = it)) },
                    title = "绑定 Wi-Fi",
                    summary = "认证请求强制走 Wi-Fi, 避免被移动数据抢走",
                )
                RowDivider()
                SwitchPreference(
                    checked = settings.autoStart,
                    onCheckedChange = { SettingsStore.update(settings.copy(autoStart = it)) },
                    title = "开机自启",
                    summary = "开机后自动启动拨号服务",
                )
            }
        }
        item {
            Text(
                modifier = Modifier.padding(horizontal = CardPadding, vertical = 8.dp),
                text = if (settings.channel.dynamicZsm) {
                    "iOS / macOS 通道的密钥由服务端每次会话动态下发, 不使用内置密钥表。" +
                        "主机名会报成 ${settings.channel.hostName}。若认证失败请把日志反馈上来。"
                } else {
                    "通道决定 User-Agent 与上报的系统标识。若认证一直失败, 可换一个通道试试。"
                },
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        item { SmallTitle(text = "电量") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                WindowDropdownPreference(
                    items = PowerMode.entries.map { it.label },
                    selectedIndex = settings.powerMode.ordinal,
                    title = "保活策略",
                    summary = settings.powerMode.summary,
                    onSelectedIndexChange = {
                        SettingsStore.update(settings.copy(powerMode = PowerMode.entries[it]))
                    },
                )
            }
        }
        item {
            Text(
                modifier = Modifier.padding(horizontal = CardPadding, vertical = 8.dp),
                text = "切换保活策略需要重启拨号服务才会生效。若断流频繁, 可改为稳定优先, " +
                    "或在系统电池设置里为本应用取消后台限制。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        item { SmallTitle(text = "掉线重连") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                DetectIntervalPreference(settings)
                RowDivider()
                BatteryExemptionPreference()
            }
        }
        item {
            Text(
                modifier = Modifier.padding(horizontal = CardPadding, vertical = 8.dp),
                text = "账号被限制同时在线设备数时, 别的设备一登录本机就会被静默踢下线, " +
                    "此时心跳可能仍然正常, 只有主动探测外网才能发现。间隔越短掉线恢复越快, " +
                    "耗电也越多。屏幕点亮时会额外探一次, 所以多数掉线在你拿起手机时就已经修好了。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        item { SmallTitle(text = "日志") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                WindowDropdownPreference(
                    items = LogLevel.entries.map { it.label },
                    selectedIndex = settings.logLevel.ordinal,
                    title = "日志等级",
                    summary = "只记录不高于该等级的日志",
                    onSelectedIndexChange = {
                        SettingsStore.update(settings.copy(logLevel = LogLevel.entries[it]))
                    },
                )
                RowDivider()
                // 大小只在进页时算一次，不必跟着每行日志刷
                val logSize by produceState(0L) {
                    value = withContext(Dispatchers.IO) { LogStore.sizeBytes() }
                }
                SwitchPreference(
                    checked = settings.logToFile,
                    onCheckedChange = { SettingsStore.update(settings.copy(logToFile = it)) },
                    title = "记到文件",
                    summary = if (logSize > 0) {
                        "当前已占用 ${logSize / 1024} KB, 可在日志页导出"
                    } else {
                        "写到应用私有目录, 可在日志页导出"
                    },
                )
            }
        }
        item {
            Text(
                modifier = Modifier.padding(horizontal = CardPadding, vertical = 8.dp),
                text = "界面上只留最近 800 条日志, 进程被系统杀掉就没了。" +
                    "记到文件后每万行轮转一次, 最多保留四份; 日志页的清空按钮会连文件一起删。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        item { SmallTitle(text = "关于") }
        item {
            val context = LocalContext.current
            Card(modifier = Modifier.fillMaxWidth()) {
                InfoRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                RowDivider()
                LinkRow("项目地址", ProjectUrl) { openUrl(context, ProjectUrl) }
                RowDivider()
                LinkRow("上游 C 版", UpstreamUrl) { openUrl(context, UpstreamUrl) }
            }
        }
        item {
            Text(
                modifier = Modifier.padding(horizontal = CardPadding, vertical = 8.dp),
                text = "协议流程与会话算法移植自上游 C 版, 以 Apache-2.0 授权。" +
                    "认证失败可以带上日志到项目地址提 issue。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

private const val ProjectUrl = "https://github.com/LazyBonesLZY/Yilian"
private const val UpstreamUrl = "https://github.com/BadGhost520/ESurfingClient-CVersion"

/** 设备上没浏览器时 startActivity 会抛 ActivityNotFoundException, 不能让它把设置页带崩。 */
private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { AppLog.warn("打不开链接 $url: ${it.javaClass.simpleName}") }
}

/**
 * 掉线检测间隔。用滑块而不是几个固定档位：不同校园网被踢的频率差很多，
 * 让用户自己在"恢复快"和"省电"之间挑一个点。
 *
 * 拖动时只改本地状态，松手才写 SharedPreferences——每移动一格都落盘的话，
 * 一次拖动会产生几十次写入，还会让拨号循环反复重算到期时间。
 */
@Composable
private fun DetectIntervalPreference(settings: AppSettings) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableIntStateOf(settings.detectIntervalSec) }
    // 不在拖动时才接受外部变更，否则手指还按着就被 StateFlow 回灌的旧值弹回去
    if (!dragging && local != settings.detectIntervalSec) local = settings.detectIntervalSec

    val throttled = Timing.isProbeThrottled(local, settings.powerMode)
    SliderPreference(
        value = local.toFloat(),
        onValueChange = {
            dragging = true
            local = DetectRange.clamp(it.toInt())
        },
        onValueChangeFinished = {
            dragging = false
            SettingsStore.update(settings.copy(detectIntervalSec = local))
        },
        title = "掉线检测间隔",
        summary = if (throttled) {
            // 省电模式会把间隔拖慢, 不说清楚的话用户会以为设置没生效
            "省电优先下实际按 ${Timing.MIN_PROBE_BATTERY_MS / 1000} 秒执行, 想更快请切到稳定优先"
        } else {
            "每隔这么久探测一次外网, 发现掉线立刻重新认证"
        },
        valueText = DetectRange.label(local),
        valueRange = DetectRange.MIN_SEC.toFloat()..DetectRange.MAX_SEC.toFloat(),
        steps = DetectRange.STEPS,
    )
}

/**
 * 电池优化白名单的状态与入口。
 *
 * 状态要在回到本页面时重新查一次：用户是去系统设置里改的，
 * 改完回来这一行如果还显示旧状态，就等于在骗人。
 */
@Composable
private fun BatteryExemptionPreference() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exempt by remember { mutableStateOf(BatteryOptimization.isExempt(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = BatteryOptimization.isExempt(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BasicPreferenceRow(
        title = "忽略电池优化",
        summary = if (exempt) {
            "已加入白名单, 息屏后闹钟能按时唤醒"
        } else {
            "未加入。息屏后进程会被系统冻住, 心跳可能迟到导致掉线 — 点这里放行"
        },
        highlight = !exempt,
        onClick = { BatteryOptimization.request(context) },
    )
}

@Composable
private fun PasswordField(settings: AppSettings) {
    var visible by remember { mutableStateOf(false) }
    TextField(
        value = settings.password,
        onValueChange = { SettingsStore.update(settings.copy(password = it)) },
        modifier = Modifier.fillMaxWidth(),
        label = "密码",
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(
                text = if (visible) "隐藏" else "显示",
                onClick = { visible = !visible },
                modifier = Modifier.padding(end = 8.dp),
                minWidth = 56.dp,
                minHeight = 32.dp,
            )
        },
    )
}

private class StateVisual(
    val label: String,
    val icon: ImageVector,
    val accent: Color,
)

@Composable
private fun DialerState.visual(): StateVisual = when (this) {
    DialerState.ONLINE -> StateVisual("已连接", MiuixIcons.Ok, ColorOnline)
    DialerState.AUTHENTICATING -> StateVisual("认证中", MiuixIcons.Refresh, ColorBusy)
    DialerState.CHECKING -> StateVisual("检测中", MiuixIcons.Refresh, ColorBusy)
    DialerState.FAILED -> StateVisual("失败", MiuixIcons.Report, MiuixTheme.colorScheme.error)
    DialerState.STOPPED -> StateVisual("未运行", MiuixIcons.Link, ColorIdle)
}

private val ColorOnline = Color(0xFF34A853)
private val ColorBusy = Color(0xFFE8A33D)
private val ColorIdle = Color(0xFF8E8E93)

@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.FATAL, LogLevel.ERROR -> MiuixTheme.colorScheme.error
    LogLevel.WARN -> ColorBusy
    LogLevel.INFO -> MiuixTheme.colorScheme.primary
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

/**
 * 心跳新鲜度。界面显示"已认证"但心跳早已过期时，说明拨号循环被挂起了
 * （历史上就是缺 WakeLock 导致的），把它摆出来才能看出状态是不是陈的。
 */
private fun relativeTime(timeMs: Long, nowMs: Long): String {
    if (timeMs <= 0) return "—"
    val delta = (nowMs - timeMs) / 1000
    return when {
        delta < 0 -> "—"
        delta < 60 -> "$delta 秒前"
        delta < 3600 -> "${delta / 60} 分钟前"
        else -> "${delta / 3600} 小时前"
    }
}

/** 导出文件名用的时间戳，不带冒号——部分文件系统不收。 */
private fun logFileStamp(): String {
    val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
    return fmt.format(java.util.Date())
}

private fun absoluteTime(timeMs: Long): String {
    if (timeMs <= 0) return "—"
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
    return fmt.format(java.util.Date(timeMs))
}
