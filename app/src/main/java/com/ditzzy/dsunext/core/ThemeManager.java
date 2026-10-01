package com.ditzzy.dsunext.core;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import com.ditzzy.dsunext.model.ColorPalette;
import com.ditzzy.dsunext.model.ThemeMode;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

import java.util.ArrayList;
import java.util.List;

public final class ThemeManager implements Application.ActivityLifecycleCallbacks {

    private final AppPrefs prefs;
    private final List<Activity> activities = new ArrayList<>();

    public ThemeManager(AppPrefs prefs) {
        this.prefs = prefs;
    }

    /** Has to run in every process, before the first activity is created. */
    public void install(Application application) {
        AppCompatDelegate.setDefaultNightMode(getThemeMode().getNightMode());
        application.registerActivityLifecycleCallbacks(this);
    }

    public ThemeMode getThemeMode() {
        return ThemeMode.fromId(prefs.getInt(AppPrefs.THEME_MODE));
    }

    public void setThemeMode(ThemeMode mode) {
        prefs.setInt(AppPrefs.THEME_MODE, mode.getId());
        // AppCompat recreates the activities when the effective mode actually changes
        AppCompatDelegate.setDefaultNightMode(mode.getNightMode());
    }

    public ColorPalette getColorPalette() {
        return ColorPalette.fromId(prefs.getInt(AppPrefs.COLOR_PALETTE));
    }

    public void setColorPalette(ColorPalette palette) {
        prefs.setInt(AppPrefs.COLOR_PALETTE, palette.getId());
        recreateActivities();
    }

    /** Dynamic color needs Android 12 or newer. */
    public static boolean isDynamicColorSupported() {
        return DynamicColors.isDynamicColorAvailable();
    }

    public boolean isDynamicColorEnabled() {
        return isDynamicColorSupported() && prefs.getBoolean(AppPrefs.DYNAMIC_COLOR);
    }

    public void setDynamicColorEnabled(boolean enabled) {
        prefs.setBoolean(AppPrefs.DYNAMIC_COLOR, enabled);
        recreateActivities();
    }

    private void recreateActivities() {
        for (Activity activity : new ArrayList<>(activities)) {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                activity.recreate();
            }
        }
    }

    @Override
    public void onActivityPreCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        if (isDynamicColorEnabled()) {
            // The overlay is explicit since AppTheme is a fixed Light/Dark pair, not a DayNight theme
            DynamicColors.applyToActivityIfAvailable(activity, new DynamicColorsOptions.Builder()
                    .setThemeOverlay(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight)
                    .build());
            return;
        }
        int overlay = getColorPalette().getOverlay();
        if (overlay != 0) {
            activity.getTheme().applyStyle(overlay, true);
        }
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        activities.add(activity);
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        activities.remove(activity);
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }
}
