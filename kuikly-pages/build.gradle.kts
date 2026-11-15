@file:Suppress("UnstableApiUsage", "AvoidApplyFromScript")

plugins {
  id("java-toolchain-convention")
  id(libs.plugins.android.library.get().pluginId)
  id("tgx-module")
}

dependencies {
  // Kuikly cross-platform core (KMP artifact, resolves core-android on this target).
  // Keep the version in sync with KuiklyCoreEntry / docs/KUIKLY.md and the Huawei (HarmonyOS NEXT) host.
  // core-annotations carries @Page; it is NOT a transitive dependency of `core`.
  api(libs.kuikly.core)
  api(libs.kuikly.core.annotations)
  implementation(libs.kuikly.render.android)
}

android {
  namespace = "org.thunderdog.challegram.kuikly"
}
