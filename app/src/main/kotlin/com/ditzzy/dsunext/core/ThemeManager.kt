package com.ditzzy.dsunext.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.ditzzy.dsunext.model.ColorPalette
import com.ditzzy.dsunext.model.ThemeMode
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import java.util.ArrayList

class ThemeManager : Application.ActivityLifecycleCallbacks {
    private val prefs: AppPrefs

    private val activities: MutableList<Activity> = ArrayList<Activity>()

    constructor(prefs: AppPrefs) {
        this.prefs = prefs
    }

    /** Has to run in every process, before the first activity is created. */
    fun install(application: Application) {
        AppCompatDelegate.setDefaultNightMode(getThemeMode().getNightMode())
        application.registerActivityLifecycleCallbacks(this)
    }

    fun getThemeMode(): ThemeMode {
        return ThemeMode.fromId(prefs.getInt(AppPrefs.THEME_MODE))
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.setInt(AppPrefs.THEME_MODE, mode.getId())
        AppCompatDelegate.setDefaultNightMode(mode.getNightMode())
    }

    fun getColorPalette(): ColorPalette {
        return ColorPalette.fromId(prefs.getInt(AppPrefs.COLOR_PALETTE))
    }

    fun setColorPalette(palette: ColorPalette) {
        prefs.setInt(AppPrefs.COLOR_PALETTE, palette.getId())
        recreateActivities()
    }

    fun isDynamicColorEnabled(): Boolean {
        return (isDynamicColorSupported() && prefs.getBoolean(AppPrefs.DYNAMIC_COLOR))
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        prefs.setBoolean(AppPrefs.DYNAMIC_COLOR, enabled)
        recreateActivities()
    }

    private fun recreateActivities() {
        for (activity in ArrayList(activities)) {
            if ((!activity.isFinishing() && !activity.isDestroyed())) {
                activity.recreate()
            }
        }
    }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (isDynamicColorEnabled()) {
            DynamicColors.applyToActivityIfAvailable(activity, DynamicColorsOptions.Builder().setThemeOverlay(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight).build())
            return
        }
        val overlay: Int = getColorPalette().getOverlay()
        if ((overlay != 0)) {
            activity.getTheme().applyStyle(overlay, true)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activities.add(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        activities.remove(activity)
    }

    override fun onActivityStarted(activity: Activity) {

    }

    override fun onActivityResumed(activity: Activity) {

    }

    override fun onActivityPaused(activity: Activity) {

    }

    override fun onActivityStopped(activity: Activity) {

    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {

    }

    companion object {
        /** Dynamic color needs Android 12 or newer. */
        @JvmStatic
        fun isDynamicColorSupported(): Boolean {
            return DynamicColors.isDynamicColorAvailable()
        }
    }
}
