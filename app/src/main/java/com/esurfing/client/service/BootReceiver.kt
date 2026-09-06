package com.esurfing.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.esurfing.client.core.AppLog
import com.esurfing.client.core.SettingsStore

/** 开机自启：只有开启了自启且填了账密才拉起拨号服务。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        SettingsStore.init(context)
        val settings = SettingsStore.settings.value
        if (!settings.autoStart || settings.username.isBlank() || settings.password.isBlank()) return
        AppLog.info("开机自启, 启动拨号服务")
        // 后台启动前台服务在部分系统/机型上会被拒（抛
        // ForegroundServiceStartNotAllowedException）。开机广播本应豁免，
        // 但真机行为不统一，这里不能让它把接收器崩掉。
        runCatching { DialerService.start(context) }
            .onFailure { AppLog.error("开机自启失败: ${it.javaClass.simpleName}: ${it.message}") }
    }
}
