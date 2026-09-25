package com.esurfing.client.core

import android.content.Context
import android.util.Base64
import com.esurfing.client.core.cipher.CipherFactory
import com.esurfing.client.core.cipher.DynamicZsm
import com.esurfing.client.core.cipher.SessionCipher
import com.esurfing.client.net.HttpEngine
import com.esurfing.client.net.Identity
import com.esurfing.client.net.NetStatus
import org.json.JSONObject
import java.io.File

/**
 * 登录现场存档，对应 C 版 LogoutState。
 *
 * 进程被系统强杀时跑不到登出，AC 那边的会话还挂着，占着同时在线设备数。
 * 登录成功后把补登出需要的材料写下来；下次启动先读回来发一次 term，
 * 无论成败都删掉，免得每次启动都白发一次注定失败的请求。
 *
 * 客户端 IP 可能已经变了（DHCP 重新分配），那种登出会失败，
 * 交给服务器超时把会话踢掉。
 */
internal object SessionArchive {

    private const val FILE_NAME = "session.logout"

    data class Snapshot(
        val dynamic: Boolean,
        val codex: Int,
        val userAgent: String,
        val termUrl: String,
        val algoId: String,
        val clientId: String,
        val hostName: String,
        val clientIp: String,
        val macAddress: String,
        val osTag: String,
        val ticket: String,
        val key: ByteArray?,
        val iv: ByteArray?,
    )

    fun save(context: Context, snapshot: Snapshot) {
        val json = JSONObject()
            .put("dynamic", snapshot.dynamic)
            .put("codex", snapshot.codex)
            .put("userAgent", snapshot.userAgent)
            .put("termUrl", snapshot.termUrl)
            .put("algoId", snapshot.algoId)
            .put("clientId", snapshot.clientId)
            .put("hostName", snapshot.hostName)
            .put("clientIp", snapshot.clientIp)
            .put("macAddress", snapshot.macAddress)
            .put("osTag", snapshot.osTag)
            .put("ticket", snapshot.ticket)
        if (snapshot.key != null) json.put("key", b64(snapshot.key))
        if (snapshot.iv != null) json.put("iv", b64(snapshot.iv))
        runCatching {
            file(context).writeText(json.toString())
            AppLog.debug("会话现场已存档, 被强杀时下次启动会补登出")
        }.onFailure {
            AppLog.warn("写会话存档失败, 本次退出若被强杀将无法补登出: " + it.message)
        }
    }

    fun load(context: Context): Snapshot? {
        val target = file(context)
        if (!target.exists()) return null
        val root = runCatching { JSONObject(target.readText()) }.getOrElse {
            AppLog.warn("会话存档解析失败, 按没有存档处理")
            return null
        }
        val termUrl = root.optString("termUrl")
        val algoId = root.optString("algoId")
        if (termUrl.isEmpty() || algoId.isEmpty()) {
            AppLog.warn("会话存档缺少 termUrl 或 algoId, 无法补登出")
            return null
        }
        return Snapshot(
            dynamic = root.optBoolean("dynamic"),
            codex = root.optInt("codex"),
            userAgent = root.optString("userAgent"),
            termUrl = termUrl,
            algoId = algoId,
            clientId = root.optString("clientId"),
            hostName = root.optString("hostName"),
            clientIp = root.optString("clientIp"),
            macAddress = root.optString("macAddress"),
            osTag = root.optString("osTag"),
            ticket = root.optString("ticket"),
            key = unb64(root.optString("key")),
            iv = unb64(root.optString("iv")),
        )
    }

    fun clear(context: Context) {
        val target = file(context)
        if (target.exists() && target.delete()) {
            AppLog.debug("会话存档已清除")
        }
    }

    /**
     * 读档、重建加解密、补发一次登出，然后清档。
     * 没有存档时什么都不做。
     */
    fun resumeLogout(context: Context, http: HttpEngine) {
        val snapshot = load(context) ?: return
        AppLog.warn("检测到上次没登出的会话, 尝试补登出")
        val cipher = restore(snapshot)
        if (cipher == null) {
            AppLog.warn("恢复加解密失败, 无法补登出")
            clear(context)
            return
        }
        val identity = Identity(
            clientId = snapshot.clientId,
            hostName = snapshot.hostName,
            macAddress = snapshot.macAddress,
            osTag = snapshot.osTag.ifEmpty { snapshot.hostName },
        )
        val payload = cipher.encrypt(
            com.esurfing.client.net.Cctp.keepAliveXml(
                snapshot.userAgent,
                identity,
                snapshot.clientIp,
                snapshot.ticket,
            ),
        )
        if (payload == null) {
            AppLog.error("补登出加密失败")
            clear(context)
            return
        }
        // 补登出不该带上一次会话残留的门户头
        http.network = null
        val resp = http.post(snapshot.termUrl, payload, snapshot.userAgent, snapshot.clientId, snapshot.algoId)
        if (resp.status == NetStatus.HAVE_RES || resp.status == NetStatus.SUCCESS) {
            AppLog.info("补登出完成, 上次会话已从服务端释放")
        } else {
            AppLog.warn("补登出失败 (客户端 IP 可能已经变了), 交给服务器超时下线")
        }
        clear(context)
    }

    private fun restore(snapshot: Snapshot): SessionCipher? = if (snapshot.dynamic) {
        val key = snapshot.key
        if (key == null) {
            AppLog.warn("动态会话存档缺少密钥")
            null
        } else {
            DynamicZsm.resumeCipher(snapshot.codex, key, snapshot.iv)
        }
    } else {
        CipherFactory.create(snapshot.algoId)
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(text: String): ByteArray? =
        text.takeIf { it.isNotEmpty() }?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        }
}
