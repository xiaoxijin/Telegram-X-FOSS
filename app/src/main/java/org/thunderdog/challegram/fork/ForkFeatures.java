/*
 * This file is a part of Telegram X (FOSS fork).
 *
 * Fork-wide compile-time feature switches, ported from the source fork's
 * ForkFeatures convention (see PORTING_FEATURES.md §C): upstream code only
 * gets a one-line check at each entry point (marked CUSTOM_HOOK in comments);
 * R8 then strips whatever a switched-off feature leaves unreachable.
 */
package org.thunderdog.challegram.fork;

import org.thunderdog.challegram.BuildConfig;

public final class ForkFeatures {

/**
   * Telegram Passport (sharing identity documents with bots, MRZ scanning).
   * TGX never implemented Passport UI and TDLib-side Passport is data-only,
   * so this gate currently has no compile surface; kept for parity and
   * future native OCR work. Verified N/A (PORTING_FEATURES.md §C).
   */
  public static final boolean PASSPORT = false;

  /**
   * Hide Telegram Premium / Business promotions for non-premium users, the
   * same way the server's premiumLocked flag does for regions where Premium
   * can't be bought. Gifts stay (premiumPurchaseBlocked untouched).
   * CUSTOM_HOOK touch points: premium promo rows in SettingsController /
   * Profile pages, premium hints in TdlibUi/Settings.
   */
  public static final boolean HIDE_PREMIUM = true;

  /**
   * Web links always open in the in-app browser (its WebView goes through the
   * built-in proxy, see {@code org.thunderdog.challegram.fork.WebViewNodeProxy});
   * nothing is handed to outside browsers or apps, which would bypass the
   * proxy. CUSTOM_HOOK touch points: TdlibUi.openUrl / Browser entry points.
   */
  public static final boolean IN_APP_BROWSER_ONLY = true;

  /**
   * Default of "Loop animated stickers": off, stickers in chats play once.
   * Only the default changes: a value the user saved in power-saving /
   * sticker settings is kept.
   */
  public static final boolean LOOP_STICKERS = false;

  /**
   * Language pack id used on first start until the user picks one (§B2/B3),
   * from local.properties {@code custom.default_language}. The pack loads
   * through the regular remote-language-pack flow; empty = TGX default.
   */
  public static final String DEFAULT_LANGUAGE = BuildConfig.CUSTOM_DEFAULT_LANGUAGE;

  private ForkFeatures () {
    throw new UnsupportedOperationException();
  }
}
