# 翼连 Yilian

天翼校园网（CCTP 门户）的 Android 认证客户端。后台常驻拨号、掉线自动重连，界面用
[Miuix](https://github.com/compose-miuix-ui/miuix) 写的 Jetpack Compose。

协议流程与全部会话算法移植自
[BadGhost520/ESurfingClient-CVersion](https://github.com/BadGhost520/ESurfingClient-CVersion)，
纯 Kotlin 实现，没有 native 依赖。

> **不包含防共享检测（excheck）**。上游 C 版本身也没有这一块——全仓库搜不到
> `excheck` / `sproof` 相关代码，那套流程在更早的 Rsplwe Kotlin 客户端里。本项目同样不会加。

| | |
|---|---|
| 项目地址 | <https://github.com/LazyBonesLZY/Yilian> |
| 上游 C 版 | <https://github.com/BadGhost520/ESurfingClient-CVersion> |
| 认证通道 | Android / Linux / Windows / iOS / macOS |
| 最低系统 | Android 8.0（API 26） |
| 包名 | `com.esurfing.client` |
| 许可证 | Apache-2.0 |

**下载**：[Releases](https://github.com/LazyBonesLZY/Yilian/releases) 页面的 `app-release.apk`。

## 功能

- 完整 CCTP 认证流程：探测 → 跟随 BAS 重定向取门户配置 → 初始化会话取 Algo-ID → 取 ticket → 登录 → 心跳 → 登出
- **34 个 Algo-ID 全部覆盖**（C 版 24 个 + 上游 PR #37 的 10 个 Windows 系），与 C 版逐字节一致，纯 Kotlin 实现无 native 依赖
- **五种认证通道**：Android、iOS、macOS 各有对应的 User-Agent 与设备标识；iOS / macOS 走服务端动态下发密钥
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

## 认证通道

「设置 → 认证」里选，通道决定 User-Agent、主机名与上报的系统标识。

| 通道 | User-Agent | 密钥来源 |
|---|---|---|
| Android 11 / Android VPN | `CCTP/android11_64/2104`、`CCTP/android64_vpn/2093` | 内置密钥表（按 Algo-ID 查） |
| iOS | `CCTP/iOSdy/4023` | **服务端动态下发** |
| macOS | `CCTP/macdy/5019` | **服务端动态下发** |

iOS / macOS 这两个通道（移植自上游
[PR #39](https://github.com/BadGhost520/ESurfingClient-CVersion/pull/39)）与前面几族的
工作方式完全不同：`ticket.cgi` 首包用**空 body** 请求，服务端返回一个 PacketTunnel / GDCV
动态模块——正文是 TEA 加密 + LZMA 压缩的一段 JS，会话密钥就藏在里面，每次都不一样，
头部那个 UUID 只是模块 ID，查内置密钥表是查不到的。客户端要现场解开：

```
[3 字节头][Pascal 串][Pascal 串 = 模块 UUID]
[5 字节 LZMA props][u32 LE: 高 4 位 type=2 + 低 28 位解压后长度]
[TEA 密文 → LZMA → 明文]
                       ├─ [0xFA] IV 长度 / [0xFC] 密钥长度 / [0xFF] 密钥偏移
                       └─ [0x103…] 一段 JS，里面的 `var codex = 0xNN` 决定用哪个算法
```

`codex` 落在 1..9（oCode）时复用现有的 Android 算法实现，只是密钥/IV 换成模块下发的；
≥ 16 是 nCode，上游也还没移植，遇到会明确报错而不是拿错算法硬跑。

判定不只看用户选了哪个通道，也看包本身的结构——服务端给什么由它决定：选了 Android
却收到动态模块会自动切过去，反之解不开动态模块时会回退查内置表。

> iOS 通道上游作者在校园网实测过（3 个账号可拉 ZSM、取 Ticket、登录、心跳）；
> macOS 是按 GDCV `2.1.5020.2403211` 对齐协议、尚未同样验证。
> 本项目手上没有 iOS 设备可抓包，因此这两个通道的**解包链路**用合成模块做了逐字节回归，
> 但**真机联通性**未验证。认证失败请开 DEBUG 日志反馈。

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

C 版的 **24 个 Algo-ID 已全部覆盖**，另含上游 PR #37 的 10 个 Windows 系 ID，共 **34 个**，服务端下发哪一个都能认。

### 现行 Android 算法集

| Algo-ID | 算法 |
|---|---|
| 07E824B2-… | SNOW3G 变体流密码 |
| 319FC5AB-… / 35101415-… | 三层改版 TEA，ECB / CBC |
| D6544CFE-… / D755A536-… | SM4 变体，CBC / ECB |
| BB2EA626-… / DEABB8C8-… | 双层 AES-128，CBC / ECB |
| 9ABF4D29-… | 双重 3DES-CBC |
| AD8BB5B0-… | 六层 DES-ECB |

### Linux 系算法集

Algo-ID 由 AC 下发，与客户端跑在什么系统上无关，所以 Android 端同样可能收到这一族。

| Algo-ID | 算法 |
|---|---|
| 45433DCF-… / 4BA5496A-… | 双层 AES-128（标准密钥扩展），CBC / ECB |
| 60639D8B-… / AB6C8EBE-… | 三层 XTEA，ECB（大端）/ CBC（小端） |
| 1A7343EC-… | 双重 3DES-CBC |
| B306E770-… | 六层 DES-ECB |

这一族与 Android 那族看着像，细节处处不同：AES 用标准密钥扩展而非改版的、XTEA 的
delta 是正的、AES-CBC 还会把每层的全零 IV 拼进密文（密文因此比明文多 32 字节，
开头固定 32 个 0）。最后这条看着像 bug，但 C 版就是这么发的、服务端也这么收，照抄未改。

### Windows 系算法集

来自上游 [PR #37](https://github.com/BadGhost520/ESurfingClient-CVersion/pull/37)（尚未合并的草稿）。

| Algo-ID | 算法 |
|---|---|
| 03F8A638-… / 079637D7-… / 0A2375CB-… / 11734889-… / CF750526-… / FC05D786-… | 三层 XTEA-CBC |
| 066474E5-… | 双层 AES-128-CBC |
| 083B005A-… / 08BDB042-… | 双层 AES-128-ECB |
| 054DDD03-… | 双重 3DES-CBC |

只有三层 XTEA-CBC 是新算法，其余四个复用 Linux 族实现、只换密钥与 IV。

> **移植时改了两处**：该 PR 把双层 AES-CBC 的 IV 从常量改成参数，但
> (1) 构造函数收下 `iv` 却从没存进 ctx，`s_calloc` 之后它一直是全零；
> (2) 删掉了往密文前缀写 IV 的两行 `memcpy`，而缓冲区是 `malloc` 的，
> 于是前 16 字节变成未初始化堆内存——每次运行都不一样，连原本正常的 45433DCF 也被带坏。
>
> 本项目按改动前的语义实现（前缀 = IV，IV 真正生效），并以此重新生成参考向量。
> IV 全零时与既有 45433DCF 向量逐字节一致，可以确认这就是原本的语义。
> `WindowsCipherVectorTest.aesCbcPrefixIsIvAndDeterministic` 专门盯着这两点。

### 旧版 Android 算法集

C 版里这九个连同密钥被整体注释掉了（标注"已弃用"），但服务端仍可能下发，所以照样认：
`CAFBCBAD` `A474B1C2` `5BFBA864` `6E0B65FF` `B809531F` `F3974434` `ED382482` `B3047D4E` `C32C68F9`。

把 C 版这九个实现跑出来的密文与现行九个逐条比对，结果**完全一致**——密钥材料本就是同一批，
只是数组切分位置不同（例如旧的 `key1` 就是新的 `key[24..47]`），算法取用顺序正好补偿回来。
所以这里直接复用现有实现，没有再写九套；`LegacyCipherVectorTest` 专门守住这个等价前提，
一旦不再成立就会立刻失败。

## 构建

需要 JDK 17，`compileSdk 37`（Miuix 0.9.2 的要求）。

```bash
./gradlew :app:assembleDebug      # 产物在 app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # R8 + 资源压缩，约 1.3 MB
./gradlew :app:testDebugUnitTest
```

### 签名

自行发布需要一份签名。把 `keystore.properties.example` 复制为 `keystore.properties`
（已 gitignore）填好，或改用环境变量 `YILIAN_STORE_FILE` / `YILIAN_STORE_PASSWORD` /
`YILIAN_KEY_ALIAS` / `YILIAN_KEY_PASSWORD`：

```bash
keytool -genkeypair -v -keystore yilian-release.jks -storetype PKCS12 \
  -alias yilian -keyalg RSA -keysize 4096 -validity 10950
```

未配置时 release 产物**不签名**，也不会静默退回 debug 签名——debug key 是公开的，
用它签的包谁都能覆盖安装，且换机安装会因签名不一致失败。

Releases 里的 APK 由固定的发布 key 签名，升级请沿用同一来源；换签名需先卸载重装。

## 测试

```
CipherVectorTest        Android 算法集与 C 版逐字节一致（33 条固定向量）
LinuxCipherVectorTest   Linux 算法集与 C 版逐字节一致（18 条固定向量）
WindowsCipherVectorTest Windows 算法集逐字节一致，并盯住 AES-CBC 的 IV 前缀与确定性（30 条）
DynamicZsmTest          iOS/macOS 动态模块解包：模块 ID / 密钥 / IV / codex / 密文全链路比对
LegacyCipherVectorTest  旧版算法集逐字节一致，并守住"复用现行实现"的等价前提
CctpAlgoIdTest          从 ZSM 交付包的二进制正文里解析 Algo-ID
CctpParsingTest         重定向 URL 解析、门户配置解析、MD5、随机身份
```

`app/src/test/resources/` 下四份 `cipher_vectors*.tsv`（以及 `zsm/` 里的合成模块） 都是用 C 版算法直接跑出来的密文向量。
测试里的 IP / MAC / 会话材料都是合成值，不含任何真实抓包数据。

## 致谢与许可

- 协议实现与算法移植自 [BadGhost520/ESurfingClient-CVersion](https://github.com/BadGhost520/ESurfingClient-CVersion)（Apache-2.0）
- iOS / macOS 通道与动态 ZSM 解包移植自 [MiaM1ku 的 PR #39](https://github.com/BadGhost520/ESurfingClient-CVersion/pull/39)
- Windows 系算法移植自 [Rsplwe 的 PR #37](https://github.com/BadGhost520/ESurfingClient-CVersion/pull/37)
- UI 组件来自 [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)
- LZMA 解码用 [XZ for Java](https://tukaani.org/xz/java.html)（公有领域）

本项目以 Apache-2.0 授权，详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

## 免责声明

仅供学习与技术研究。请使用本人合法持有的校园网账号，
遵守所在学校与运营商的相关规定；因滥用产生的后果与本项目无关。
