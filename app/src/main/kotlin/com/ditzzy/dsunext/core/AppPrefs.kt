package com.ditzzy.dsunext.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around SharedPreferences used for every persisted setting.
 * Existing keys and value types are retained so the Kotlin migration preserves saved settings.
 */
class AppPrefs {
    private val preferences: SharedPreferences

    constructor(context: Context) {
        this.preferences = context.getSharedPreferences(USER_PREFERENCES, Context.MODE_PRIVATE)
    }

    fun getBoolean(key: String): Boolean {
        return preferences.getBoolean(key, false)
    }

    fun setBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    fun getString(key: String): String {
        return preferences.getString(key, "")!!
    }

    fun setString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    fun getInt(key: String): Int {
        return preferences.getInt(key, 0)
    }

    fun setInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }

    fun isUserAgreementAccepted(): Boolean {
        return (getInt(USER_AGREEMENT_VERSION) >= CURRENT_USER_AGREEMENT_VERSION)
    }

    fun acceptUserAgreement() {
        setInt(USER_AGREEMENT_VERSION, CURRENT_USER_AGREEMENT_VERSION)
    }

    companion object {
        const val USER_PREFERENCES: String = "user_preferences"

        const val USER_AGREEMENT_VERSION: String = "user_agreement_version"

        const val SAF_PATH: String = "writable_path"

        const val DEVELOPER_OPTIONS: String = "developer_options"

        const val USE_BUILTIN_INSTALLER: String = "builtin_installer"

        const val MULTI_GSI: String = "multi_gsi"

        const val KEEP_SCREEN_ON: String = "keep_screen_on"

        const val UMOUNT_SD: String = "umount_sd"

        const val DISABLE_STORAGE_CHECK: String = "disable_storage_check"

        const val FULL_LOGCAT_LOGGING: String = "full_logcat_logging"

        const val THEME_MODE: String = "theme_mode"

        const val COLOR_PALETTE: String = "color_palette"

        const val DYNAMIC_COLOR: String = "dynamic_color"

        const val LANGUAGE: String = "language"

        /** Bumped whenever the agreement text changes in a way that needs to be accepted again. */
        const val CURRENT_USER_AGREEMENT_VERSION: Int = 1
    }
}
