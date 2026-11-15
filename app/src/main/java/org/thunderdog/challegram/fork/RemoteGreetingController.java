/*
 * This file is a part of Telegram X (FOSS fork).
 *
 * Ported from the source fork (org.telegram.messenger.RemoteGreetingController),
 * PORTING_FEATURES.md §F1, on TDLib: resolves the username, verifies the
 * recipient is a "stranger" (private non-contact user with no chat history),
 * sends the greeting, waits for the server to confirm delivery, then deletes
 * this side's history and removes the chat from the chat list. The recipient
 * keeps the greeting; two-sided deletion is not supported.
 */
package org.thunderdog.challegram.fork;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.TDLib;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccount;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.tool.UI;

import java.util.concurrent.atomic.AtomicBoolean;

import me.vkryl.core.StringUtils;

/** Sends one greeting and removes only a newly created private dialog on this account's side. */
public final class RemoteGreetingController {
  public interface ResultCallback {
    void onResult (String status, @Nullable String error);
  }

  private static final long TIMEOUT_MS = 90_000;
  // the loopback server runs one command at a time (another_command_is_running), so no
  // additional per-account guard is needed here
  private static final AtomicBoolean singleCommand = new AtomicBoolean(false);

  private final RemoteGreetingCommand command;
  private final ResultCallback callback;
  private final Tdlib tdlib;
  private long chatId;
  private long sentMessageId;
  private boolean sendIssued;
  private String phase = "resolving";

  public static void execute (RemoteGreetingCommand command, ResultCallback callback) {
    UI.post(() -> {
      Tdlib tdlib = findAccount(command.accountUserId);
      if (tdlib == null) {
        callback.onResult("failed", "Account is not logged in on this device");
        return;
      }
      if (!singleCommand.compareAndSet(false, true)) {
        callback.onResult("failed", "Account already has a greeting command in progress");
        return;
      }
      new RemoteGreetingController(command, callback, tdlib).start();
    });
  }

  private static @Nullable Tdlib findAccount (long accountUserId) {
    TdlibManager manager = TdlibManager.instance();
    for (TdlibAccount account : manager) {
      Tdlib tdlib = account.tdlibNoWakeup();
      if (tdlib != null && tdlib.myUserId() == accountUserId) {
        return tdlib;
      }
    }
    return null;
  }

  private RemoteGreetingController (RemoteGreetingCommand command, ResultCallback callback, Tdlib tdlib) {
    this.command = command;
    this.callback = callback;
    this.tdlib = tdlib;
  }

  private void start () {
    UI.post(this::failAfterTimeout, TIMEOUT_MS);
    resolveUsername();
  }

  private void failAfterTimeout () {
    // the receipt stays "accepted" (non-retryable), matching the source fork's contract
    finish("unknown", "Operation timed out; do not resend automatically");
  }

  private void resolveUsername () {
    TdApi.SearchPublicChat request = new TdApi.SearchPublicChat(command.username);
    tdlib.client().send(request, result -> {
      if (checkFinished()) {
        return;
      }
      if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
        finish("failed", TD.toErrorString(result));
        return;
      }
      TdApi.Chat chat = (TdApi.Chat) result;
      if (!(chat.type instanceof TdApi.ChatTypePrivate)) {
        finish("failed", "Username did not resolve to a private user");
        return;
      }
      long userId = ((TdApi.ChatTypePrivate) chat.type).userId;
      if (userId == command.accountUserId) {
        finish("failed", "Recipient must be a private user other than the account itself");
        return;
      }
      chatId = chat.id;
      checkRecipient(chat);
    });
  }

  private void checkRecipient (TdApi.Chat chat) {
    if (chat.type instanceof TdApi.ChatTypePrivate) {
      long userId = ((TdApi.ChatTypePrivate) chat.type).userId;
      if (userId != command.accountUserId) {
        // need the full user to verify not-a-bot / not-a-contact / paid messages
        tdlib.client().send(new TdApi.GetUser(userId), userResult -> {
          if (checkFinished()) {
            return;
          }
          if (userResult.getConstructor() == TdApi.Error.CONSTRUCTOR) {
            finish("failed", TD.toErrorString(userResult));
            return;
          }
          TdApi.User user = (TdApi.User) userResult;
          if (user.type instanceof TdApi.UserTypeBot) {
            finish("failed", "Recipient must not be a bot");
            return;
          }
          if (user.isContact) {
            finish("failed", "Recipient must not be a contact");
            return;
          }
          checkHistoryEmpty();
        });
        return;
      }
    }
    finish("failed", "Recipient must be a private user");
  }

  private void checkHistoryEmpty () {
    // "stranger": no existing chat history on either side
    tdlib.client().send(new TdApi.GetChatHistory(chatId, 0, 0, 1, false), result -> {
      if (checkFinished()) {
        return;
      }
      if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
        finish("failed", TD.toErrorString(result));
        return;
      }
      TdApi.Messages messages = (TdApi.Messages) result;
      if (messages.totalCount > 0) {
        finish("failed", "Recipient already has chat history");
        return;
      }
      sendGreeting();
    });
  }

  private void sendGreeting () {
    phase = "sending";
    sendIssued = true;
    TdApi.FormattedText text = new TdApi.FormattedText(command.text, new TdApi.TextEntity[0]);
    TdApi.LinkPreviewOptions linkPreviewOptions = new TdApi.LinkPreviewOptions();
    linkPreviewOptions.isDisabled = true; // the greeting is plain text (searchLinks=false upstream)
    TdApi.InputMessageContent content = new TdApi.InputMessageText(text, linkPreviewOptions, false);
    tdlib.client().send(new TdApi.SendMessage(chatId, null, null, null, null, content), result -> {
      if (checkFinished()) {
        return;
      }
      if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
        finish("failed", "Telegram rejected the greeting: " + TD.toErrorString(result));
        return;
      }
      TdApi.Message message = (TdApi.Message) result;
      if (message.id <= 0) {
        finish("unknown", "Server did not provide a valid message id");
        return;
      }
      sentMessageId = message.id;
      checkSentAndCleanup();
    });
  }

  /**
   * Waits until the server confirms the message was delivered (polling GetChatHistory:
   * TDLib keeps the id negative until then), then deletes this side's history.
   */
  private void checkSentAndCleanup () {
    phase = "confirming";
    pollConfirmations(0);
  }

  private void pollConfirmations (final int attempt) {
    if (checkFinished()) {
      return;
    }
    tdlib.client().send(new TdApi.GetMessage(chatId, sentMessageId), result -> {
      if (checkFinished()) {
        return;
      }
      if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
        // message may not be visible yet; retry until the timeout fires
        pollLater(attempt);
        return;
      }
      TdApi.Message message = (TdApi.Message) result;
      if (message.sendingState != null && message.sendingState instanceof TdApi.MessageSendingStatePending) {
        pollLater(attempt);
        return;
      }
      // server confirmed delivery: delete this side's history and drop the chat from the list
      deleteLocalHistory();
    });
  }

  private void pollLater (final int attempt) {
    if (attempt >= 80) {
      finish("unknown", "Delivery was not confirmed in time; history preserved");
      return;
    }
    UI.post(() -> pollConfirmations(attempt + 1), 1000);
  }

  private void deleteLocalHistory () {
    phase = "deleting";
    TdApi.DeleteChatHistory request = new TdApi.DeleteChatHistory(chatId, true, false);
    tdlib.client().send(request, result -> {
      if (checkFinished()) {
        return;
      }
      if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
        finish("cleanup_failed", TD.toErrorString(result));
        return;
      }
      finish("completed", null);
    });
  }

  private boolean checkFinished () {
    if ("finished".equals(phase)) {
      return true;
    }
    // account changed during execution: the greeting may or may not have been sent
    if (tdlib.myUserId() != command.accountUserId) {
      finish(sendIssued ? "unknown" : "failed", "Account changed during execution");
      return true;
    }
    return false;
  }

  private void finish (String status, @Nullable String error) {
    if ("finished".equals(phase)) {
      return;
    }
    phase = "finished";
    Log.i("RemoteGreeting %s: %s (%s)", command.requestId, status, error != null ? error : "-");
    singleCommand.set(false);
    callback.onResult(status, error);
  }
}
