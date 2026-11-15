/*
 * This file is a part of Telegram X (FOSS fork).
 *
 * Ported from the source fork (org.telegram.utils.proxy.PolyNodeTransport),
 * see PORTING_FEATURES.md §A1/A6. The call-relay hook (setRelayPort /
 * startUDPRelay, §A5) is intentionally not wired yet: TGX's WebRTC build
 * needs its own socket-server hook first, see PORTING_FEATURES.md §A5.
 */
package org.thunderdog.challegram.fork;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.tool.UI;
import org.telegram.polynet.Polynet;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the bundled proxy core in-process (app/jni/polynet, Go via gomobile): it turns a remote
 * node server into a local SOCKS5 proxy, which TDLib then uses as a regular SOCKS5 proxy.
 * In-process, so the system can't kill the core on its own (Android 12+ phantom-process limits).
 */
public final class PolyNodeTransport {
  private static final ExecutorService executor = Executors.newSingleThreadExecutor();

  // guarded by PolyNodeTransport.class
  private static String activeLink;
  private static int activePort;
  // touched only on executor
  private static long activeHandle;

  private PolyNodeTransport () {
    throw new UnsupportedOperationException();
  }

  /** Starts (or reuses) the main instance. Returns the local SOCKS5 port, 0 on failure. */
  public static synchronized int start (String link) {
    if (link.equals(activeLink) && activePort != 0) {
      return activePort;
    }
    final PolyNodeConfig config = PolyNodeConfig.parse(link);
    final int port = config != null ? freePort() : 0;
    activeLink = config != null ? link : null;
    activePort = port;
    WebViewNodeProxy.setNodePort(port);
    executor.execute(() -> {
      CallProxy.setRelayPort(0);
      Polynet.stop(activeHandle);
      activeHandle = port != 0 ? run(config, port) : 0;
      startCallRelay(port);
    });
    return port;
  }

  public static synchronized void stop () {
    if (activeLink == null) {
      return;
    }
    activeLink = null;
    activePort = 0;
    WebViewNodeProxy.setNodePort(0);
    executor.execute(() -> {
      CallProxy.setRelayPort(0);
      Polynet.stop(activeHandle);
      activeHandle = 0;
    });
  }

  /** Calls' UDP goes through the node only while this relay runs; without it they connect directly (§A5). */
  private static void startCallRelay (int port) {
    if (activeHandle == 0) {
      return;
    }
    try {
      // same number as the SOCKS5 port (UDP side, unused by the core); fails if taken, calls then go direct
      Polynet.startUDPRelay(activeHandle, port);
      CallProxy.setRelayPort(port);
    } catch (Throwable e) {
      Log.e("PolyNode: call UDP relay unavailable", e);
    }
  }

  /**
   * Checks a node through the main instance if it already serves it, else through a throwaway
   * one (§A6: proxy-list ping reuses the running core instead of starting another instance).
   * TGX pings via TDLib's {@code testProxy} against the SOCKS5 inbound; for the built-in node
   * the main instance is always up while it is the effective proxy, so pings cost no extra
   * core instance. This API exists for parity with the source fork.
   */
  public interface CheckCallback {
    /** Called on the UI thread with a ready local SOCKS5 port; call {@code done} when finished. */
    void run (int port, Runnable done);
  }

  public static void startCheck (String link, CheckCallback callback) {
    executor.execute(() -> {
      final int mainPort;
      synchronized (PolyNodeTransport.class) {
        mainPort = link.equals(activeLink) ? activePort : 0;
      }
      if (mainPort != 0 && activeHandle != 0) {
        UI.post(() -> callback.run(mainPort, () -> { }));
        return;
      }
      final PolyNodeConfig config = PolyNodeConfig.parse(link);
      final int port = config != null ? freePort() : 0;
      final long handle = port != 0 ? run(config, port) : 0;
      final Runnable done = () -> executor.execute(() -> Polynet.stop(handle));
      // hard cap so a check that never reports back can't leak an instance (stop is idempotent)
      UI.post(done, 30_000);
      UI.post(() -> callback.run(handle != 0 ? port : 0, done));
    });
  }

  /** @return instance handle, 0 on failure; the SOCKS5 inbound is listening when this returns */
  private static long run (@Nullable PolyNodeConfig config, int port) {
    if (config == null || port == 0) {
      return 0;
    }
    try {
      String server;
      try {
        server = InetAddress.getByName(config.host).getHostAddress();
      } catch (Exception e) {
        server = config.host;
      }
      // gomobile loads libgojni.so lazily; arm64-only .so → degrades to "no built-in node" elsewhere
      return Polynet.start(config.toConfigJson(server, port));
    } catch (Throwable e) {
      Log.e("PolyNode: core failed to start", e);
      return 0;
    }
  }

  private static int freePort () {
    // port may be taken between close and core bind; practically never on loopback
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    } catch (Exception e) {
      return 0;
    }
  }
}
