/*
 * This file is a part of Telegram X (FOSS fork).
 *
 * Vendor abstraction for the ROM keep-alive surface (PORTING_FEATURES.md §E):
 * each Chinese ROM manages autostart / background / battery through its own
 * private settings surface, so the permissions page and prompts are
 * vendor-specific. Routing uses candidate intents resolved against the
 * package manager (ROM layouts drift between releases; the first resolvable
 * candidate wins, falling back to the app details screen).
 */
package org.thunderdog.challegram.kuikly;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public final class DeviceVendors {
  public static final int VENDOR_OTHER = 0;
  public static final int VENDOR_XIAOMI = 1;   // MIUI / HyperOS
  public static final int VENDOR_HUAWEI = 2;   // EMUI / HarmonyOS 4.x (Android-compatible builds)
  public static final int VENDOR_VIVO = 3;     // OriginOS / FuntouchOS
  public static final int VENDOR_OPPO = 4;     // ColorOS (OPPO / OnePlus / realme)

  private static volatile int cachedVendor = -1;

  private DeviceVendors () {
    throw new UnsupportedOperationException();
  }

  public static int vendor (Context context) {
    int vendor = cachedVendor;
    if (vendor != -1) {
      return vendor;
    }
    if (!XiaomiUtilities.getSystemProperty("ro.miui.ui.version.name").isEmpty() ||
      !XiaomiUtilities.getSystemProperty("ro.mi.os.version.name").isEmpty()) {
      vendor = VENDOR_XIAOMI;
    } else if (!getSystemProperty("ro.build.version.emui").isEmpty() ||
      !getSystemProperty("ro.build.version.harmonyos").isEmpty() ||
      isResolvable(context, "com.huawei.systemmanager")) {
      vendor = VENDOR_HUAWEI;
    } else if (!getSystemProperty("ro.vivo.os.name").isEmpty() ||
      !getSystemProperty("ro.vivo.os.build.display.id").isEmpty()) {
      vendor = VENDOR_VIVO;
    } else if (!getSystemProperty("ro.build.version.opporom").isEmpty() ||
      !getSystemProperty("ro.build.version.realmerom").isEmpty() ||
      isResolvable(context, "com.coloros.safecenter")) {
      vendor = VENDOR_OPPO;
    } else {
      vendor = VENDOR_OTHER;
    }
    cachedVendor = vendor;
    return vendor;
  }

  private static boolean isResolvable (Context context, String packageName) {
    try {
      return context.getPackageManager().getLaunchIntentForPackage(packageName) != null;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static String getSystemProperty (String key) {
    String value = XiaomiUtilities.getSystemProperty(key);
    return value == null ? "" : value;
  }

  /**
   * Autostart management page. Every vendor hides it differently; candidates are
   * tried in order until one resolves (RO releases shuffle packages/activities).
   */
  public static Intent autostartIntent (Context context) {
    int vendor = vendor(context);
    switch (vendor) {
      case VENDOR_XIAOMI:
        return firstResolvable(context,
          new Intent("miui.intent.action.OP_AUTO_START").setPackage("com.miui.securitycenter"),
          component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"));
      case VENDOR_HUAWEI:
        return firstResolvable(context,
          component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
          component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
          component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"));
      case VENDOR_VIVO:
        return firstResolvable(context,
          component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
          component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
          component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"));
      case VENDOR_OPPO:
        return firstResolvable(context,
          component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
          component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
          component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"));
      default:
        return null;
    }
  }

  /** Vendor-specific battery / background-power page; null = generic settings apply. */
  public static Intent batteryIntent (Context context) {
    int vendor = vendor(context);
    switch (vendor) {
      case VENDOR_XIAOMI:
        return new Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL",
          Uri.parse("package:" + context.getPackageName()));
      case VENDOR_HUAWEI:
        return firstResolvable(context,
          component("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"),
          component("com.huawei.systemmanager", "com.huawei.systemmanager.power.HwPowerManagerActivity"));
      case VENDOR_VIVO:
        return firstResolvable(context,
          component("com.vivo.abe", "com.vivo.abedevkit.ExcessPowerListActivity"),
          component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.SoftPermissionDetailActivity"));
      case VENDOR_OPPO:
        return firstResolvable(context,
          component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
          component("com.coloros.safecenter", "com.coloros.safecenter.permission.singlepage.PermissionDetailActivity"));
      default:
        return null;
    }
  }

  /** Vendor permission-editor page that groups lock-screen / pop-ups / overlay (Xiaomi only today). */
  public static Intent permissionEditorIntent (Context context) {
    if (vendor(context) == VENDOR_XIAOMI) {
      return XiaomiUtilities.getPermissionManagerIntent(context);
    }
    return null;
  }

  /** First candidate that resolves, or the last one so startActivity can fall back itself. */
  private static Intent firstResolvable (Context context, Intent... candidates) {
    Intent last = null;
    for (Intent intent : candidates) {
      last = intent;
      try {
        if (context.getPackageManager().resolveActivity(intent, 0) != null) {
          return intent;
        }
      } catch (Throwable ignored) {
      }
    }
    return last;
  }

  private static Intent component (String packageName, String className) {
    return new Intent().setComponent(new ComponentName(packageName, className));
  }
}
