/*
 * This file is a part of Telegram X (FOSS fork).
 *
 * Ported from the source fork (org.telegram.utils.proxy.CallProxy), §A5:
 * carries the call engines' UDP through polynet's UDP relay while the built-in
 * node runs (app/jni/tgvoip/fork/call_proxy_socket_server.cc, app/jni/polynet/
 * udprelay.go). Relay port 0 = upstream behaviour (calls connect directly).
 */
package org.thunderdog.challegram.fork;

public final class CallProxy {
  private CallProxy () {
    throw new UnsupportedOperationException();
  }

  public static native void setRelayPort (int port);
}
