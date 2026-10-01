/*
 * Entry points for features that only exist on newer Android versions.
 *
 * The classes below are kept in their own files/classes on purpose: a device that does not have
 * UiModeManager.setApplicationNightMode (Android 12) or LocaleManager (Android 13) must never have to
 * load them, otherwise the verifier would reject the whole patch on that device.
 */
package com.appcloner.runtime;

import android.app.UiModeManager;
import android.content.Context;

final class ApiGates {

    private static boolean darkWanted;

    private ApiGates() {
    }

    static void setDarkWanted(boolean value) {
        darkWanted = value;
    }

    /** Forces the clone into dark mode (Android 12+; older versions have no per-app dark mode). */
    static void applyDarkMode(Context context) {
        if (!darkWanted || context == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            DarkModeApi31.apply(context);
        }
    }

    /** Sets the clone's own display language (Android 13+; per-app languages did not exist before). */
    static void applyLanguage(Context context, String languageTag) {
        if (context == null || languageTag == null || languageTag.length() == 0) return;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            LanguageApi33.apply(context, languageTag);
        }
    }

    static boolean isDarkModeSupported() {
        return android.os.Build.VERSION.SDK_INT >= 31;
    }

    static boolean isLanguageSupported() {
        return android.os.Build.VERSION.SDK_INT >= 33;
    }
}

/** Android 12 (API 31) and newer: per-app night mode. */
final class DarkModeApi31 {

    private DarkModeApi31() {
    }

    static void apply(Context context) {
        try {
            UiModeManager manager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
            if (manager != null) manager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES);
        } catch (Throwable ignored) {
        }
    }
}

/** Android 13 (API 33) and newer: per-app display language. */
final class LanguageApi33 {

    private LanguageApi33() {
    }

    static void apply(Context context, String languageTag) {
        try {
            android.app.LocaleManager manager =
                (android.app.LocaleManager) context.getSystemService(Context.LOCALE_SERVICE);
            if (manager != null) {
                manager.setApplicationLocales(android.os.LocaleList.forLanguageTags(languageTag));
            }
        } catch (Throwable ignored) {
        }
    }
}
