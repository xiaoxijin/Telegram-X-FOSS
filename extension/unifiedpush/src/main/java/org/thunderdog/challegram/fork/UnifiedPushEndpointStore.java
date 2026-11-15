package org.thunderdog.challegram.fork

import org.drinkless.tdlib.TdApi

/**
 * Last endpoint received from the UnifiedPush distributor in this process, so token
 * fetches between registrations resolve without another distributor round-trip.
 * Guarded by the class monitor; written by [UnifiedPushService].
 */
object UnifiedPushEndpointStore {
  @Volatile
  private var endpoint: String? = null

  @Synchronized
  fun store(url: String) {
    endpoint = url
  }

  @Synchronized
  fun get(): TdApi.DeviceTokenSimplePush? =
    endpoint?.let { TdApi.DeviceTokenSimplePush(it) }

  @Synchronized
  fun clear() {
    endpoint = null
  }
}
