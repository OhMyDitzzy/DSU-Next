package com.ditzzy.dsunext.model;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatDelegate;

import com.ditzzy.dsunext.R;

public enum ThemeMode {
    SYSTEM(0, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.string.theme_system),
    LIGHT(1, AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_light),
    DARK(2, AppCompatDelegate.MODE_NIGHT_YES, R.string.theme_dark);

    private final int id;
    private final int nightMode;
    private final int label;

    ThemeMode(int id, int nightMode, @StringRes int label) {
        this.id = id;
        this.nightMode = nightMode;
        this.label = label;
    }

    /** Value persisted in preferences, never change an existing id. */
    public int getId() {
        return id;
    }

    /** One of the {@code AppCompatDelegate.MODE_NIGHT_*} constants. */
    public int getNightMode() {
        return nightMode;
    }

    @StringRes
    public int getLabel() {
        return label;
    }

    public static ThemeMode fromId(int id) {
        for (ThemeMode mode : values()) {
            if (mode.id == id) {
                return mode;
            }
        }
        return SYSTEM;
    }
}
