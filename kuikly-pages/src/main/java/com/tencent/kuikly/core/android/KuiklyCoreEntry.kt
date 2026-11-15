package com.tencent.kuikly.core.android

import com.tencent.kuikly.core.IKuiklyCoreEntry
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.nvi.NativeBridge
import org.thunderdog.challegram.kuikly.pages.KuiklyAboutPage
import org.thunderdog.challegram.kuikly.pages.KuiklyHomePage

/**
 * Hand-maintained equivalent of the file Kuikly's KSP processor
 * (`com.tencent.kuikly-open:core-ksp`) generates from `@Page`-annotated pagers.
 *
 * This repository builds with AGP 9 built-in Kotlin, which has no KSP hook,
 * so the generated entry is replicated by hand. Signatures follow the published
 * `com.tencent.kuikly-open:core-android` artifact (see IKuiklyCoreEntry /
 * NativeBridge in the AAR). When adding a `@Page`, register it in
 * [triggerRegisterPages]; when upgrading Kuikly, diff this file against the
 * output of the upstream processor and against the new core AAR.
 *
 * Kept unobfuscated by the consumer rules shipped inside `core-render-android`
 * (`-keep class com.tencent.kuikly.core.android.KuiklyCoreEntry { *; }`).
 */
class KuiklyCoreEntry : IKuiklyCoreEntry {
  private var hadRegisterNativeBridge = false

  override var delegate: IKuiklyCoreEntry.Delegate? = null

  override fun callKotlinMethod(
    methodId: Int,
    arg0: Any?,
    arg1: Any?,
    arg2: Any?,
    arg3: Any?,
    arg4: Any?,
    arg5: Any?
  ) {
    if (!hadRegisterNativeBridge) {
      triggerRegisterPages()
      hadRegisterNativeBridge = true
      val nativeBridge = NativeBridge()
      nativeBridge.delegate = object : NativeBridge.NativeBridgeDelegate {
        override fun callNative(
          methodId: Int,
          arg0: Any?,
          arg1: Any?,
          arg2: Any?,
          arg3: Any?,
          arg4: Any?,
          arg5: Any?
        ): Any? {
          return delegate?.callNative(methodId, arg0, arg1, arg2, arg3, arg4, arg5)
        }
      }
      BridgeManager.registerNativeBridge(arg0 as String, nativeBridge)
    }
    BridgeManager.callKotlinMethod(methodId, arg0, arg1, arg2, arg3, arg4, arg5)
  }

  override fun triggerRegisterPages() {
    BridgeManager.registerPageRouter("kuikly_home") { KuiklyHomePage() }
    BridgeManager.registerPageRouter("kuikly_about") { KuiklyAboutPage() }
  }
}
