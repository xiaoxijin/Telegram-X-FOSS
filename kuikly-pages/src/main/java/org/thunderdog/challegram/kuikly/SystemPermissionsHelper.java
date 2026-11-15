/*
 * This file is a part of Telegram X (FOSS fork).
 *
 * Permission checks and system-page routing for the ROM keep-alive surface
 * (PORTING_FEATURES.md §E1/E2/E3/E4), ported from the source fork's
 * SystemPermissionsActivity logic. Framework-free so both the Android prompts
 * and the Kuikly permissions page drive the same checks.
 */
package org.thunderdog.challegram.kuikly;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class SystemPermissionsHelper {
  // permission ids, stable across the Kuikly page and Android prompts
  public static final int NOTIFICATIONS = 1;
  public static final int AUTO_START = 2;
  public static final int BATTERY = 3;
  public static final int LOCK_SCREEN = 4;
  public static final int BACKGROUND_POPUP = 5;
  public static final int OVERLAY = 6;
  public static final int FULL_SCREEN_NOTIFICATIONS = 7;

  private SystemPermissionsHelper () {
    throw new UnsupportedOperationException();
  }

  public static boolean isMIUI (Context context) {
    return XiaomiUtilities.isMIUI(context);
  }

  public static boolean notificationsAllowed (Context context) {
    NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.N || manager == null || manager.areNotificationsEnabled();
  }

  public static boolean fullScreenNotificationsAllowed (Context context) {
    NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager == null || manager.canUseFullScreenIntent();
  }

  public static boolean batteryUnrestricted (Context context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return true;
    }
    PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    if (powerManager == null || !powerManager.isIgnoringBatteryOptimizations(context.getPackageName())) {
      return false;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      if (activityManager != null && activityManager.isBackgroundRestricted()) {
        return false;
      }
    }
    return true;
  }

  /** Everything the app needs to still get messages and calls on a Chinese ROM, in guide order. */
  public static List<Integer> missingPermissions (Context context) {
    List<Integer> missing = new ArrayList<>();
    boolean miui = isMIUI(context);
    if (!notificationsAllowed(context)) {
      missing.add(NOTIFICATIONS);
    }
    if (miui && !XiaomiUtilities.isCustomPermissionGranted(context, XiaomiUtilities.OP_AUTO_START)) {
      missing.add(AUTO_START);
    }
    if (!batteryUnrestricted(context)) {
      missing.add(BATTERY);
    }
    if (miui && !XiaomiUtilities.isCustomPermissionGranted(context, XiaomiUtilities.OP_SHOW_WHEN_LOCKED)) {
      missing.add(LOCK_SCREEN); // HyperOS also switches this one off again with every app update
    }
    if (miui && !XiaomiUtilities.isCustomPermissionGranted(context, XiaomiUtilities.OP_BACKGROUND_START_ACTIVITY)) {
      missing.add(BACKGROUND_POPUP);
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
      missing.add(OVERLAY);
    }
    if (!fullScreenNotificationsAllowed(context)) {
      missing.add(FULL_SCREEN_NOTIFICATIONS);
    }
    return missing;
  }

  /** Real-time states for the Kuikly permissions page: id -> granted, plus vendor. */
  public static String statesJson (Context context) {
    JSONObject json = new JSONObject();
    try {
      boolean miui = isMIUI(context);
      int vendor = DeviceVendors.vendor(context);
      json.put("vendor", vendor);
      json.put("isMiui", miui);
      json.put(String.valueOf(NOTIFICATIONS), notificationsAllowed(context));
      if (miui) {
        json.put(String.valueOf(AUTO_START), XiaomiUtilities.isCustomPermissionGranted(context, XiaomiUtilities.OP_AUTO_START));
        json.put(String.valueOf(BATTERY), batteryUnrestricted(context));
        json.put(String.valueOf(LOCK_SCREEN), XiaomiUtilities.isCustomPermissionGranted(context, XiaomiUtilities.OP_SHOW_WHEN_LOCKED));
        json.put(String.valueOf(BACKGROUND_POPUP), XiaomiUtilities.isCustomPermissionGranted(context, XiaomiUtilities.OP_BACKGROUND_START_ACTIVITY));
      } else {
        // other ROMs hide these behind vendor managers without readable state:
        // the page shows "open settings" rows instead of true/false
        json.put(String.valueOf(BATTERY), batteryUnrestricted(context));
        json.put(String.valueOf(OVERLAY), Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context));
        json.put(String.valueOf(FULL_SCREEN_NOTIFICATIONS), fullScreenNotificationsAllowed(context));
      }
    } catch (Exception ignored) { }
    return json.toString();
  }

  /** One step per system page: on MIUI the app permission page holds lock screen, pop-ups and overlay together. */
  public static List<List<Integer>> guideSteps (Context context, List<Integer> missing) {
    List<List<Integer>> steps = new ArrayList<>();
    for (int id : missing) {
      if (!steps.isEmpty() && settingsPage(context, steps.get(steps.size() - 1).get(0)) == settingsPage(context, id)) {
        steps.get(steps.size() - 1).add(id);
      } else {
        List<Integer> step = new ArrayList<>();
        step.add(id);
        steps.add(step);
      }
    }
    return steps;
  }

  /** Which system page a permission lives on (on MIUI, lock screen / pop-ups / overlay share one page). */
  public static int settingsPage (Context context, int id) {
    boolean xiaomiAppPermissions = id == LOCK_SCREEN || id == BACKGROUND_POPUP || (id == OVERLAY && isMIUI(context));
    return xiaomiAppPermissions ? LOCK_SCREEN : id;
  }

  /** System page routing (PORTING_FEATURES.md §E1): vendor private routes first, generic pages then. */
  public static Intent settingsIntent (Context context, int id) {
    String packageName = context.getPackageName();
    Uri app = Uri.parse("package:" + packageName);
    switch (settingsPage(context, id)) {
      case NOTIFICATIONS:
        return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
      case AUTO_START: {
        Intent vendorIntent = DeviceVendors.autostartIntent(context);
        if (vendorIntent != null) {
          return vendorIntent;
        }
        break; // fall through to the app details fallback below
      }
      case BATTERY: {
        Intent vendorIntent = DeviceVendors.batteryIntent(context);
        if (vendorIntent != null) {
          return vendorIntent;
        }
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
      }
      case OVERLAY:
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, app);
      case FULL_SCREEN_NOTIFICATIONS:
        return new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, app);
      default:
        Intent editor = DeviceVendors.permissionEditorIntent(context);
        if (editor != null) {
          return editor; // lock screen, pop-ups, overlay on MIUI
        }
        break;
    }
    return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app);
  }

  /** Opens a system page, falling back to the app details screen (source fork behavior). */
  public static void openSettings (Context context, int id) {
    try {
      // CUSTOM_HOOK(kuikly-module): the module context is the application context
      // (background thread), so a NEW_TASK flag is required.
      context.startActivity(settingsIntent(context, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    } catch (Exception e) {
      try {
        context.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.parse("package:" + context.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      } catch (Exception ignored) { }
    }
  }

  /** "第 1 步" guide payload for the Kuikly page: first step's ids as a JSON array, plus count. */
  public static String guideJson (Context context) {
    JSONObject json = new JSONObject();
    try {
      List<Integer> missing = missingPermissions(context);
      List<List<Integer>> steps = guideSteps(context, missing);
      json.put("stepCount", steps.size());
      if (!steps.isEmpty()) {
        JSONArray step = new JSONArray();
        for (int id : steps.get(0)) {
          step.put(id);
        }
        json.put("step", step);
      }
    } catch (Exception ignored) { }
    return json.toString();
  }
}
