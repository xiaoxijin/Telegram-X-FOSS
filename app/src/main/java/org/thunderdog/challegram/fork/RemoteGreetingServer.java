/*
 * This file is a part of Telegram X (FOSS fork).
 *
 * Ported from the source fork (org.telegram.messenger.RemoteGreetingServer),
 * PORTING_FEATURES.md §F1: opt-in loopback NanoHTTPD endpoint; remote callers
 * use an authenticated tunnel or adb forwarding. Off unless files/
 * remote-greeting.json exists and is valid.
 */
package org.thunderdog.challegram.fork;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.unsorted.AppContext;
import org.thunderdog.challegram.tool.UI;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public final class RemoteGreetingServer extends NanoHTTPD {
  private static final int MAX_BODY_BYTES = 24 * 1024;
  private static final String RECEIPT_PREFIX = "request:";
  private static volatile @Nullable RemoteGreetingServer instance;

  private final String token;
  private final SharedPreferences receipts;
  private boolean busy;

  private RemoteGreetingServer (String token, int port, SharedPreferences receipts) {
    super("127.0.0.1", port);
    this.token = token;
    this.receipts = receipts;
  }

  /** Called once from {@code BaseApplication.onCreate}. */
  public static void startIfConfigured () {
    if (instance != null) {
      return;
    }
    Context context = AppContext.get();
    File configFile = new File(context.getFilesDir(), "remote-greeting.json");
    if (!configFile.isFile()) {
      return;
    }
    try (InputStream input = new FileInputStream(configFile)) {
      JSONObject config = new JSONObject(readBody(input, (int) configFile.length(), 4096));
      String token = config.getString("token");
      if (!RemoteGreetingCommand.authorized(token, "Bearer " + token)) {
        throw new IllegalArgumentException("Invalid remote greeting token");
      }
      int port = config.optInt("port", 8765);
      if (port < 1024 || port > 65535) {
        throw new IllegalArgumentException("Invalid remote greeting port");
      }
      SharedPreferences receipts = context.getSharedPreferences("remote_greetings", Context.MODE_PRIVATE);
      // Never resend a command whose outcome was lost during a process restart.
      SharedPreferences.Editor editor = receipts.edit();
      for (Map.Entry<String, ?> entry : receipts.getAll().entrySet()) {
        if (entry.getKey().startsWith(RECEIPT_PREFIX)) {
          JSONObject receipt = new JSONObject((String) entry.getValue());
          if ("accepted".equals(receipt.optString("status"))) {
            receipt.put("status", "unknown");
            receipt.put("error", "process_restarted; message may have been sent; not retried");
            editor.putString(entry.getKey(), receipt.toString());
          }
        }
      }
      if (!editor.commit()) {
        throw new IOException("Unable to persist remote greeting state");
      }
      RemoteGreetingServer server = new RemoteGreetingServer(token, port, receipts);
      server.start(SOCKET_READ_TIMEOUT, true);
      instance = server;
      Log.i("Remote greeting endpoint listening on 127.0.0.1:%d", port);
    } catch (Exception e) {
      // Configuration includes a secret; do not log its contents or parser exceptions.
      Log.e("Remote greeting endpoint could not start; check private configuration and port");
    }
  }

  @Override
  public Response serve (IHTTPSession session) {
    if (!RemoteGreetingCommand.authorized(token, session.getHeaders().get("authorization"))) {
      Response response = error(Response.Status.UNAUTHORIZED, "unauthorized");
      response.addHeader("WWW-Authenticate", "Bearer");
      return response;
    }
    String uri = session.getUri();
    if (Method.GET.equals(session.getMethod()) && uri.startsWith("/commands/")) {
      String requestId = uri.substring("/commands/".length());
      if (!requestId.matches("[A-Za-z0-9_-]{1,64}")) {
        return error(Response.Status.BAD_REQUEST, "invalid_request_id");
      }
      synchronized (this) {
        String saved = receipts.getString(RECEIPT_PREFIX + requestId, null);
        try {
          return saved == null ? error(Response.Status.NOT_FOUND, "unknown_request")
            : receiptResponse(Response.Status.OK, new JSONObject(saved));
        } catch (JSONException e) {
          return error(Response.Status.INTERNAL_ERROR, "corrupt_receipt");
        }
      }
    }
    if (!"/commands/greet-and-remove".equals(uri)) {
      return error(Response.Status.NOT_FOUND, "unknown_endpoint");
    }
    if (!Method.POST.equals(session.getMethod())) {
      return error(Response.Status.METHOD_NOT_ALLOWED, "post_required");
    }
    String contentType = session.getHeaders().get("content-type");
    if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
      return error(Response.Status.UNSUPPORTED_MEDIA_TYPE, "application_json_required");
    }
    try {
      String lengthHeader = session.getHeaders().get("content-length");
      if (lengthHeader == null || session.getHeaders().containsKey("transfer-encoding")) {
        return error(Response.Status.BAD_REQUEST, "content_length_required; chunked requests are unsupported");
      }
      int length = Integer.parseInt(lengthHeader);
      if (length < 1 || length > MAX_BODY_BYTES) {
        return error(Response.Status.BAD_REQUEST, "invalid_body_length");
      }
      RemoteGreetingCommand command = RemoteGreetingCommand.parse(readBody(session.getInputStream(), length, MAX_BODY_BYTES));
      if (command == null) {
        return error(Response.Status.BAD_REQUEST, "invalid_command");
      }
      synchronized (this) {
        String key = RECEIPT_PREFIX + command.requestId;
        String existing = receipts.getString(key, null);
        if (existing != null) {
          JSONObject receipt = new JSONObject(existing);
          RemoteGreetingCommand existingCommand = RemoteGreetingCommand.parse(receipt.optJSONObject("command") == null ? null : receipt.getJSONObject("command").toString());
          if (!command.matches(existingCommand)) {
            return error(Response.Status.CONFLICT, "request_id_already_used_for_another_command");
          }
          return receiptResponse(Response.Status.OK, receipt);
        }
        // one command at a time; per-account queues only if concurrency is needed.
        if (busy) {
          return error(Response.Status.CONFLICT, "another_command_is_running");
        }
        // retain 1000 IDs; use a SQLite ledger if a longer command history is needed.
        if (receipts.getAll().size() >= 1000) {
          return error(Response.Status.CONFLICT, "receipt_limit_reached");
        }
        JSONObject receipt = new JSONObject();
        receipt.put("request_id", command.requestId);
        receipt.put("status", "accepted");
        receipt.put("command", new JSONObject(command.toJson()));
        if (!receipts.edit().putString(key, receipt.toString()).commit()) {
          return error(Response.Status.INTERNAL_ERROR, "unable_to_persist_request");
        }
        busy = true;
        UI.post(() -> {
          try {
            RemoteGreetingController.execute(command, (status, detail) -> finish(key, status, detail));
          } catch (Exception e) {
            finish(key, "unknown", "execution_interrupted; not retried");
          }
        });
        return receiptResponse(Response.Status.ACCEPTED, receipt);
      }
    } catch (IOException | IllegalArgumentException | JSONException e) {
      return error(Response.Status.BAD_REQUEST, "invalid_command");
    }
  }

  private synchronized void finish (String key, String status, @Nullable String detail) {
    try {
      JSONObject receipt = new JSONObject(receipts.getString(key, "{}"));
      receipt.put("status", status);
      if (detail != null) {
        receipt.put("error", detail);
      }
      if (!receipts.edit().putString(key, receipt.toString()).commit()) {
        // Persisted "accepted" remains non-retryable even if the final receipt cannot be saved.
        Log.e("Unable to persist remote greeting result");
      }
    } catch (JSONException ignored) {
    }
    busy = false;
  }

  private static String readBody (InputStream input, int length, int limit) throws IOException {
    if (length < 1 || length > limit) {
      throw new IllegalArgumentException("Invalid body length");
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream(length);
    byte[] buffer = new byte[Math.min(length, 4096)];
    int remaining = length;
    while (remaining > 0) {
      int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
      if (count < 0) {
        throw new IOException("Incomplete request body");
      }
      output.write(buffer, 0, count);
      remaining -= count;
    }
    return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString();
  }

  private static Response receiptResponse (Response.Status status, JSONObject receipt) throws JSONException {
    JSONObject response = new JSONObject(receipt.toString());
    response.remove("command");
    return json(status, response);
  }

  private static Response error (Response.Status status, String detail) {
    JSONObject response = new JSONObject();
    try {
      response.put("error", detail);
    } catch (JSONException ignored) {
    }
    return json(status, response);
  }

  private static Response json (Response.Status status, JSONObject body) {
    Response response = newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString());
    response.closeConnection(true);
    response.addHeader("Cache-Control", "no-store");
    return response;
  }
}
