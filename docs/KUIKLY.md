# KuiklyUI integration (UI-layer upgrade, HarmonyOS NEXT ready)

本文描述本仓库基于 [Tencent-TDS/KuiklyUI](https://github.com/Tencent-TDS/KuiklyUI)
的 UI 层升级改造：页面用 Kuikly 声明式 DSL（Kotlin）编写，Android 侧由
`core-render-android` 渲染；同一批页面源码保持平台中立，可直接编译到
华为 HarmonyOS NEXT（`core-render-ohos`）。

## 架构

```
┌────────────────────────── app（宿主，纯原生） ──────────────────────────┐
│  Settings → org.thunderdog.challegram.kuikly.KuiklyActivity            │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ implementation(project(":kuikly-pages"))
┌────────────────── kuikly-pages（全部 Kuikly 代码）──────────────────────┐
│ pages/KuiklyHomePage.kt   @Page("kuikly_home")   ← 平台中立 common 代码 │
│ pages/KuiklyAboutPage.kt  @Page("kuikly_about")                        │
│ com.tencent.kuikly.core.android.KuiklyCoreEntry  ← 手写的 KSP 产物等价物│
│ KuiklyActivity / KuiklyAdapters ← Android 宿主容器 + 平台适配器          │
└──────────┬─────────────────────────────────────────┬───────────────────┘
           │ api                                      │ implementation
   com.tencent.kuikly-open:core          com.tencent.kuikly-open:core-render-android
```

要点：

- **只在 UI 层改造**：业务逻辑、TDLib、导航栈（ViewController）不动；
  Kuikly 页面是独立入口（Activity），不嵌入原生导航栈。
- **KuiklyCoreEntry 手写**：本仓库使用 AGP 9 内置 Kotlin（无 KGP/KSP 插件），
  KSP 处理器（`core-ksp`）无法运行，因此按其产物手工维护页面注册表。
  新增页面时在 `triggerRegisterPages()` 里补一行
  `BridgeManager.registerPageRouter("<pageName>") { XxxPage() }`。
- **版本**：`gradle/libs.versions.toml` → `kuikly = "2.4.2-2.0.21-ohos"`。
  选带 `-ohos` 后缀的版本：同一版本号下 `core-ohosarm64` 等鸿蒙 target
  产物同时发布在 Maven Central。

## Android 侧接入（已实现）

1. 依赖：`com.tencent.kuikly-open:core-render-android` + `:core`（Maven Central）。
2. 容器：`KuiklyActivity` —— JVM 执行模式（页面类在 AAR 内），
   `KuiklyRenderViewBaseDelegator.onAttach(...)` 加载页面，
   `onResume/onPause/onDestroy` 与 `dispatchKeyEvent(back)` 转发。
3. 适配器（`KuiklyAdapters.kt`）：日志、线程、路由（必选），
   异常收集（推荐），图片（http/file/assets/base64，自带 LruCache，无第三方依赖）。
4. Manifest：`android:windowSoftInputMode="stateUnspecified|adjustNothing"`
   （键盘规避由 Kuikly `keyboardHeightChange` 事件自行处理）。
5. 混淆：`core-render-android` 自带 consumer rules，无需额外配置。

入口：设置 → “Kuikly UI (experimental)”，或代码
`KuiklyActivity.start(context, "kuikly_home")`。

## 华为（HarmonyOS NEXT）兼容路径

页面代码（`pages/*.kt`）只 import `com.tencent.kuikly.core.*`，没有任何
`android.*` 依赖 —— 这是鸿蒙可编译的前提。官方接入路径
（`docs/QuickStart/harmony.md`，本地缓存见
`.claude/skills/kuikly-ui-framework/references/KuiklyUI/`）：

1. **业务 so**：把 `kuikly-pages` 的页面源码放入 KMP 工程（或直接复用本目录），
   用 Kuikly 的鸿蒙构建脚本（Kotlin 2.4.21 工具链 `2.4.21_ohos_demo_build.sh`
   / `settings.ohos.gradle.kts` 模式）执行
   `./gradlew -c settings.ohos.gradle.kts :shared:linkOhosArm64`，
   产出 `libshared.so` + `libshared_api.h`。
2. **宿主工程**（DevEco Studio）：
   - `oh-package.json5` 依赖 `"@kuikly-open/render"`（ohpm，版本同 2.4.2）；
   - C++（NAPI）模块链接 `libshared.so` 与 `render::kuikly`，
     `InitKuikly()` 调 `libshared_symbols()` 初始化运行时；
   - ArkTS 侧 `KuiklyNativeManager.loadNative()` 关联 NAPI 入口，
     页面用 `<Kuikly pageName="kuikly_home" .../>` 组件承载。
3. **适配器对齐**：鸿蒙侧同样需要日志/路由（ArkTS 版）适配器；
   图片加载走 C-API `KRRegisterImageAdapterV2/V3`。
4. **assets**：`commonMain/assets`（本仓库暂无页面资源，如新增需同步
   拷贝到 `entry/src/main/resources/resfile`）。

升级 Kuikly 版本时：同步改 `libs.versions.toml` 的 `kuikly` 版本号，
并 diff `KuiklyCoreEntry` 与新版 `core-ksp` 产物/新 AAR 的
`IKuiklyCoreEntry` 签名（javap）。

## 验证

```bash
# 模块级编译（不依赖其余子模块）：
./gradlew :kuikly-pages:compileLatestDebugKotlin

# 完整 APK：构建全部子模块后 :app:assembleLatestRelease
# 运行验证：设置 → Kuikly UI (experimental)，应看到演示页并可跳转关于页。
```
