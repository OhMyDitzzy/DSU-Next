package com.ditzzy.dsunext.core

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.ditzzy.dsunext.model.AppLanguage

/**
 * Per-app language. Android 13 and newer keep the choice in the system (it is also shown in the
 * system "App languages" screen), older versions keep it in [AppPrefs] and hand it to
 * AppCompat on every start.
 */
class LanguageManager {
    private val application: Application

    private val prefs: AppPrefs

    constructor(application: Application, prefs: AppPrefs) {
        this.application = application
        this.prefs = prefs
    }

    /** Has to run in every process, before the first activity is created. */
    fun install() {
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)) {
            return
        }
        val stored: AppLanguage = AppLanguage.fromId(prefs.getInt(AppPrefs.LANGUAGE))
        if (!stored.isSystem()) {
            AppCompatDelegate.setApplicationLocales(stored.toLocales())
        }
        syncApplicationResources(stored)
    }

    fun getLanguage(): AppLanguage {
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)) {
            val current: LocaleListCompat = AppCompatDelegate.getApplicationLocales()
            return (if (current.isEmpty()) AppLanguage.SYSTEM else AppLanguage.fromLocale(current.`get`(0)))
        }
        return AppLanguage.fromId(prefs.getInt(AppPrefs.LANGUAGE))
    }

    /** AppCompat recreates the visible activities when the effective language changes. */
    fun setLanguage(language: AppLanguage) {
        prefs.setInt(AppPrefs.LANGUAGE, language.getId())
        syncApplicationResources(language)
        AppCompatDelegate.setApplicationLocales(language.toLocales())
    }

    /** The language the app shows while it follows the system. */
    fun getSystemLanguage(): AppLanguage {
        val locales: LocaleList = Resources.getSystem().getConfiguration().getLocales()
        val codes = Array(locales.size()) { locales.get(it).language }
        return AppLanguage.resolve(*codes)
    }

    /** Called when the system configuration changed, which resets the application resources. */
    fun onSystemConfigurationChanged() {
        syncApplicationResources(getLanguage())
    }

    /**
     * Before Android 13 AppCompat only localizes activity contexts. View models read strings from
     * the application context, so it gets the same locale here.
     */
    @SuppressWarnings("deprecation")
    private fun syncApplicationResources(language: AppLanguage) {
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)) {
            return
        }
        val resources: Resources = application.getResources()
        val configuration: Configuration = Configuration(resources.getConfiguration())
        val target: LocaleList = (if (language.isSystem()) Resources.getSystem().getConfiguration().getLocales() else LocaleList.forLanguageTags(language.getTag()))
        configuration.setLocales(target)
        resources.updateConfiguration(configuration, resources.getDisplayMetrics())
    }
}
