package com.esurfing.client.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化白名单。
 *
 * 这是本应用能做的最有效的防掉线措施，而且**不在应用自己手里**：
 * 只要还受电池优化管，息屏后系统就会把进程冻进 Doze，非精确闹钟迟到几分钟很正常，
 * 心跳因此错过服务端的截止时间，会话被 AC 回收。心跳提前量能扛住几十秒的迟到，
 * 扛不住"整个进程被冻住半小时"。加进白名单后闹钟才会按时响。
 *
 * 查询状态不需要任何权限；跳那个一键授权的弹窗需要清单里声明
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS。个别 ROM 把该弹窗拦掉了，
 * 所以 [request] 会退回到系统的电池优化列表页，让用户自己找到本应用。
 */
object BatteryOptimization {

    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * 拉起系统界面让用户把本应用加进白名单。
     *
     * lint 的 BatteryLife 检查针对的是 Play 商店政策：那里只允许极少数用途用这个
     * 意图。本应用不上架 Play，用途也正是政策列举的合法情形之一——用户主动开启的
     * 常驻网络保活；而且只是"打开系统弹窗"，同意与否完全在用户。
     *
     * @return 是否成功拉起了某个系统界面。
     */
    @SuppressLint("BatteryLife")
    fun request(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", context.packageName, null))
        if (start(context, direct)) return true

        AppLog.warn("一键加入白名单的弹窗打不开, 改为跳转电池优化列表")
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (start(context, list)) return true

        AppLog.error("无法打开电池优化设置, 请到系统设置里手动放行")
        return false
    }

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
}
