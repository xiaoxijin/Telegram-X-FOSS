package org.thunderdog.challegram.kuikly.module

import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * Kuikly-side bridge to the host's system-permission surface (§E1/E2).
 * The Android implementation lives in KuiklySystemPermissionsModule
 * ("KRSystemPermissionsModule"); the HarmonyOS host will provide its own
 * implementation with the same methods.
 */
class SystemPermissionsModule : Module() {

  /** id -> granted, plus "isMiui". Synchronous JSON string. */
  fun permissionStates (): JSONObject {
    val result = syncToNativeMethod(METHOD_STATES, arrayOf(""), null)
    return JSONObject(result?.toString() ?: "{}")
  }

  /** Current guide step (first missing permission group) and total step count. */
  fun permissionGuide (): JSONObject {
    val result = syncToNativeMethod(METHOD_GUIDE, arrayOf(""), null)
    return JSONObject(result?.toString() ?: "{}")
  }

  /** Opens the system settings page for a permission id. */
  fun openSettings (id: Int) {
    toNative(false, METHOD_OPEN_SETTINGS, id.toString(), null, false)
  }

  override fun moduleName (): String = MODULE_NAME

  companion object {
    const val MODULE_NAME = "KRSystemPermissionsModule"
    const val METHOD_STATES = "permissionStates"
    const val METHOD_GUIDE = "permissionGuide"
    const val METHOD_OPEN_SETTINGS = "openSettings"
  }
}
