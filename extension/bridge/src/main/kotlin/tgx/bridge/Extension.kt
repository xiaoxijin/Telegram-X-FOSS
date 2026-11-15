package tgx.bridge

import android.app.Application
import android.content.Context
import android.content.Intent

open class Extension(@JvmField val name: String) {
  fun isNotEmpty(): Boolean =
    name != "none"

  open fun createNewTokenRetriever(context: Context): DeviceTokenRetriever? =
    null
  open fun configure(application: Application, deviceTokenRetriever: DeviceTokenRetriever) { }

  // ── push distributor guidance hooks (PORTING_FEATURES.md §D2) ──
  // null = this extension has no UnifiedPush distributor; the prompt machinery stays off.

  /** Endpoint received from the distributor in this process? */
  open fun isPushDistributorReady(context: Context): Boolean? = null

  /** Any UnifiedPush distributor (e.g. ntfy) installed? */
  open fun hasPushDistributor(context: Context): Boolean? = null

  /** Launch intent for the distributor app, or null if it is not installed. */
  open fun createDistributorLaunchIntent(context: Context): Intent? = null
}
