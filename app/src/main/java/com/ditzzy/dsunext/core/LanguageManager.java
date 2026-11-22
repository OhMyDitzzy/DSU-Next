package com.ditzzy.dsunext.core;

import android.app.Application;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.ditzzy.dsunext.model.AppLanguage;

/**
 * Per-app language. Android 13 and newer keep the choice in the system (it is also shown in the
 * system "App languages" screen), older versions keep it in {@link AppPrefs} and hand it to
 * AppCompat on every start.
 */
public final class LanguageManager {

    private final Application application;
    private final AppPrefs prefs;

    public LanguageManager(Application application, AppPrefs prefs) {
        this.application = application;
        this.prefs = prefs;
    }

    /** Has to run in every process, before the first activity is created. */
    public void install() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        AppLanguage stored = AppLanguage.fromId(prefs.getInt(AppPrefs.LANGUAGE));
        if (!stored.isSystem()) {
            AppCompatDelegate.setApplicationLocales(stored.toLocales());
        }
        syncApplicationResources(stored);
    }

    public AppLanguage getLanguage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
            return current.isEmpty() ? AppLanguage.SYSTEM : AppLanguage.fromLocale(current.get(0));
        }
        return AppLanguage.fromId(prefs.getInt(AppPrefs.LANGUAGE));
    }

    /** AppCompat recreates the visible activities when the effective language changes. */
    public void setLanguage(AppLanguage language) {
        prefs.setInt(AppPrefs.LANGUAGE, language.getId());
        // Before the activities are recreated, so strings read from the application context match
        syncApplicationResources(language);
        AppCompatDelegate.setApplicationLocales(language.toLocales());
    }

    /** The language the app shows while it follows the system. */
    public AppLanguage getSystemLanguage() {
        LocaleList locales = Resources.getSystem().getConfiguration().getLocales();
        String[] codes = new String[locales.size()];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = locales.get(i).getLanguage();
        }
        return AppLanguage.resolve(codes);
    }

    /** Called when the system configuration changed, which resets the application resources. */
    public void onSystemConfigurationChanged() {
        syncApplicationResources(getLanguage());
    }

    /**
     * Before Android 13 AppCompat only localizes activity contexts. View models read strings from
     * the application context, so it gets the same locale here.
     */
    @SuppressWarnings("deprecation")
    private void syncApplicationResources(AppLanguage language) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        Resources resources = application.getResources();
        Configuration configuration = new Configuration(resources.getConfiguration());
        LocaleList target = language.isSystem()
                ? Resources.getSystem().getConfiguration().getLocales()
                : LocaleList.forLanguageTags(language.getTag());
        configuration.setLocales(target);
        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
    }
}
