plugins {
  id("java-toolchain-convention")
  id(libs.plugins.android.library.get().pluginId)
  id("tgx-module")
}

dependencies {
  api(project(":extension:bridge"))
  // DeviceToken types (TdApi.DeviceTokenSimplePush) live in the tdlib module
  implementation(project(":tdlib"))
  // UnifiedPush connector (a distributor such as ntfy delivers wake-only pings,
  // PORTING_FEATURES.md §D1). Its JVM 'tink' duplicates tink-android, so exclude it —
  // but add tink-android explicitly: the connector's WebPushHybridDecrypt (loaded on
  // class verification) needs com.google.crypto.tink.subtle.* at runtime.
  implementation("org.unifiedpush.android:connector:3.3.5") {
    exclude(group = "com.google.crypto.tink", module = "tink")
  }
  implementation("com.google.crypto.tink:tink-android:1.23.0")
}

android {
  namespace = "tgx.extension"
}
