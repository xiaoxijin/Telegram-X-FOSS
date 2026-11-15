/*
 * This file is a part of Telegram X (FOSS fork).
 *
 * Built-in proxy node integration (PORTING_FEATURES.md §A1/A2/A6).
 * TGX-specific glue: polynet core + TDLib proxy registration.
 */
package org.thunderdog.challegram.fork;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BuildConfig;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.unsorted.Settings;

/**
 * Forces the built-in node from {@code custom.node_link} whenever the user has not
 * enabled a proxy of their own; a user-configured proxy always wins (and the core
 * stops, so it holds no sockets). The node surfaces as a regular entry in TGX's
 * proxy list (kept visible for transparency), but the user never has to touch it.
 *
 * Proxy-list pings go through TDLib's testProxy against the local SOCKS5 inbound,
 * so they reuse the running core instead of starting extra instances (§A6).
 */
public final class PolyNodeManager {
  /** Description of the built-in proxy entry (visible in the proxy list). */
  public static final String BUILT_IN_PROXY_DESCRIPTION = "Built-in node";

  private static boolean initialized;
  private static int builtInProxyId = Settings.PROXY_ID_NONE;
  /** Guards against callbacks dispatched by our own {@code addOrUpdateProxy} call. */
  private static boolean registering;

  private PolyNodeManager () {
    throw new UnsupportedOperationException();
  }

  /**
   * Called once from {@code BaseApplication.onCreate}, before any TDLib client starts,
   * so the proxy is in place before the first connection.
   */
  public static void initialize () {
    if (initialized) {
      return;
    }
    initialized = true;
    final String link = BuildConfig.CUSTOM_NODE_LINK;
    if (PolyNodeConfig.parse(link) == null) {
      return; // not configured / unusable: behave exactly like a build without a node
    }
    Settings.instance().addProxyListener(new Settings.ProxyChangeListener() {
      @Override
      public void onProxyConfigurationChanged (int proxyId, @Nullable TdApi.Proxy proxy, @Nullable String description, boolean isCurrent, boolean isNewAdd) {
        if (registering || !isCurrent) {
          return;
        }
        if (proxyId == builtInProxyId) {
          ensureStarted();
        } else {
          // the user picked (or enabled) their own proxy: yield completely
          PolyNodeTransport.stop();
        }
      }

      @Override
      public void onProxyAvailabilityChanged (boolean isAvailable) {
        if (registering) {
          return;
        }
        // proxy feature disabled / all entries removed → fall back to the built-in node
        if (!isAvailable) {
          ensureStarted();
        }
      }

      @Override
      public void onProxyAdded (Settings.Proxy proxy, boolean isCurrent) {
        if (registering) {
          return;
        }
        if (isCurrent && proxy != null && proxy.id != builtInProxyId) {
          PolyNodeTransport.stop();
        }
      }
    });
    ensureStarted();
  }

  private static void ensureStarted () {
    if (registering) {
      return;
    }
    registering = true;
    try {
      final int port = PolyNodeTransport.start(BuildConfig.CUSTOM_NODE_LINK);
      if (port == 0) {
        Log.e("PolyNode: no local SOCKS5 inbound, built-in node is inactive");
        return;
      }
      final TdApi.Proxy proxy = new TdApi.Proxy("127.0.0.1", port, new TdApi.ProxyTypeSocks5());
      if (builtInProxyId != Settings.PROXY_ID_NONE) {
        // port changed across core restarts: update the existing entry in place
        Settings.instance().addOrUpdateProxy(proxy, BUILT_IN_PROXY_DESCRIPTION, isCurrentProxy(builtInProxyId), builtInProxyId);
      } else {
        // yield to a proxy the user already has enabled; register ours as current otherwise
        builtInProxyId = Settings.instance().addOrUpdateProxy(proxy, BUILT_IN_PROXY_DESCRIPTION, !userProxyActive());
      }
    } finally {
      registering = false;
    }
  }

  private static boolean userProxyActive () {
    final int currentId = Settings.instance().getEffectiveProxyId();
    return currentId != Settings.PROXY_ID_NONE && currentId != builtInProxyId;
  }

  private static boolean isCurrentProxy (int proxyId) {
    return Settings.instance().getEffectiveProxyId() == proxyId;
  }
}
