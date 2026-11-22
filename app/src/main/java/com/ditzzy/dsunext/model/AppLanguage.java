package com.ditzzy.dsunext.model;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.os.LocaleListCompat;

import com.ditzzy.dsunext.R;

import java.util.Locale;

/**
 * Languages the interface is translated into, plus the "follow the system" choice.
 * To add a language, see the translation section of CONTRIBUTING.md.
 */
public enum AppLanguage {
    SYSTEM(0, "", R.string.language_system, 0),
    ENGLISH(1, "en", R.string.language_english, R.string.language_english_name),
    INDONESIAN(2, "id", R.string.language_indonesian, R.string.language_indonesian_name);

    /** Used when the system language is not one of the translated ones. */
    public static final AppLanguage FALLBACK = ENGLISH;

    private final int id;
    private final String tag;
    private final int label;
    private final int localizedName;

    AppLanguage(int id, String tag, @StringRes int label, @StringRes int localizedName) {
        this.id = id;
        this.tag = tag;
        this.label = label;
        this.localizedName = localizedName;
    }

    /** Value persisted in preferences, never change an existing id. */
    public int getId() {
        return id;
    }

    /** BCP 47 language tag, empty for {@link #SYSTEM}. */
    @NonNull
    public String getTag() {
        return tag;
    }

    public boolean isSystem() {
        return this == SYSTEM;
    }

    /** The language written in itself ("Bahasa Indonesia"), or "Follow system". */
    @StringRes
    public int getLabel() {
        return label;
    }

    /** The language written in the current interface language, 0 for {@link #SYSTEM}. */
    @StringRes
    public int getLocalizedName() {
        return localizedName;
    }

    /** Locales to hand to AppCompat, empty means "follow the system". */
    @NonNull
    public LocaleListCompat toLocales() {
        return isSystem() ? LocaleListCompat.getEmptyLocaleList() : LocaleListCompat.forLanguageTags(tag);
    }

    public static AppLanguage fromId(int id) {
        for (AppLanguage language : values()) {
            if (language.id == id) {
                return language;
            }
        }
        return SYSTEM;
    }

    /** Matches a locale by its language only, region and script are ignored. */
    public static AppLanguage fromLocale(Locale locale) {
        return locale == null ? SYSTEM : fromLanguageCode(locale.getLanguage());
    }

    /** Returns {@link #SYSTEM} when the code is not a translated language. */
    public static AppLanguage fromLanguageCode(String code) {
        String normalized = normalize(code);
        for (AppLanguage language : values()) {
            if (!language.isSystem() && language.tag.equals(normalized)) {
                return language;
            }
        }
        return SYSTEM;
    }

    /**
     * Picks the language the app shows when it follows the system: the first entry of the
     * system language list that is translated, otherwise {@link #FALLBACK}.
     */
    public static AppLanguage resolve(String... systemLanguageCodes) {
        for (String code : systemLanguageCodes) {
            AppLanguage match = fromLanguageCode(code);
            if (!match.isSystem()) {
                return match;
            }
        }
        return FALLBACK;
    }

    /** Java still reports Indonesian as the legacy "in" code on some versions. */
    private static String normalize(String code) {
        if (code == null) {
            return "";
        }
        String lower = code.toLowerCase(Locale.ROOT);
        return "in".equals(lower) ? "id" : lower;
    }
}
