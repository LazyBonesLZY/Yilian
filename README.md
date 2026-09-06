# 翼连 Yilian

天翼校园网（CCTP 门户）的 Android 认证客户端。后台常驻拨号、掉线自动重连，界面用
[Miuix](https://github.com/compose-miuix-ui/miuix) 写的 Jetpack Compose。

协议流程与九套会话算法移植自
[ESurfingClient-CVersion](https://github.com/BadGhost520/ESurfingClient-CVersion)，
纯 Kotlin 实现，没有 native 依赖。

> **不包含防共享检测（excheck）**：C 版里的 `excheck-args` / `sproof` / `shared` 相关流程整体未移植。

| | |
|---|---|
| 最低系统 | Android 8.0（API 26） |
| 包名 | `com.esurfing.client` |
| 许可证 | Apache-2.0 |

## 功能

- 完整 CCTP 认证流程：探测 → 跟随 BAS 重定向取门户配置 → 初始化会话取 Algo-ID → 取 ticket → 登录 → 心跳 → 登出
- 九种 Android 算法集的会话加解密，与 C 版逐字节一致（33 条固定向量回归）
- 前台服务常驻，可选开机自启
- **掉线检测与自动重连**：账号被限制多设备时会被静默踢下线，本客户端会主动发现并重新认证
- 两档保活策略，在省电与及时性之间取舍
- 可把请求绑定到 Wi-Fi，避免误走蜂窝数据
- 内存日志页，日志等级可调

## 掉线自动重连

被踢下线是**静默**的：AC 不会通知你，`state.cgi` 心跳往往还在正常应答，只是数据面
流量已经被重新拦截回门户。所以"心跳成功"根本不能证明"还能上网"，必须真的去探一次外网。

客户端有三重判定，任何一重不过就丢弃会话重新认证：

1. **心跳严格判定**——必须能解密、`code` 不是错误码、且带回 `interval` 节点。
   缺少 `interval` 即视为会话失效（与 C 版 `heartbeat()` 一致）。连续 3 次失败即重认证。
2. **连通性探测**——按「掉线检测间隔」周期性请求外网地址，被重定向或收到门户页就说明已被踢。
3. **网络变化事件**——`ConnectivityManager.NetworkCallback` 一有动静立刻加探一次，不等周期到。

重认证前会**重新生成身份**（Client-ID / 主机名 / MAC 全换新）。这一步不能省：
AC 会把失效的会话与那组身份绑定，拿旧身份重认证会被直接拒绝——这正是"必须手动断开
再重连才能上网"的原因。对应 C 版的 `reset()` = `clean()` + `refresh_states()`。

「设置 → 掉线重连」里可以选检测间隔：

| 间隔 | 说明 |
|---|---|
| 20 秒 | 恢复最快，仅建议配合「稳定优先」使用 |
| 45 秒（默认） | 一般够用 |
| 1.5 分钟 / 3 分钟 | 更省电，代价是掉线后要等更久才恢复 |

省电优先模式下每次探测都要把设备从 Doze 唤醒，因此最快按 45 秒执行；想要 20 秒请切到稳定优先。
首页在发生过掉线后会显示自动重连次数与上次掉线时间。

## 保活与耗电

心跳必须按服务端给的间隔准时发送，而息屏后系统会进入 Doze 挂起 CPU，两者天然冲突。
两档策略（设置 → 电量）：

| 策略 | 机制 | 代价 |
|---|---|---|
| **省电优先**（默认） | 等待期间释放 WakeLock，靠 `AlarmManager` 唤醒；只在收发时短暂持锁 | 闹钟为非精确类型，Doze 下心跳可能迟到几分钟 |
| 稳定优先 | 全程持 `PARTIAL_WAKE_LOCK`，心跳绝不迟到 | 持续耗电 |

省电模式下心跳迟到不会导致不可恢复的掉线——连续失败会自动重认证。
这里选择的是"容忍偶尔迟到 + 自动自愈"，而不是和系统的省电策略硬碰。

其它省电措施：

- 已认证后不再每秒探测。心跳按服务端下发的间隔调度，连通性探测尽量搭在心跳的唤醒上。
  早期版本每秒探测一次，约 8.6 万请求/天。
- 调度用 `SystemClock.elapsedRealtime()` 而非墙上时钟，NTP 校正或用户改时间不会导致睡过头。
- 闹钟用 `ELAPSED_REALTIME_WAKEUP` + `setAndAllowWhileIdle`，不需要
  `SCHEDULE_EXACT_ALARM`（该权限自 Android 14 起对新装应用默认不授予）。
- 前台服务类型是 `specialUse` 而不是 `dataSync`：后者在 targetSdk ≥ 35 时每 24 小时
  只允许累计运行 6 小时，超时会被系统终止。

> 部分国产 ROM（小米/华为/OPPO 等）会无视前台服务与 WakeLock 强杀后台进程。
> 如仍然断流，需要在系统电池设置里为本应用取消后台限制，这是应用层无法绕过的。

## 明文 HTTP

CCTP 全程是明文 HTTP（探测地址、BAS 重定向、`index.cgi`/`ticket.cgi`/`auth.cgi`
都是 `http://<IP>:7001` 这类地址），而 Android 9 起默认禁止明文流量。
因此 manifest 里声明了 `usesCleartextTraffic` 并配了 `network_security_config`。
AC 的 IP 由现场下发、无法预先枚举，所以没法收窄成域名白名单——代价是这个 App
对任何地址都允许明文。这是协议本身决定的，不是可以"关掉"的选项。

## 使用

1. 在「设置」页填账号密码、选认证通道（对应不同的 User-Agent 与算法集）
2. 回到「连接」页启动服务
3. 若想开机自动拨号，在「设置」里打开自启

需要在校园网内使用本人有效账号：ticket 由 AC 现场下发并绑定 `wlanuserip` / `wlanacip`，
离开校园网无法认证。

## 结构

| 包 | 内容 |
|---|---|
| `core.cipher` | 会话加解密。`CipherFactory` 按 Algo-ID 选实现，`KeyData` 是硬编码密钥 |
| `net` | `HttpEngine`（请求头、Network 绑定、不自动跟随重定向）与 `Cctp`（XML 组包/解析、MD5、随机身份） |
| `core` | `DialerEngine` 状态机、`SettingsStore` 配置、`AppLog` 日志 |
| `service` | `DialerService` 前台服务、`Sleeper` 等待策略、`BootReceiver` 开机自启 |
| `ui` | `MainActivity` 与 Miuix 界面（连接 / 日志 / 设置三页） |

## 支持的 Algo-ID

| Algo-ID | 算法 |
|---|---|
| 07E824B2-… | SNOW3G 变体流密码 |
| 319FC5AB-… / 35101415-… | 三层改版 TEA，ECB / CBC |
| D6544CFE-… / D755A536-… | SM4 变体，CBC / ECB |
| BB2EA626-… / DEABB8C8-… | 双层 AES-128，CBC / ECB |
| 9ABF4D29-… | 双重 3DES-CBC |
| AD8BB5B0-… | 六层 DES-ECB |

C 版还支持 6 个 Linux 系算法（`1A7343EC`、`45433DCF`、`4BA5496A`、`60639D8B`、
`AB6C8EBE`、`B306E770`），本移植未覆盖。若服务端下发这些 ID，界面会提示
"服务器下发了尚未支持的算法"并停止，此时把日志里的 Algo-ID 反馈上来即可补齐。

## 构建

需要 JDK 17，`compileSdk 37`（Miuix 0.9.2 的要求）。

```bash
./gradlew :app:assembleDebug      # 产物在 app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # R8 + 资源压缩，约 1.3 MB
./gradlew :app:testDebugUnitTest
```

Release 目前用 debug 签名，自行发布请换成自己的签名配置。

## 测试

```
CipherVectorTest   加解密与 C 版逐字节一致（33 条固定向量）
CctpAlgoIdTest     从 ZSM 交付包的二进制正文里解析 Algo-ID
CctpParsingTest    重定向 URL 解析、门户配置解析、MD5、随机身份
```

`app/src/test/resources/cipher_vectors.tsv` 是用 C 版算法直接跑出来的密文向量。
测试里的 IP / MAC / 会话材料都是合成值，不含任何真实抓包数据。

## 致谢与许可

- 协议实现与算法移植自 [BadGhost520/ESurfingClient-CVersion](https://github.com/BadGhost520/ESurfingClient-CVersion)（Apache-2.0）
- UI 组件来自 [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)

本项目以 Apache-2.0 授权，详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

## 免责声明

仅供学习与技术研究。请使用本人合法持有的校园网账号，
遵守所在学校与运营商的相关规定；因滥用产生的后果与本项目无关。
