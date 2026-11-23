package com.ditzzy.dsunext.model

import androidx.annotation.StringRes
import androidx.core.os.LocaleListCompat
import com.ditzzy.dsunext.R
import java.util.Locale

/**
 * Languages the interface is translated into, plus the "follow the system" choice.
 * To add a language, see the translation section of CONTRIBUTING.md.
 */
enum class AppLanguage(private val id: Int, private val tag: String, private val label: Int, private val localizedName: Int) {
    SYSTEM(0, "", R.string.language_system, 0),
    ENGLISH(1, "en", R.string.language_english, R.string.language_english_name),
    INDONESIAN(2, "id", R.string.language_indonesian, R.string.language_indonesian_name);

    /** Value persisted in preferences, never change an existing id. */
    fun getId(): Int {
        return id
    }

    /** BCP 47 language tag, empty for [#SYSTEM]. */
    fun getTag(): String {
        return tag
    }

    fun isSystem(): Boolean {
        return (this == SYSTEM)
    }

    /** The language written in itself ("Bahasa Indonesia"), or "Follow system". */
    @StringRes
    fun getLabel(): Int {
        return label
    }

    /** The language written in the current interface language, 0 for [#SYSTEM]. */
    @StringRes
    fun getLocalizedName(): Int {
        return localizedName
    }

    /** Locales to hand to AppCompat, empty means "follow the system". */
    fun toLocales(): LocaleListCompat {
        return (if (isSystem()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
    }

    companion object {
        /** Used when the system language is not one of the translated ones. */
        val FALLBACK: AppLanguage = ENGLISH

        @JvmStatic
        fun fromId(id: Int): AppLanguage {
            for (language in values()) {
                if ((language.id == id)) {
                    return language
                }
            }
            return SYSTEM
        }

        /** Matches a locale by its language only, region and script are ignored. */
        @JvmStatic
        fun fromLocale(locale: Locale?): AppLanguage {
            return (if ((locale == null)) SYSTEM else fromLanguageCode(locale!!.getLanguage()))
        }

        /** Returns [#SYSTEM] when the code is not a translated language. */
        @JvmStatic
        fun fromLanguageCode(code: String?): AppLanguage {
            val normalized: String = normalize(code)
            for (language in values()) {
                if ((!language.isSystem() && language.tag.equals(normalized))) {
                    return language
                }
            }
            return SYSTEM
        }

        /**
         * Picks the language the app shows when it follows the system: the first entry of the
         * system language list that is translated, otherwise [#FALLBACK].
         */
        @JvmStatic
        fun resolve(vararg systemLanguageCodes: String?): AppLanguage {
            for (code in systemLanguageCodes) {
                val match: AppLanguage = fromLanguageCode(code)
                if (!match.isSystem()) {
                    return match
                }
            }
            return FALLBACK
        }

        /** Java still reports Indonesian as the legacy "in" code on some versions. */
        private fun normalize(code: String?): String {
            if ((code == null)) {
                return ""
            }
            val lower: String = code!!.lowercase(Locale.ROOT)
            return (if ("in".equals(lower)) "id" else lower)
        }
    }
}
