package org.thunderdog.challegram.fork

import org.drinkless.tdlib.TdApi
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import tgx.bridge.PushManagerBridge

/**
 * UnifiedPush distributor callbacks (PORTING_FEATURES.md §D1).
 *
 * Simple pushes carry only "version=N" (nothing to decrypt): each one just wakes
 * networking, and the updates are fetched over MTProto (see PushProcessor's
 * empty-payload wake).
 */
class UnifiedPushService : PushService() {

  override fun onNewEndpoint (endpoint: PushEndpoint, instance: String) {
    // never log the URL: anyone holding it can wake the app
    PushManagerBridge.log("UnifiedPush endpoint received")
    UnifiedPushEndpointStore.store(endpoint.url)
    PushManagerBridge.onNewToken(this, TdApi.DeviceTokenSimplePush(endpoint.url))
  }

  override fun onMessage (message: PushMessage, instance: String) {
    PushManagerBridge.log("UnifiedPush wake")
    // bare ping: empty payload → PushProcessor wakes the TDLib clients,
    // which then pull the updates over MTProto
    PushManagerBridge.onMessageReceived(this, emptyMap(), System.currentTimeMillis(), 0)
  }

  override fun onRegistrationFailed (reason: FailedReason, instance: String) {
    PushManagerBridge.error("UnifiedPush registration failed: $reason", null)
  }

  override fun onUnregistered (instance: String) {
    PushManagerBridge.log("UnifiedPush unregistered by the distributor")
    UnifiedPushEndpointStore.clear()
    // empty endpoint = registration withdrawn; the caller ignores empty tokens
    PushManagerBridge.onNewToken(this, TdApi.DeviceTokenSimplePush(""))
  }
}
