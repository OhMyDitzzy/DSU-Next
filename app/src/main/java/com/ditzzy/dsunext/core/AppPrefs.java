package com.ditzzy.dsunext.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Thin wrapper around SharedPreferences used for every persisted setting.
 * SharedPreferences is used here since DataStore needs a coroutine or RxJava/Guava bridge
 * to be consumed from Java.
 */
public final class AppPrefs {

    public static final String USER_PREFERENCES = "user_preferences";
    public static final String USER_AGREEMENT_VERSION = "user_agreement_version";
    public static final String SAF_PATH = "writable_path";
    public static final String DEVELOPER_OPTIONS = "developer_options";
    public static final String USE_BUILTIN_INSTALLER = "builtin_installer";
    public static final String KEEP_SCREEN_ON = "keep_screen_on";
    public static final String UMOUNT_SD = "umount_sd";
    public static final String DISABLE_STORAGE_CHECK = "disable_storage_check";
    public static final String FULL_LOGCAT_LOGGING = "full_logcat_logging";
    public static final String THEME_MODE = "theme_mode";
    public static final String COLOR_PALETTE = "color_palette";
    public static final String DYNAMIC_COLOR = "dynamic_color";

    /** Bumped whenever the agreement text changes in a way that needs to be accepted again. */
    public static final int CURRENT_USER_AGREEMENT_VERSION = 1;

    private final SharedPreferences preferences;

    public AppPrefs(Context context) {
        this.preferences = context.getSharedPreferences(USER_PREFERENCES, Context.MODE_PRIVATE);
    }

    public boolean getBoolean(String key) {
        return preferences.getBoolean(key, false);
    }

    public void setBoolean(String key, boolean value) {
        preferences.edit().putBoolean(key, value).apply();
    }

    public String getString(String key) {
        return preferences.getString(key, "");
    }

    public void setString(String key, String value) {
        preferences.edit().putString(key, value).apply();
    }
    
    public int getInt(String key) {
        return preferences.getInt(key, 0);
    }

    public void setInt(String key, int value) {
        preferences.edit().putInt(key, value).apply();
    }

    public boolean isUserAgreementAccepted() {
        return getInt(USER_AGREEMENT_VERSION) >= CURRENT_USER_AGREEMENT_VERSION;
    }

    public void acceptUserAgreement() {
        setInt(USER_AGREEMENT_VERSION, CURRENT_USER_AGREEMENT_VERSION);
    }
}
