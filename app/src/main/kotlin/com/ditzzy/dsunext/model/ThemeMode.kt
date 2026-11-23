package com.ditzzy.dsunext.model

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.ditzzy.dsunext.R

enum class ThemeMode(private val id: Int, private val nightMode: Int, private val label: Int) {
    SYSTEM(0, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.string.theme_system),
    LIGHT(1, AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_light),
    DARK(2, AppCompatDelegate.MODE_NIGHT_YES, R.string.theme_dark);

    /** Value persisted in preferences, never change an existing id. */
    fun getId(): Int {
        return id
    }

    /** One of the `AppCompatDelegate.MODE_NIGHT_*` constants. */
    fun getNightMode(): Int {
        return nightMode
    }

    @StringRes
    fun getLabel(): Int {
        return label
    }

    companion object {
        @JvmStatic
        fun fromId(id: Int): ThemeMode {
            for (mode in values()) {
                if ((mode.id == id)) {
                    return mode
                }
            }
            return SYSTEM
        }
    }
}
