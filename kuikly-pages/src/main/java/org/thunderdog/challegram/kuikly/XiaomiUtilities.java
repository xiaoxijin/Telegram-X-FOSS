/*
 * This file is a part of Telegram X (FOSS fork).
 *
 * Ported from the source fork (org.telegram.messenger.XiaomiUtilities),
 * PORTING_FEATURES.md §E1/E4: MIUI/HyperOS custom permission checks and the
 * private permission-editor routing (official Telegram is on Xiaomi's allow
 * list; sideloads are not).
 */
package org.thunderdog.challegram.kuikly;

import android.annotation.TargetApi;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import java.lang.reflect.Method;

public class XiaomiUtilities {

  // custom permissions
  public static final int OP_AUTO_START = 10008;
  public static final int OP_BACKGROUND_START_ACTIVITY = 10021;
  public static final int OP_SHOW_WHEN_LOCKED = 10020;

  public static boolean isMIUI (Context context) {
    return !TextUtils.isEmpty(getSystemProperty("ro.miui.ui.version.name"));
  }

  @SuppressWarnings("JavaReflectionMemberAccess")
  @TargetApi(19)
  public static boolean isCustomPermissionGranted (Context context, int permission) {
    try {
      AppOpsManager mgr = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
      Method m = AppOpsManager.class.getMethod("checkOpNoThrow", int.class, int.class, String.class);
      int result = (int) m.invoke(mgr, permission, android.os.Process.myUid(), context.getPackageName());
      return result == AppOpsManager.MODE_ALLOWED;
    } catch (Exception x) {
      // unknown op on this ROM: assume granted, so the page never nags spuriously
    }
    return true;
  }

  public static Intent getPermissionManagerIntent (Context context) {
    Intent intent = new Intent("miui.intent.action.APP_PERM_EDITOR");
    intent.setPackage("com.miui.securitycenter");
    intent.putExtra("extra_package_uid", android.os.Process.myUid());
    intent.putExtra("extra_pkgname", context.getPackageName());
    return intent;
  }

  @SuppressWarnings("JavaReflectionMemberAccess")
  public static String getSystemProperty (String key) {
    try {
      Class<?> systemProperties = Class.forName("android.os.SystemProperties");
      Method get = systemProperties.getMethod("get", String.class);
      return (String) get.invoke(null, key);
    } catch (Exception e) {
      return null;
    }
  }
}
