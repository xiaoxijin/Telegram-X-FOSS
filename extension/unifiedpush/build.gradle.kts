plugins {
  id("java-toolchain-convention")
  id(libs.plugins.android.library.get().pluginId)
  id("tgx-module")
}

dependencies {
  api(project(":extension:bridge"))
  // UnifiedPush connector (a distributor such as ntfy delivers wake-only pings,
  // PORTING_FEATURES.md §D1). Its JVM 'tink' duplicates tink-android, so exclude it.
  implementation("org.unifiedpush.android:connector:3.3.5") {
    exclude(group = "com.google.crypto.tink", module = "tink")
  }
}

android {
  namespace = "tgx.extension"
}
