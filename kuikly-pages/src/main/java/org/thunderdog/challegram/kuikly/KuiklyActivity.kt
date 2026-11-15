package org.thunderdog.challegram.kuikly

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
import com.tencent.kuikly.core.render.android.context.KuiklyRenderCoreExecuteModeBase
import com.tencent.kuikly.core.render.android.exception.ErrorReason
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegator
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate

/**
 * Kuikly 页面承载容器（宿主侧），按官方 androidApp 接入模式实现：
 * JVM 执行模式直接加载 AAR 内的页面类（contextCode 传 ""），
 * 生命周期通过 [KuiklyRenderViewBaseDelegator] 转发给 Kuikly 页面。
 *
 * 页面本体在 `org.thunderdog.challegram.kuikly.pages`，与宿主解耦；
 * 鸿蒙侧使用同一批页面源码 + `core-render-ohos`（见 docs/KUIKLY.md）。
 */
class KuiklyActivity : Activity() {

  private lateinit var delegator: KuiklyRenderViewBaseDelegator
  private lateinit var loadingView: View

  private val pageName: String
    get() = intent.getStringExtra(KEY_PAGE_NAME)?.takeIf { it.isNotEmpty() } ?: DEFAULT_PAGE

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ensureAdaptersRegistered()
    delegator = KuiklyRenderViewBaseDelegator(createDelegate())
    val container = FrameLayout(this)
    loadingView = ProgressBar(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER
      )
    }
    val root = FrameLayout(this)
    root.addView(container, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT
    ))
    root.addView(loadingView, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT
    ))
    setContentView(root)
    delegator.onAttach(container, "", pageName, createPageData())
  }

  override fun onResume() {
    super.onResume()
    delegator.onResume()
  }

  override fun onPause() {
    delegator.onPause()
    super.onPause()
  }

  override fun onDestroy() {
    delegator.onDetach()
    super.onDestroy()
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP &&
      delegator.onBackPressed()
    ) {
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  private fun createDelegate(): KuiklyRenderViewBaseDelegatorDelegate {
    return object : KuiklyRenderViewBaseDelegatorDelegate {
      // JVM 模式：页面代码以 AAR 形式静态集成（本仓库的 kuikly-pages 模块）。
      override fun coreExecuteModeX(): KuiklyRenderCoreExecuteModeBase {
        return KuiklyRenderCoreExecuteModeBase.JVM
      }

      // 宿主扩展 Module：系统权限页（PORTING_FEATURES.md §E1/E2）等原生能力。
      override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalModule(kuiklyRenderExport)
        val appContext = applicationContext
        kuiklyRenderExport.moduleExport(org.thunderdog.challegram.kuikly.module.SystemPermissionsModule.MODULE_NAME) {
          KuiklySystemPermissionsModule(appContext)
        }
      }

      override fun onPageLoadComplete(
        isSucceed: Boolean,
        errorReason: ErrorReason?,
        executeMode: KuiklyRenderCoreExecuteModeBase
      ) {
        loadingView.visibility = if (isSucceed) View.GONE else View.VISIBLE
        if (!isSucceed) {
          Log.e(TAG, "Kuikly page \"$pageName\" failed to load: $errorReason")
        }
      }

      override fun onUnhandledException(
        throwable: Throwable,
        errorReason: ErrorReason,
        executeMode: KuiklyRenderCoreExecuteModeBase
      ) {
        Log.e(TAG, "Kuikly unhandled exception ($errorReason): ${throwable.stackTraceToString()}")
      }
    }
  }

  private fun createPageData(): MutableMap<String, Any> {
    val map = HashMap<String, Any>()
    val json = intent.getStringExtra(KEY_PAGE_DATA)
    if (!json.isNullOrEmpty()) {
      val obj = JSONObject(json)
      for (key in obj.keySet()) {
        val value = obj.opt(key) ?: continue
        map[key] = value
      }
    }
    map["debug"] = isDebuggable()
    return map
  }

  private fun isDebuggable(): Boolean {
    return (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
  }

  private fun ensureAdaptersRegistered() {
    with(KuiklyRenderAdapterManager) {
      if (krLogAdapter == null) {
        krLogAdapter = KuiklyLogAdapter
      }
      if (krImageAdapter == null) {
        krImageAdapter = KuiklyImageAdapter(applicationContext)
      }
      if (krRouterAdapter == null) {
        krRouterAdapter = KuiklyRouterAdapter
      }
      if (krThreadAdapter == null) {
        krThreadAdapter = KuiklyThreadAdapter()
      }
      if (krUncaughtExceptionHandlerAdapter == null) {
        krUncaughtExceptionHandlerAdapter = KuiklyCrashAdapter
      }
    }
  }

  companion object {
    private const val TAG = "KuiklyActivity"
    private const val KEY_PAGE_NAME = "pageName"
    private const val KEY_PAGE_DATA = "pageData"
    private const val DEFAULT_PAGE = "kuikly_home"

    @JvmStatic
    @JvmOverloads
    fun start(context: Context, pageName: String = DEFAULT_PAGE, pageData: JSONObject = JSONObject()) {
      val starter = Intent(context, KuiklyActivity::class.java)
        .putExtra(KEY_PAGE_NAME, pageName)
        .putExtra(KEY_PAGE_DATA, pageData.toString())
      context.startActivity(starter)
    }
  }
}
