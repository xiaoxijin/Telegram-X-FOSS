/*
 * This file is a part of Telegram X (FOSS fork).
 *
 * One-shot startup prompts (PORTING_FEATURES.md §D2 + §E3), ported from the
 * source fork's LaunchActivity checks: push-distributor guidance and the ROM
 * keep-alive permission list. Each prompt shows at most once per process and
 * can be switched off persistently ("不再提醒").
 */
package org.thunderdog.challegram.fork;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.BuildConfig;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.kuikly.KuiklyActivity;
import org.thunderdog.challegram.kuikly.SystemPermissionsHelper;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.tool.UI;

import java.util.List;

import tgx.extension.TelegramXExtension;

public final class ForkPrompts {
  private static final String PREF_PUSH_OFF = "fork_push_prompt_off";
  private static final String PREF_PERMISSIONS_OFF = "fork_permissions_prompt_off";

  // at most one of each prompt per process
  private static boolean installPromptShown, openPromptShown, permissionsPromptShown;

  private static final String NTFY_RELEASES_URL = "https://github.com/binwiederhier/ntfy-android/releases/latest";

  private ForkPrompts () {
    throw new UnsupportedOperationException();
  }

  /** Called from {@code MainActivity.onResume}. */
  public static void check (BaseActivity activity) {
    if (activity == null || activity.isFinishing()) {
      return;
    }
    checkPushDistributor(activity);
    checkPermissions(activity);
  }

  // ── §D2: ntfy distributor guidance ──

  private static void checkPushDistributor (Activity activity) {
    Boolean ready = TelegramXExtension.INSTANCE.isPushDistributorReady(activity);
    if (ready == null) {
      return; // not a UnifiedPush build
    }
    if (ready || Settings.instance().getBoolean(PREF_PUSH_OFF, false)) {
      return;
    }
    Boolean hasDistributor = TelegramXExtension.INSTANCE.hasPushDistributor(activity);
    boolean installed = Boolean.TRUE.equals(hasDistributor);
    if (installed ? openPromptShown : installPromptShown) {
      return;
    }
    if (installed) {
      openPromptShown = true;
    } else {
      installPromptShown = true;
    }
    showPushPrompt(activity, installed);
  }

  private static void showPushPrompt (Activity activity, boolean installed) {
    UI.post(() -> {
      try {
        new android.app.AlertDialog.Builder(activity)
          .setTitle(R.string.ForkPushTitle)
          .setMessage(installed ? R.string.ForkPushOpenInfo : R.string.ForkPushInstallInfo)
          .setPositiveButton(installed ? R.string.ForkPushOpen : R.string.ForkPushInstall, (d, w) ->
            openPushDistributor(activity)
          )
          .setNegativeButton(R.string.ForkNeverRemind, (d, w) ->
            Settings.instance().putBoolean(PREF_PUSH_OFF, true)
          )
          .show();
      } catch (Throwable t) {
        Log.e("ForkPrompts", t);
      }
    }, 1000);
  }

  /**
   * Opens ntfy, or where to get it: custom.ntfy_link (e.g. the APK posted in our channel),
   * else the releases page. t.me links only open once logged in, so before that the
   * releases page is used too (§D2).
   */
  private static void openPushDistributor (Activity activity) {
    Intent ntfy = TelegramXExtension.INSTANCE.createDistributorLaunchIntent(activity);
    if (ntfy != null) {
      try {
        activity.startActivity(ntfy);
        return;
      } catch (ActivityNotFoundException ignored) { }
    }
    String link = BuildConfig.CUSTOM_NTFY_LINK;
    boolean beforeLogin = TdlibManager.instance().currentNoWakeup() == null ||
      TdlibManager.instance().currentNoWakeup().myUser() == null;
    if (link == null || link.isEmpty() || (link.contains("t.me") && beforeLogin)) {
      link = NTFY_RELEASES_URL;
    }
    try {
      activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
    } catch (Throwable t) {
      Log.e("ForkPrompts", t);
    }
  }

  // ── §E3: keep-alive permission prompt ──

  private static void checkPermissions (Activity activity) {
    if (permissionsPromptShown || Settings.instance().getBoolean(PREF_PERMISSIONS_OFF, false)) {
      return;
    }
    List<Integer> missing = SystemPermissionsHelper.missingPermissions(activity);
    if (missing.isEmpty()) {
      return;
    }
    permissionsPromptShown = true;
    StringBuilder message = new StringBuilder(activity.getString(R.string.ForkPermText));
    for (int id : missing) {
      message.append("\n• ").append(permissionName(activity, id));
    }
    if (missing.contains(SystemPermissionsHelper.LOCK_SCREEN)) {
      message.append("\n\n").append(activity.getString(R.string.ForkPermLockScreenReset));
    }
    final String text = message.toString();
    UI.post(() -> {
      try {
        new android.app.AlertDialog.Builder(activity)
          .setTitle(R.string.ForkPermTitle)
          .setMessage(text)
          .setPositiveButton(R.string.ForkPermOpen, (d, w) ->
            KuiklyActivity.start(activity, "kuikly_permissions")
          )
          .setNegativeButton(R.string.ForkNeverRemind, (d, w) ->
            Settings.instance().putBoolean(PREF_PERMISSIONS_OFF, true)
          )
          .show();
      } catch (Throwable t) {
        Log.e("ForkPrompts", t);
      }
    }, 2000);
  }

  private static String permissionName (Activity activity, int id) {
    switch (id) {
      case SystemPermissionsHelper.NOTIFICATIONS: return activity.getString(R.string.ForkPermNotifications);
      case SystemPermissionsHelper.AUTO_START: return activity.getString(R.string.ForkPermAutoStart);
      case SystemPermissionsHelper.BATTERY: return activity.getString(R.string.ForkPermBattery);
      case SystemPermissionsHelper.LOCK_SCREEN: return activity.getString(R.string.ForkPermLockScreen);
      case SystemPermissionsHelper.BACKGROUND_POPUP: return activity.getString(R.string.ForkPermBackgroundPopup);
      case SystemPermissionsHelper.OVERLAY: return activity.getString(R.string.ForkPermOverlay);
      default: return activity.getString(R.string.ForkPermFullScreen);
    }
  }
}
