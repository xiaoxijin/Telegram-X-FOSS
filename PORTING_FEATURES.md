# TG PRO 特性移植规格书(feature/telegram-customizations → kuikly-ui)

> **目的**:把 `D:\code\Telegram`(分支 `feature/telegram-customizations`,基线官方 Telegram Android 13.0.3)上积累的全部定制特性,移植到本仓库(Telegram-X-FOSS,TDLib + Kotlin,UI 层走 KuiklyUI)。
> **范围**:43 个定制提交,106 个文件,+24499/-59 行;功能特性见下文 A–F 组,工程工具(G 组)不随功能移植。
> **源仓库说明**:文中 `源:` 路径均相对 `D:\code\Telegram`;所有触碰上游的改动都带 `CUSTOM_HOOK(name)` 注释并有 `ForkFeatures` 门禁,建议移植时保持同样的约定。

---

## 0. 源实现架构总览

| 组件 | 形态 | 说明 |
|---|---|---|
| polynet | gomobile 绑定的 Go 库(`libgojni.so` + `polynet.jar`) | 进程内代理核心,暴露本地 SOCKS5 |
| polyNode 集成 | Java 层 `PolyNodeConfig` / `PolyNodeTransport` / `WebViewProxy` | 节点配置、传输、WebView 代理 |
| 通话代理 | vendored WebRTC 内 2 行 hook + `voip/fork/call_proxy_socket_server.cc` | 通话 UDP 过节点,无 VPN |
| UnifiedPush | `UnifiedPushProvider` / `UnifiedPushService`(connector 3.3.5) | endpoint 注册为 TDLib SimplePush |
| 权限引导 | `SystemPermissionsActivity` + `ForkApplicationLoader` 启动检查 | HyperOS/MIUI/OriginOS 适配 |
| RemoteGreeting | `RemoteGreetingController/Server/Command` + NanoHTTPD | 本地 HTTP 指令接口 |
| 门禁 | `ForkFeatures.java` | PASSPORT=false / HIDE_PREMIUM=true / IN_APP_BROWSER_ONLY=true / LOOP_STICKERS=false |
| 发行配置 | `local.properties` 的 `custom.*` 键 | 见 §4 配置总表 |

---

## A. 内置代理内核 polynet(优先级最高,其余特性的地基)

### A1. 进程内 Go 代理核心

**行为**
- Go 编译的 gomobile 绑定库加载进 app 进程,向 `127.0.0.1` 暴露一个 SOCKS5 端口;API 收敛为 `Start(json 配置)/Stop(handle)`,内部封装 `core.StartInstance`。
- 演进史:最初是 exec 外部 `libxray.so` 子进程(feb62f467)→ 因 Android 12+ phantom-process 限制会静默杀子进程且从不重启,改为进程内库(a8dcd6af8);同时抹掉 xray/vless 标识(e6486991b)。
- 构建链:`jni/prebuild/build_polynet.sh` → `gomobile bind`(arm64-v8a、16KB pages、`-checklinkname=0`)→ `libs/polynet.jar` + `jniLibs/arm64-v8a/libgojni.so`;proguard 走 consumer rules(`proguard-fork.pro`)。
- Go 侧测试覆盖:单进程内 HTTP → SOCKS5 → VLESS client → VLESS server 全链路(`polynet_test.go`)。

**源**:`TMessagesProj/jni/polynet/`(polynet.go、udprelay.go、polynet_test.go、go.mod/sum)、`jni/prebuild/build_polynet.sh`、`Tools/POLYNET.md`

**移植要点(TGX 侧)**
- TGX 的代理走 TDLib `addProxy`(SOCKS5/MTProto/HTTP),没有自定义代理内核。polynet 以本地 SOCKS5 形态接入:核心起来后在 TDLib 注册 `127.0.0.1:port` 的 SOCKS5 代理,复用 TDLib 的连接管理。
- TGX 的 NDK/构建体系与本仓库不同,KuiklyUI 分支需要新增一条 Go 侧构建链(gomobile 或纯 .so + JNI)。

**验收**:核心起停无崩溃;经本地 SOCKS5 的 HTTP 请求可达外网;Go 单测全绿。

### A2. 强制默认节点 + 隐藏代理状态

**行为**
- 用户未启用自有代理时,自动走 `custom.node_link` 指定的内置节点;用户自配代理则完全让位。
- UI 隐藏"强制代理连接中"状态显示,不让用户感知到底层强制路由(2 行改动:`ConnectionsManager.java`、`LoginActivity.java`)。
- 历史遗留:早期的强制 SOCKS5(`custom.proxy_*`)已删除,由内置节点统一覆盖(56173daf0)。

**源**:`PolyNodeConfig.java`(org/telegram/utils/proxy/)、commit 419ec5c63 / f2420b0db

**移植要点**:TDLib 侧等价物是"无用户代理时 `addProxy` + `enableProxy`";TGX 的代理设置 UI 需要一个"内置节点"隐藏条目。

### A3. WebView / Mini App / 应用内浏览器流量过节点

**行为**(fd3b6e5de)
- WebView 全部流量走 polynet 的本地 SOCKS5。
- **固定本地中继端口**:WebView 的 proxy 设置跨核心重启/节点切换保持稳定;核心启动期间新连接挂起等待,而不是打到未监听端口(页面不会报错)。
- WebView 引擎延迟到主线程 idle 才加载,冷启动零回归(实测 1250→1246ms,PSS 256.6→257.2MB)。

**源**:`TMessagesProj/src/main/java/org/telegram/messenger/WebViewProxy.java` + `WebViewProxyTest`

**移植要点**:TGX 有自己的应用内浏览器(WebView 封装),找到其 WebView 初始化点注入同样的代理设置逻辑即可;"固定端口 + 启动挂起"两个细节直接照搬。

### A4. `IN_APP_BROWSER_ONLY` 门禁(浏览不出域)

**行为**(ForkFeatures.java:21)
- 所有 web 链接强制留在应用内浏览器:
  - `Browser.java:300`:强制 in-app scheme 走内嵌;
  - `:309`:禁用"用外部浏览器打开"的 `allowCustom`;
  - `:509`:web 链接一律不外发;
  - `:557`:阻止其他 app 接手链接——**理由是外部浏览器会绕过内置代理**。
- 这条是 A1/A3 的一致性闭环:没有它,代理会静默漏流量。

**源**:`TMessagesProj/src/main/java/org/telegram/messenger/browser/Browser.java`(搜 `IN_APP_BROWSER_ONLY` / `CUSTOM_HOOK(in-app-browser)`)

**验收**:任何链接(含 `http(s)`、自定义 scheme)都不会离开应用内浏览器。

### A5. 通话媒体过节点,无 VPN(技术难度最高)

**行为**(2de61c392)
- 问题背景:大陆封锁 Telegram 通话 relay;上游 `VoIPService` 传 null 代理,tgcalls 引擎忽略代理,WebRTC 早已丢掉 SOCKS5 支持。
- 实现:所有 tgcalls 引擎和群通话的 socket 都来自 `rtc::CreateDefaultSocketServer()`,对它包一层——**vendored WebRTC 里 2 行 hook + `voip/CMakeLists.txt` 1 行,tgcalls 子模块零改动**:
  - 公网 IPv4 UDP:加 RFC 1928 头 → polynet 新增的 UDP relay(`127.0.0.1`),回包还原真实源地址;
  - relay 运行期间公网 IPv6 UDP 与公网 TCP 直接失败(**fail-closed,不泄漏**;所有 relay 节点是 IPv4);
  - 私有/LAN 地址直连;
  - relay 端口 = 0 时完全等价上游行为;
  - Android network binder 同步给内部 server(通话 socket 需按网络绑定)。
- polynet 侧新增 `StartUDPRelay`(Xray 的 SOCKS5 UDP 需要 TCP 持有)。

**源**:`jni/polynet/udprelay.go`、`jni/voip/fork/call_proxy_socket_server.cc`、vendored WebRTC 的 socket server 包装

**移植要点**:TGX 同样用 tgcalls/WebRTC(`:tgcalls` 模块,仅 sinceLollipop flavor),`rtc::CreateDefaultSocketServer` 包装思路可直接复用,但要在 TGX 的 voip 构建里重新接一遍;UDP relay 的 Go 代码可整体拷贝。

**验收**:被墙环境下经节点可拨打 1:1 语音,接通率与直连环境对照;IPv6/TCP 路径在 relay 开启时不产生外发流量(抓包验证)。

### A6. 代理列表 ping 复用主实例

同链接的 ping 检查不另起核心实例,复用运行中的主实例(a8dcd6af8)。小项,随 A1 顺带。

---

## B. 中文发行形态

### B1. 独立包名 + arm64-only + 品牌

**行为**(88ed29bf8、07508a3c9、56173daf0)
- 独立 applicationId(`org.telegram.messenger.zh`),与官方 app 并存;launcher 名 "TG PRO";仅出 arm64-v8a。
- 行为对齐 Telegram 官方 standalone APK:无 Google Play 内容限制、发票代替 Play 内购、冷启动完全不连 Play Billing、无 Play Store 链接;应用内更新检查报告"无更新"而非提供官方 APK。

**移植要点**:TGX-FOSS 本身就是独立包(`org.thunderdog.challegram`),此项大体天然满足;需确认其更新检查行为。

### B2. 内置 zh_hans 语言包,首启即中文

**行为**(55bea4623)
- 默认语言是远程语言包,首屏原本英文直到下载完成。方案:assets 内置从 translations.telegram.org 导出的完整语言包(`remote_zh_hans.xml`,15439 行),在远程列表加载**之前**注册;之后服务器照常推送更新。

**移植要点**:TGX 用自己的 strings 体系(Localizer + 独立翻译仓库),不是 Telegram 的远程语言包机制。等效做法:确保 zh_hans strings 完整打包 + 首启默认语言为 zh_hans。若 KuiklyUI 页面有自己的文案表,同步内置中文。

### B3. 默认语言 zh_hans 直到用户自选

feb62f467 的一部分,与 B2 绑定。

### B4. 中文系统权限字符串修复

`values-zh` 系统权限字符串补齐(afc3d41ea),`values-zh` 目录纳入跟踪(37f989d1c)。TGX 侧:检查系统权限(通知、悬浮窗等)的中文文案完整性。

---

## C. 产品裁剪(ForkFeatures 门禁)

每个门禁是编译期常量,upstream 触点带 `CUSTOM_HOOK` 注释:

| 门禁 | 值 | 内容 |
|---|---|---|
| `PASSPORT` | false | Telegram Passport 深链、外部请求、隐私设置行全部关闭;**MRZ OCR 不参与编译**(`secureid_ocr.cpp`、`genann.c`、`fast-edge.cpp`),模型 `secureid_ocr_nn.dat` 不打包 |
| `HIDE_PREMIUM` | true | 对非 Premium 用户隐藏 Premium/Business 推广,走与服务器 `premiumLocked` 相同的路径;**gifts(礼物)保留**;新增自有 "TG PRO membership" 设置行(id 10002 → `custom.pro_link`,链接为空时隐藏) |
| `IN_APP_BROWSER_ONLY` | true | 见 A4 |
| `LOOP_STICKERS` | false | "循环播放动态贴纸"默认关:只清 `LiteMode.FLAG_ANIMATED_STICKERS_CHAT` 的**设备档预设默认值**(高端机默认开),用户已在贴纸/省电设置里保存的值不覆盖(53694c9f1) |

**C 组附加项**(`src/fork` manifest overlay + `ForkApplicationLoader.isStandalone()`,56173daf0):
- 裁掉:Wear OS 登录、Android Auto、Google Pay、Google Assistant 语音入口、Play Billing 代理 activity;
- `ApplicationLoader` 除 RemoteGreeting 外回到上游形态;WebView 不再在 `Application.onCreate` 加载;
- merged manifest 已验证无 Google 组件残留。

**移植要点**:
- TGX-FOSS 已完成大部分去 Google 化(blob 全源码编译、FCM 移除),此项重点核对 Play Billing / Auto / Assistant 是否残留;
- Passport:TGX 从未实现 Passport UI,预计 N/A,验证即可;
- Premium 推广:TGX 有自己的 premium 提示体系,需对应的"隐藏推广 + 自有会员行"改造;会员行是 KuiklyUI 页面的好切入点。

---

## D. 推送:UnifiedPush(ntfy),FCM 全关

### D1. UnifiedPush 通道

**行为**(dc610ea65)
- app 通过 UnifiedPush connector(3.3.5;JVM 版 Tink 换成 `tink-android` 1.23.0)拿到 distributor(ntfy)的 endpoint,注册给 Telegram 服务器为 **TDLib SimplePush(token_type 4,`PUSH_TYPE_SIMPLE`)**。
- **每个 push 只是一个空 ping,只负责唤醒网络**;真正的更新随后全部通过 MTProto(经 A1 节点)拉取——推送服务器读不到任何消息内容。
- simple push 一律按非静默处理(`no_muted`):每次 push 都唤醒整个 app。
- 移除:`GcmPushListenerService`、`FirebaseInitProvider`,gradle exclude `firebase-messaging`(经 `src/fork` manifest overlay)。

**移植要点(TGX 侧,成本最低收益最大)**
- **TGX 代码原生支持 SimplePush**:`TdlibNotificationUtils.getDeviceTokenType` 已处理 `DeviceTokenSimplePush`(app/src/main/java/org/thunderdog/challegram/telegram/TdlibNotificationUtils.java:294)。
- 当前 FOSS fork 的推送是 `extension/none` → `NoPushDeviceTokenRetriever`("Push notifications are disabled")。正确做法:**新增一个 `extension/unifiedpush` 变体**(Gradle `extra["extension"]` 选择),实现 `DeviceTokenRetriever` + UnifiedPush connector,替换 `none`。这比源仓库的 fork manifest overlay 干净得多。
- 依赖换用 `tink-android` 的坑直接照抄。

**验收**:注册 SimplePush 后,杀掉 app,从另一账号发消息,ntfy ping → app 拉起 → MTProto 拉内容 → 通知出现。

### D2. distributor 引导提示

**行为**(313d02034)
- LaunchActivity 每次 resume 检查,**登录前后都提示**,直到 distributor 在本进程应答为止:
  - 无 distributor:约 1s 后弹"安装 ntfy"→ `custom.ntfy_link`(频道帖)或 ntfy releases 页;登录前 t.me 链接无法应用内打开,自动回退 releases 页;
  - 已装但 5s 内无 endpoint(从未打开/被强停):弹"打开 ntfy";返回后无需重启即重新注册;
  - 每个提示每进程至多一次;"不再提醒"可关。
- 对话框宿主在 fork 自有的 `SystemPermissionsActivity`(App 模块看不到 LaunchActivity 的 androidx 父类,源仓库的模块结构限制;TGX 侧无此限制)。

### D3. ntfy 唤醒实测基线(vivo Y36 / OriginOS 3)

- 被杀进程唤醒 ~2.2s,后台 ~1.6s——**前提是两个 app(ntfy + TG PRO)都拿到:自启动、关联启动、后台高耗电**三项。
- 冷启动 1057→1058ms,内存 246.6+32.4 → 235.3MB,无回归。
- 移植后用同口径复测。

---

## E. 国产 ROM 权限与保活(HyperOS/MIUI/OriginOS)

> TGX 侧完全空白,需从零建;页面 UI 用 KuiklyUI 实现,正好当框架验收场。

### E1. 系统权限页(主设置入口)

**行为**(5417628d4 及 HyperOS 集群:27bf780ba、5de48b908、83ae19494、a6cf2f03d、6d41d5903、c5987a423、e1f97138e、4f863f3a0、8103990eb)
- 主设置直达的权限页,每行**实时状态**:
  - 通知;小米自启动;电池不限(**所有机型**,不限 MIUI);锁屏显示;后台弹窗(**仅小米**,跳 MIUI 权限编辑器);悬浮窗(系统 overlay 开关);Android 14+ 全屏意图;push distributor 状态行;
  - 后台保活开关有独立的"后台设置面板"(8103990eb);
  - keep-alive 开关写入**读取方实际使用的 prefs**(account 0)——历史上开关与读取键不一致导致失效的坑。
- HyperOS 电池权限状态需主动刷新(缓存会过期)。

**源**:`TMessagesProj/src/main/java/org/telegram/ui/SystemPermissionsActivity.java`(415+ 行)

**移植要点**:MIUI/HyperOS 的权限页路由是私有 API(`miui.securitycenter` 等),源仓库已调通的路由表值得整体照搬;TGX 的设置页体系与 KuiklyUI 混合实现。

### E2. 分步权限引导(一次一步)

**行为**(a39c014c9)
- 启动提示不再跳裸系统页(几十个开关),而是进权限页;顶部卡片**一次只显示一步**:
  1. 指示"到某个页面改某一项" → "Open" 跳转;
  2. 返回自动复检;未过 → "再试一次";全过 → 下一项;完毕 → "全部完成"。
- MIUI 的 app 权限页一页含锁屏+弹窗+悬浮窗三项,合并为一步。
- 检查项全集:通知、小米自启动、电池不限、锁屏显示、后台弹窗、悬浮窗、Android 14+ 全屏意图。

### E3. 启动提示与"HyperOS 每次更新重新锁权限"告知

- 启动时(每次启动一次,登录前后都查)若缺任一项:列表展示 + "去允许" → 权限页,或"不再提醒";
- 与 ntfy 提示共用同一个调度 helper(每进程一次、可关闭);
- 提示文案明确告知:**HyperOS 每次更新都会重新关掉"锁屏显示"**(连续安装实测确认)。

### E4. 来电可靠性修复集群

**问题**(6f0359b42):MIUI/HyperOS 对侧载 app 拒绝"锁屏显示"和"后台弹窗"(官方 Telegram 在小米白名单);悬浮窗默认全关。后果:来电只响铃无接听界面;锁屏状态下 MIUI 直接丢来电、不确认。
**修复集**:
- 权限页补齐上述三项(带实时状态);
- `ForkApplicationLoader.onResume` 每启动一次的全量检查;
- 来电操作按钮常显(5de48b908)、HyperOS 来电通知处理(83ae19494)、后台通话权限优先级(27bf780ba)、持久化通话权限设置(4f863f3a0);
- 上游自带的一次性 MIUI 锁屏提问标记为已问,避免重复弹。

### E5. 权限字符串

系统权限相关字符串中英文双份维护(`values` + `values-zh`),权限页文案可配置。

---

## F. RemoteGreeting(远程问候,独立功能,按需移植)

**行为**(36c5a386e,`Tools/REMOTE_GREETING.md`)
- 复用 NanoHTTPD,只监听手机 `127.0.0.1`,token 鉴权(32–128 位,字母/数字/下划线/减号)。
- 配置:`files/remote-greeting.json` `{"token": "...", "port": 8765}`,**默认关闭**——文件存在且有效才在客户端初始化后开启。
- 流程:解析指定用户名 → 校验是"陌生用户"(无既有聊天记录)→ 发送指定文本 → 等待 Telegram 服务端确认送达 → **删除自己一侧的问候历史并移出聊天列表**。对方保留收到的问候;不支持双向删除。
- 接入方式:ADB 转发或自有安全隧道;不是公网推送服务,不负责唤醒被强停的 app。
- 协议与 `D:\code\rust\recon` 组件互通(同一 HTTP 指令及回执协议;`bun run remote-greeting --mock` 可先调调用方)。`RemoteGreetingCommandTest` 覆盖指令解析。

**源**:`TMessagesProj/src/main/java/org/telegram/messenger/RemoteGreeting{Controller,Server,Command}.java`、`Tools/REMOTE_GREETING.md`

**移植要点**:TGX 侧等价实现需要:TDLib 消息发送 + 服务端送达确认 + 本地历史删除;HTTP 服务可沿用 NanoHTTPD 或 Ktor。注意 TGX 删除消息用 `deleteMessages` + chat list 操作的 TDLib 对应 API。

---

## G. 不随功能移植(源仓库工程资产)

| 资产 | 处理 |
|---|---|
| `Tools/tgsync_lib/`(~2800 行)+ `TGSYNC.md` + audit/gates 产物 | 源仓库的可审计上游同步流程;TGX 用自己的上游同步机制,不移植 |
| `Tools/perf.ps1` + PERF.md + 各真机对比数据 | 源仓库的测量流程;移植后建议重建同口径脚本(distributor PSS、RSS fallback、ART compiler filter 重置这些坑值得参考) |
| profileable release 构建(534d4922d) | TGX 侧值得同样开启,一条 gradle 配置 |
| `.serena/`、release 渠道文档(cb17fb86c:发行渠道限定 standalone 与 Huawei) | 参考性 |

---

## §4. 配置项总表(`local.properties`)

| 键 | 用途 | 空值行为 |
|---|---|---|
| `custom.node_link` | 强制默认节点的链接(A2) | 不强制任何节点 |
| `custom.ntfy_link` | "安装 ntfy" 提示的目标页(频道帖;登录前 t.me 自动回退 releases 页)(D2) | 回退 ntfy 官方 releases |
| `custom.pro_link` | "TG PRO membership" 设置行目标链接(C 组) | 整行隐藏 |
| ~~`custom.vless_link`~~ | 已废弃 → 更名为 node_link | — |
| ~~`custom.proxy_*`~~ | 已删除(强制 SOCKS5,被内置节点取代) | — |

---

## 移植顺序与依赖

```
A1 polynet 核心 ──┬──> A2 默认节点 ──> A3 浏览器过节点 ──> A4 IN_APP_BROWSER_ONLY(闭环)
                  ├──> A6 ping 复用
                  └──> A5 通话 UDP relay(独立 Go 模块,可并行)
D1 UnifiedPush(独立于 A,但内容拉取依赖 A1/A2 才有意义)
B2/B3 本地化 ──> C2 会员行(C1/C3/C4 独立)
E1 权限页 ──> E2 分步引导 ──> E3/E4 提示与来电(依赖 D2 共用提示 helper)
F1 RemoteGreeting(完全独立,随时可做)
```

**建议批次**:
1. **A1+A2**:地基。
2. **D1 + A3 + A4**:TGX 侧成本最低(SimplePush 通道现成),先打通"杀进程也能收通知"。
3. **A5**:WebRTC hook 在 TGX 的 voip 构建重接,单独排期。
4. **B2/B3 + C2 + C4**:增量配置与 UI。
5. **E1→E2→E3/E4**:从零建,UI 用 KuiklyUI,兼作框架验证。
6. **F1** 按需。

---

## 架构差异备忘(Telegram Android → TGX/TDLib)

| 源仓库概念 | TGX 对应 | 移植注意 |
|---|---|---|
| tgnet 直连 + `ConnectionsManager` | TDLib 客户端实例 | 代理注册走 `addProxy`;"A2 强制节点"不能直接改网络层,要在 TDLib proxy 体系上做 |
| PushListenerController PUSH_TYPE_SIMPLE=4 | `DeviceTokenSimplePush`(TdlibNotificationUtils:294 已支持) | 只差一个 UnifiedPush retriever(建议做成 `extension/unifiedpush` 变体) |
| `src/fork` manifest overlay | TGX 的 extension 变体机制(`extension/bridge` + `extra["extension"]`) | 用 extension 变体替代 overlay,更干净 |
| 远程语言包(translations.telegram.org) | TGX 自有 strings 体系 | 内置中文 = 打包完整 zh_hans strings + 默认语言 |
| vendored WebRTC(tgcalls 子模块零改动 hook) | `:tgcalls` 模块(sinceLollipop flavor) | hook 思路相同,落点在 TGX 的 voip 构建 |
| ForkFeatures 编译期门禁 + CUSTOM_HOOK 注释 | 无对应 | **建议整个约定照搬**:每个 upstream 触点可开关、可审计 |
| NanoHTTPD(RemoteGreeting) | TGX 依赖表里需确认 | 无则加 NanoHTTPD 或 Ktor |

---

## 验收基线(源仓库实测数据,移植后同口径复测)

| 指标 | 基线 | 机型/条件 |
|---|---|---|
| ntfy 唤醒被杀进程 | ~2.2s;后台 ~1.6s | vivo Y36 / OriginOS 3,双 app 三项保活权限齐 |
| 冷启动(含 polynet + WebView 延迟加载) | 1057→1058ms,无回归 | 同上 |
| 冷启动(standalone 改造后) | 1250→1246ms,PSS 256.6→257.2MB | vivo Y36 |
| Premium/Business 行 | 全部消失, gift 保留 | 同上 |
| 应用内浏览器 | telegram.org 经节点加载成功 | 同上 |
| merged manifest | 无 Google 组件残留 | 同上 |
| "循环贴纸" | 安装后默认关 | REDMI Turbo 4 Pro |
| 通话 | 被墙环境经节点可拨打接通;IPv6/TCP 零泄漏(抓包) | — |

---

*文档生成于 2026-11-15,基于 `D:\code\Telegram` 分支 `feature/telegram-customizations`(HEAD 53694c9f1)的提交正文与代码逐条核对;TGX 侧事实基于本仓库现有代码(`extension/none`、`TdlibNotificationUtils`)核实。*
