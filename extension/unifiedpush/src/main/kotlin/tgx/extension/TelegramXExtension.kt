package tgx.extension

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush
import org.thunderdog.challegram.fork.UnifiedPushEndpointStore
import tgx.bridge.DeviceTokenRetriever
import tgx.bridge.TokenRetrieverListener

/**
 * UnifiedPush (ntfy) push channel, PORTING_FEATURES.md §D1.
 *
 * The endpoint is registered with Telegram as a wake-only "simple push"
 * (token_type 4). Every push is a bare ping: it wakes networking and the
 * updates are then fetched over MTProto, so nothing readable passes through
 * the push server.
 */
object TelegramXExtension : Extension("unifiedpush") {
  override fun createNewTokenRetriever(context: Context): DeviceTokenRetriever =
    UnifiedPushDeviceTokenRetriever
}

class UnifiedPushDeviceTokenRetriever : DeviceTokenRetriever("unifiedpush") {
  override fun isAvailable(context: Context): Boolean =
    UnifiedPush.getDistributors(context).isNotEmpty()

  override fun performInitialization(context: Context): Boolean =
    isAvailable(context)

  override fun fetchDeviceToken(context: Context, listener: TokenRetrieverListener) {
    UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { success ->
      if (!success) {
        listener.onTokenRetrievalError("NO_UNIFIEDPUSH_DISTRIBUTOR", null)
        return@tryUseCurrentOrDefaultDistributor
      }
      UnifiedPushEndpointStore.get()?.let { existing ->
        // already registered with the distributor: report the endpoint right away
        listener.onTokenRetrievalSuccess(existing)
        return@tryUseCurrentOrDefaultDistributor
      }
      // registering on every attempt makes the distributor re-send the endpoint,
      // which then arrives in UnifiedPushService and goes to all accounts
      UnifiedPush.register(context, INSTANCE, null, null)
      listener.onTokenRetrievalError("UNIFIEDPUSH_ENDPOINT_PENDING", null)
    }
  }

  companion object {
    const val INSTANCE = "default"
  }
}
