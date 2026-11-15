/*
 * This file is a part of Telegram X (FOSS fork).
 *
 * Ported from the source fork (org.telegram.messenger.RemoteGreetingCommand),
 * PORTING_FEATURES.md §F1: strict protocol validation, org.json instead of Gson.
 */
package org.thunderdog.challegram.fork;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class RemoteGreetingCommand {
  public final String requestId;
  public final long accountUserId;
  public final String username;
  public final String text;

  private RemoteGreetingCommand (String requestId, long accountUserId, String username, String text) {
    this.requestId = requestId;
    this.accountUserId = accountUserId;
    this.username = username;
    this.text = text;
  }

  @Nullable
  public static RemoteGreetingCommand parse (@Nullable String body) {
    try {
      if (body == null || body.length() > 24 * 1024) {
        return null;
      }
      JSONObject object = new JSONObject(body);
      if (object.length() != 4) {
        return null;
      }
      String requestId = string(object, "request_id");
      String accountId = string(object, "account_user_id");
      String username = string(object, "username").trim().toLowerCase(Locale.ROOT);
      String text = string(object, "text");
      if (username.startsWith("@")) {
        username = username.substring(1);
      }
      if (!requestId.matches("[A-Za-z0-9_-]{1,64}") || !accountId.matches("[0-9]+")
        || !username.matches("[a-z0-9_]{1,32}") || blank(text) || text.length() > 4096) {
        return null;
      }
      long accountUserId = Long.parseLong(accountId);
      if (accountUserId <= 0) {
        return null;
      }
      return new RemoteGreetingCommand(requestId, accountUserId, username, text);
    } catch (RuntimeException | JSONException error) {
      return null;
    }
  }

  public String toJson () {
    JSONObject object = new JSONObject();
    try {
      object.put("request_id", requestId);
      object.put("account_user_id", Long.toString(accountUserId));
      object.put("username", username);
      object.put("text", text);
    } catch (JSONException ignored) { }
    return object.toString();
  }

  private static String string (JSONObject object, String key) throws IllegalArgumentException {
    Object value = object.opt(key);
    if (!(value instanceof String)) {
      throw new IllegalArgumentException();
    }
    return (String) value;
  }

  private static boolean blank (String value) {
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isWhitespace(value.charAt(i)) && !Character.isSpaceChar(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  public static boolean authorized (@Nullable String token, @Nullable String header) {
    return token != null && token.matches("[A-Za-z0-9_-]{32,128}") && header != null
      && MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
      header.getBytes(StandardCharsets.UTF_8));
  }

  public boolean matches (@Nullable RemoteGreetingCommand other) {
    return other != null && requestId.equals(other.requestId) && accountUserId == other.accountUserId
      && username.equals(other.username) && text.equals(other.text);
  }
}
