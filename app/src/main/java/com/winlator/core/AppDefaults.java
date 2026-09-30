package com.winlator.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

// Constantes que antes vivian en las pantallas de Winlator (MainActivity/SettingsFragment)
public abstract class AppDefaults {
    public static final boolean DEBUG_MODE = false;
    public static final byte CONTAINER_PATTERN_COMPRESSION_LEVEL = 9;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte EDIT_INPUT_CONTROLS_REQUEST_CODE = 3;
    public static final String DEFAULT_WINE_DEBUG_CHANNELS = "warn,err,fixme";
    public static final byte APP_THEME_LIGHT = 0;
    public static final byte APP_THEME_DARK = 1;

    public static void resetPreferenceVersions(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit()
            .putString("box64_version", DefaultVersion.BOX64)
            .remove("current_box64_version")
            .remove("current_graphics_driver")
            .apply();
    }
}
