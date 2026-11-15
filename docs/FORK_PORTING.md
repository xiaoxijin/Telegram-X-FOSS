# Fork feature porting status (PORTING_FEATURES.md)

Status of the port described in `PORTING_FEATURES.md` (source: `D:\code\Telegram`,
branch `feature/telegram-customizations`). Everything below compiles; items marked
**device-verify** need on-device acceptance runs (see the acceptance table in
PORTING_FEATURES.md).

| Spec item | Status | Where |
|---|---|---|
| A1 polynet core | ✅ ported (Go tests green, prebuilt jar + arm64 libgojni.so) | `app/jni/polynet/`, `app/libs/polynet.jar`, `org.thunderdog.challegram.fork.PolyNodeTransport`, `scripts/build-polynet.sh` |
| A2 forced default node | ✅ ported (yields to user proxies) | `fork.PolyNodeManager` (wired in `BaseApplication.onCreate`) |
| A6 ping reuses main instance | ✅ parity (`startCheck`; TGX pings via TDLib testProxy against the local SOCKS5) | `fork.PolyNodeTransport` |
| A3 WebView over node | ✅ ported (fixed relay port, startup hold, idle-time apply; process-wide via androidx.webkit) | `fork.WebViewNodeProxy` |
| A4 IN_APP_BROWSER_ONLY | ✅ gate active in `Intents` (three exit points) | `fork.ForkFeatures` |
| A5 call UDP relay | ✅ ported + **CI-verified**: Linux CI full `assembleRelease` passes on clang Latest/Lollipop/Marshmallow (call_proxy_socket_server.cc + webrtc hook compile & link). The gcc-matrix / LegacyArm32 jobs fail at `:app:buildLibvpx` configure **on the untouched baseline too** (isolated by running CI at base commit 84b50523 — same early failure) = CI environment drift, unrelated to the port; Call UDP behavior itself needs on-device verification | `webrtc-patches/`, `scripts/private/patch-webrtc-impl.sh`, `app/jni/tgvoip/fork/call_proxy_socket_server.cc`, `fork.CallProxy`, `PolyNodeTransport.startCallRelay` |
| B1 standalone release form | ✅ N/A (TGX is already standalone; update check reports via its own channel) | — |
| B2/B3 default language | ✅ `custom.default_language` (e.g. `zh-hans`) first-start default; strings come over the regular remote-pack flow — **device-verify zh pack presence** | `Settings.readLanguage`, `local.properties.sample` |
| B4 zh permission strings | ✅ fork prompt strings are English built-in; zh rides the language-pack flow (E-group page copy is Chinese by design) | `strings.xml` |
| C PASSPORT | ✅ N/A (no Passport UI in TGX) | `fork.ForkFeatures` |
| C HIDE_PREMIUM | ✅ TGX has no promo rows (Play Billing removed upstream); "TG PRO membership" row added (hidden when `custom.pro_link` empty), opens Kuikly page `kuikly_pro`; functional premium alerts kept | `SettingsController`, `kuikly-pages .../KuiklyProPage.kt` |
| C LOOP_STICKERS | ✅ default off; user-saved values kept | `Settings.makeDefaultNewSettings` |
| D1 UnifiedPush | ✅ `extension/unifiedpush` variant (`tgx.extension=unifiedpush`); SimplePush (token_type 4) registration; empty-ping wake in `PushProcessor` — **device-verify** | `extension/unifiedpush/`, `service.PushProcessor` |
| D2 distributor guidance | ✅ install/open prompts once per process, dismissible; custom.ntfy_link with releases fallback | `fork.ForkPrompts`, `tgx.bridge.Extension` hooks |
| E1 permission page | ✅ KuiklyUI page + native module; MIUI routing table ported | `kuikly-pages .../KuiklyPermissionsPage.kt`, `kuikly.SystemPermissionsHelper`, `kuikly.XiaomiUtilities` |
| E2 step-by-step guide | ✅ one step at a time, auto re-check on return | same page |
| E3 startup prompt | ✅ shared one-shot scheduler in MainActivity.onResume | `fork.ForkPrompts` |
| E4 call reliability | ✅ **device-verified on Redmi Turbo 4 Pro (HyperOS 3.0)**: Kuikly permission page renders with al compact incoming-call notification keeps answer/decline visible (5de48b908); persistent "Call permissions" row in notification settings stepping through runtime permissions → full-screen-intent page → MIUI editor (4f863f3a0); autostart/battery prioritized on the Kuikly page (27bf780ba); HyperOS CallStyle crash fallback N/A — TGX never uses CallStyle (83ae19494); upstream's one-off MIUI lock-screen question N/A — TGX never asks (repeat prompting covered by ForkPrompts' persistent dismiss). CI-compiled. Call-button/notification behavior needs on-device verification | `TGCallService.showIncomingNotification`, `SettingsNotificationController`, `call_notification_compact.xml`, Kuikly permissions page |
| E5 strings | ✅ en built-in; zh via language pack | — |
| F1 RemoteGreeting | ✅ ported on TDLib (stranger check, confirmed send, own-side cleanup, non-retryable receipts); off unless `files/remote-greeting.json` — **device-verify** | `fork.RemoteGreeting*`, `BaseApplication` |
| G profileable | ✅ manifest `<profileable>` | `AndroidManifest.xml` |

## Building the proxy core locally

```bash
scripts/build-polynet.sh   # go test + gomobile bind -> app/libs/polynet.jar + jniLibs/arm64-v8a/libgojni.so
```

The committed `libgojni.so`/`polynet.jar` come from the source fork at HEAD 53694c9f1;
rerun the script after upgrading the Go core.

## Distribution switches (`local.properties`)

`custom.node_link`, `custom.ntfy_link`, `custom.pro_link`, `custom.default_language`
(see `local.properties.sample`). Push variant: `tgx.extension=unifiedpush`.

## Known deltas vs the source fork

- Telegram-Android's full bundled zh_hans pack is not transplantable (different
  strings system); TGX uses its remote language packs instead.
- E4's call-stack fixes beyond the permission surface are not ported.
- The webrtc patch must be re-applied after every webrtc submodule update
  (`scripts/setup.sh` does it automatically).

## Vendor adaptation matrix (§E per-ROM surface)

| Vendor | Detect | Autostart | Battery / background | Lock-screen & pop-ups | State readout |
|---|---|---|---|---|---|
| Xiaomi MIUI / HyperOS | `ro.miui.ui.version.name` / `ro.mi.os.version.name` | `miui.intent.action.OP_AUTO_START` (AppOps 10008) | `VIEW_ADVANCED_POWER_USAGE_DETAIL` | MIUI permission editor (AppOps 10020/10021) | ✅ live AppOps state |
| Huawei EMUI / HarmonyOS 4.x (Android-compatible) | `ro.build.version.emui` / `ro.build.version.harmonyos` / systemmanager resolvable | `com.huawei.systemmanager` startupmgr (candidates list) | systemmanager power pages | not exposed | ⛔ link-out only ("去设置") |
| vivo OriginOS / FuntouchOS | `ro.vivo.os.name` / `ro.vivo.os.build.display.id` | `com.vivo.permissionmanager` BgStartUpManager (candidates) | `com.vivo.abe` background power (candidates) | not exposed | ⛔ link-out only |
| OPPO ColorOS / OnePlus / realme | `ro.build.version.opporom` / `realmerom` | `com.coloros.safecenter` StartupAppList (candidates) | coloros power pages (candidates) | not exposed | ⛔ link-out only |
| Other | — | generic Android settings | `IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | generic | ✅ for the generic checks |

Routing is candidate-list based (`DeviceVendors.firstResolvable`): ROM releases shuffle
packages/activities, so the first resolvable candidate wins and the app details screen
is the last resort. `missingPermissions` only reports checks with a readable state, so
startup prompts never nag falsely on non-Xiaomi ROMs.

**Huawei has two lines** (see also `docs/KUIKLY.md`):
1. **EMUI / HarmonyOS 4.x and below** (Android APK installs) — covered by the
   Android-level vendor routing above; push via UnifiedPush/ntfy, keep-alive through
   the systemmanager pages.
2. **HarmonyOS NEXT** (no Android APK support) — the Kuikly pages compile to a native
   ArkUI host via `core-render-ohos` (see docs/KUIKLY.md §华为兼容路径); the
   SystemPermissionsModule gains an ArkTS implementation there.
