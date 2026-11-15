# Telegram X (FOSS) — Kuikly UI notes

KuiklyUI (Tencent-TDS/KuiklyUI) is integrated **at the UI layer only**: pages are
written in Kuikly's declarative Kotlin DSL and rendered by `core-render-android`,
with the goal of being able to run the exact same page code on Huawei
HarmonyOS NEXT (`core-render-ohos`) later. See `docs/KUIKLY.md` for the full
architecture and the HarmonyOS porting playbook.

## Module layout

- `kuikly-pages/` — all Kuikly code lives here:
  - `src/main/java/org/thunderdog/challegram/kuikly/pages/*.kt` — `@Page` pagers
    (**platform-neutral**: only import `com.tencent.kuikly.core.*`, never
    `android.*`, so the code stays HarmonyOS-compilable).
  - `src/main/java/com/tencent/kuikly/core/android/KuiklyCoreEntry.kt` —
    hand-maintained equivalent of the `core-ksp` generated page registry.
    **When adding a `@Page`, register it in `triggerRegisterPages()`.**
    (AGP 9 built-in Kotlin has no KSP hook, which is why this is manual.)
  - `KuiklyActivity.kt`, `KuiklyAdapters.kt` — Android host container +
    framework adapters (image/log/router/thread/exception).
- App depends on `:kuikly-pages`; the entry point is
  `KuiklyActivity.start(context, pageName, pageData)` (Settings → “Kuikly UI”).

## Versions & rules

- Kuikly version lives in `gradle/libs.versions.toml` (`kuikly = "2.4.2-2.0.21-ohos"`).
  Version scheme: `<kuikly>-<kotlinBuiltWith>[-ohos]`; prefer the `-ohos`
  variant so the HarmonyOS target artifacts exist for the same version.
- Kuikly DSL conventions (attr/event blocks, observable state, vfor/vif
  directives, pitfalls): `.claude/rules/kuiklyDSL.mdc`; deeper references:
  `.claude/skills/kuikly-ui-framework/` (auto-clones upstream docs/sources into
  `references/KuiklyUI`, which is git-ignored).
- NEVER invent Kuikly API names from memory — check the DSL rules / upstream
  references first, or the published AAR (javap) when targeting a specific
  published version.
