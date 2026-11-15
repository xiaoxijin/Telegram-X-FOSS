/*
 * This file is a part of Telegram X (FOSS fork).
 *
 * Ported from the source fork (org.telegram.utils.proxy.PolyNodeConfig),
 * see PORTING_FEATURES.md §A1.
 */
package org.thunderdog.challegram.fork;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses {@code vless://uuid@host:port?params#name} links and turns them into a
 * proxy-core config with a local SOCKS5 inbound. Pure Java so it can be checked off-device.
 */
public final class PolyNodeConfig {
  public final String uuid;
  public final String host;
  public final int port;
  public final Map<String, String> params;

  private PolyNodeConfig (String uuid, String host, int port, Map<String, String> params) {
    this.uuid = uuid;
    this.host = host;
    this.port = port;
    this.params = params;
  }

  /** @return parsed link or {@code null} if it is not a usable node link */
  @Nullable
  public static PolyNodeConfig parse (@Nullable String link) {
    if (link == null || !link.regionMatches(true, 0, "vless://", 0, 8)) {
      return null;
    }
    try {
      final URI uri = new URI(link.trim());
      final String uuid = uri.getRawUserInfo();
      final String host = uri.getHost();
      final int port = uri.getPort();
      if (uuid == null || uuid.isEmpty() || host == null || host.isEmpty() || port <= 0 || port > 65535) {
        return null;
      }
      final Map<String, String> params = new HashMap<>();
      final String query = uri.getRawQuery();
      if (query != null) {
        for (String pair : query.split("&")) {
          final int eq = pair.indexOf('=');
          if (eq > 0) {
            params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
          }
        }
      }
      // URI keeps IPv6 brackets in getHost()
      final String cleanHost = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
      return new PolyNodeConfig(URLDecoder.decode(uuid, "UTF-8"), cleanHost, port, params);
    } catch (Exception e) {
      return null;
    }
  }

  private String param (String key, String def) {
    final String v = params.get(key);
    return v == null || v.isEmpty() ? def : v;
  }

  /**
   * @param serverAddress resolved address of {@link #host} (Go's resolver has no system DNS on Android)
   * @param localPort     port of the local SOCKS5 inbound
   */
  public String toConfigJson (String serverAddress, int localPort) throws JSONException {
    final String network = param("type", "tcp");
    final String security = param("security", "none");
    final String sni = param("sni", param("host", host));

    final JSONObject stream = new JSONObject()
      .put("network", network)
      .put("security", security);
    if ("tls".equals(security)) {
      final JSONObject tls = new JSONObject()
        .put("serverName", sni)
        .put("fingerprint", param("fp", "chrome"));
      if (params.containsKey("alpn")) {
        tls.put("alpn", new JSONArray(java.util.Arrays.asList(param("alpn", "").split(","))));
      }
      if ("1".equals(params.get("allowInsecure"))) {
        tls.put("allowInsecure", true);
      }
      stream.put("tlsSettings", tls);
    } else if ("reality".equals(security)) {
      stream.put("realitySettings", new JSONObject()
        .put("serverName", sni)
        .put("fingerprint", param("fp", "chrome"))
        .put("publicKey", param("pbk", ""))
        .put("shortId", param("sid", ""))
        .put("spiderX", param("spx", "")));
    }
    switch (network) {
      case "ws":
        stream.put("wsSettings", new JSONObject()
          .put("path", param("path", "/"))
          .put("host", param("host", "")));
        break;
      case "grpc":
        stream.put("grpcSettings", new JSONObject()
          .put("serviceName", param("serviceName", ""))
          .put("multiMode", "multi".equals(params.get("mode"))));
        break;
      case "httpupgrade":
        stream.put("httpupgradeSettings", new JSONObject()
          .put("path", param("path", "/"))
          .put("host", param("host", "")));
        break;
      case "xhttp":
      case "splithttp":
        stream.put("xhttpSettings", new JSONObject()
          .put("path", param("path", "/"))
          .put("host", param("host", ""))
          .put("mode", param("mode", "auto")));
        break;
      // tcp headerType=http, kcp, quic not supported; add when someone has such a node
    }

    final JSONObject user = new JSONObject()
      .put("id", uuid)
      .put("encryption", param("encryption", "none"));
    if (params.containsKey("flow")) {
      final String flow = param("flow", "");
      // client-side variant: plain Vision refuses UDP/443, which a call's TURN server may use
      user.put("flow", "xtls-rprx-vision".equals(flow) ? "xtls-rprx-vision-udp443" : flow);
    }

    final JSONObject outbound = new JSONObject()
      .put("protocol", "vless")
      .put("settings", new JSONObject().put("vnext", new JSONArray().put(new JSONObject()
        .put("address", serverAddress)
        .put("port", port)
        .put("users", new JSONArray().put(user)))))
      .put("streamSettings", stream);

    final JSONObject inbound = new JSONObject()
      .put("listen", "127.0.0.1")
      .put("port", localPort)
      .put("protocol", "socks")
      .put("settings", new JSONObject().put("auth", "noauth").put("udp", true));

    return new JSONObject()
      // in-process the core logs to logcat: no per-connection access log (it lists every destination)
      .put("log", new JSONObject().put("loglevel", "warning").put("access", "none"))
      .put("inbounds", new JSONArray().put(inbound))
      .put("outbounds", new JSONArray().put(outbound))
      .toString();
  }
}
