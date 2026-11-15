package org.thunderdog.challegram.kuikly

import android.content.Context
import android.util.Log
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import org.thunderdog.challegram.kuikly.SystemPermissionsHelper
import org.thunderdog.challegram.kuikly.module.SystemPermissionsModule

/**
 * Android-side implementation of the Kuikly permissions page's native module
 * (PORTING_FEATURES.md §E1/E2). Methods called from the Kuikly DSL via
 * `toNative`; see SystemPermissionsModule on the Kuikly side.
 * Context is injected by KuiklyActivity when the module is registered.
 */
class KuiklySystemPermissionsModule (private val appContext: Context) : KuiklyRenderBaseModule() {

  override fun call (method: String, params: Any?, callback: ((Any?) -> Unit)?): Any? {
    android.util.Log.i("KuiklySysPermModule", "call method=$method params=$params")
    when (method) {
      SystemPermissionsModule.METHOD_STATES -> {
        val json = SystemPermissionsHelper.statesJson(appContext)
        callback?.invoke(json)
        return json
      }
      SystemPermissionsModule.METHOD_GUIDE -> {
        val json = SystemPermissionsHelper.guideJson(appContext)
        callback?.invoke(json)
        return json
      }
      SystemPermissionsModule.METHOD_OPEN_SETTINGS -> {
        val id = (params as? String)?.toIntOrNull() ?: 0
        if (id != 0) {
          SystemPermissionsHelper.openSettings(appContext, id)
        }
        callback?.invoke("ok")
        return "ok"
      }
    }
    return null
  }
}
