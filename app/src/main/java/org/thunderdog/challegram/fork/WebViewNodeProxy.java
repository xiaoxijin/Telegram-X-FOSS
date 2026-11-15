/*
 * This file is a part of Telegram X (FOSS fork).
 *
 * Ported from the source fork (org.telegram.messenger.WebViewProxy),
 * see PORTING_FEATURES.md §A3. Renamed to WebViewNodeProxy to avoid clashing
 * with TGX's existing org.thunderdog.challegram.v.WebViewProxy (game bridge).
 */
package org.thunderdog.challegram.fork;

import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.tool.UI;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes WebViews (mini apps, in-app browser) through the built-in node's local SOCKS5 port.
 * The androidx.webkit proxy override is process-wide, so every WebView TGX opens is covered
 * once it is applied; nothing needs to be injected into individual WebView hosts.
 */
public final class WebViewNodeProxy {

  private static Forwarder node;

  /** Routes WebViews through the local proxy's SOCKS5 port; 0 clears the override. */
  public static synchronized void setNodePort (int port) {
    final boolean wasActive = node != null && node.target != 0;
    if (port != 0 && node == null) {
      try {
        node = new Forwarder();
      } catch (IOException e) {
        Log.e("WebViewNodeProxy", e);
        return;
      }
    }
    if (node != null) {
      node.target = port;
    }
    // the forwarder port is stable, so core restarts / node switches don't touch WebView at all
    if (wasActive != (port != 0)) {
      // apply() loads the WebView engine: run it once the main thread is idle instead of during cold start
      UI.post(() -> Looper.myQueue().addIdleHandler(() -> {
        apply();
        return false;
      }));
    }
  }

  private static synchronized void apply () {
    final int port = node != null && node.target != 0 ? node.getPort() : 0;
    try {
      if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
        Log.e("WebViewNodeProxy: WebView proxy override is not supported");
        return;
      }
      if (port == 0) {
        ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> { });
        return;
      }
      ProxyConfig config = new ProxyConfig.Builder()
        .addProxyRule("socks://127.0.0.1:" + port)
        .build();
      ProxyController.getInstance().setProxyOverride(config, Runnable::run, () ->
        Log.i("WebViewNodeProxy: WebView SOCKS5 proxy enabled on %d", port)
      );
    } catch (Throwable e) {
      Log.e("WebViewNodeProxy", e);
    }
  }

  /**
   * Plain TCP relay to the proxy core's SOCKS5 inbound. Accepts right away and holds each connection
   * until the core is listening, so WebView never sees ERR_PROXY_CONNECTION_FAILED while it boots.
   */
  static final class Forwarder {

    private static final int WAIT_MS = 15_000;

    private final ServerSocket serverSocket;
    private final ExecutorService executor;
    volatile int target;

    Forwarder () throws IOException {
      AtomicInteger threadId = new AtomicInteger();
      executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "WebViewNode-" + threadId.incrementAndGet());
        thread.setDaemon(true);
        return thread;
      });
      serverSocket = new ServerSocket();
      serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
      executor.execute(this::acceptLoop);
    }

    int getPort () {
      return serverSocket.getLocalPort();
    }

    private void acceptLoop () {
      while (true) {
        try {
          Socket socket = serverSocket.accept();
          executor.execute(() -> handle(socket));
        } catch (IOException e) {
          Log.e("WebViewNodeProxy", e);
        }
      }
    }

    private void handle (Socket client) {
      final long deadline = System.currentTimeMillis() + WAIT_MS;
      while (true) {
        final int port = target;
        if (port != 0) {
          Socket upstream = new Socket();
          try {
            upstream.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            executor.execute(() -> pipe(client, upstream));
            pipe(upstream, client);
            return;
          } catch (IOException e) {
            closeQuietly(upstream);
          }
        }
        if (System.currentTimeMillis() > deadline) {
          closeQuietly(client);
          return;
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          closeQuietly(client);
          return;
        }
      }
    }

    private static void pipe (Socket from, Socket to) {
      byte[] buffer = new byte[16 * 1024];
      try {
        InputStream input = from.getInputStream();
        OutputStream output = to.getOutputStream();
        int read;
        while ((read = input.read(buffer)) >= 0) {
          output.write(buffer, 0, read);
        }
      } catch (IOException ignore) {
      } finally {
        closeQuietly(from);
        closeQuietly(to);
      }
    }
  }

  private static void closeQuietly (@Nullable Closeable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (IOException ignore) {
    }
  }

  private WebViewNodeProxy () {
    throw new UnsupportedOperationException();
  }
}
