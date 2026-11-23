package com.ditzzy.dsunext.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.HashSet
import java.util.Locale

open class AppLanguageTest {
    @Test
    open fun idsAreUniqueAndRoundTrip() {
        val seen: MutableSet<Int> = HashSet<Int>()
        for (language in AppLanguage.values()) {
            assertTrue(("duplicate id " + language.getId()), seen.add(language.getId()))
            assertEquals(language, AppLanguage.fromId(language.getId()))
        }
    }

    @Test
    open fun persistedIdsStayStable() {
        assertEquals(0, AppLanguage.SYSTEM.getId())
        assertEquals(1, AppLanguage.ENGLISH.getId())
        assertEquals(2, AppLanguage.INDONESIAN.getId())
    }

    @Test
    open fun unknownIdFollowsTheSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromId(-1))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromId(99))
    }

    @Test
    open fun matchesOnLanguageOnly() {
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromLocale(Locale.forLanguageTag("id-ID")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLocale(Locale.forLanguageTag("en-GB")))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLocale(Locale.forLanguageTag("fr-FR")))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLocale(null))
    }

    @Test
    open fun legacyIndonesianCodeIsRecognized() {
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromLanguageCode("in"))
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromLanguageCode("ID"))
    }

    @Test
    open fun resolveTakesTheFirstTranslatedSystemLanguage() {
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.resolve("fr", "in", "en"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve("en", "id"))
    }

    @Test
    open fun resolveFallsBackToEnglish() {
        assertEquals(AppLanguage.FALLBACK, AppLanguage.resolve("fr", "de"))
        assertEquals(AppLanguage.FALLBACK, AppLanguage.resolve())
    }

    @Test
    open fun systemHasNoTag() {
        assertTrue(AppLanguage.SYSTEM.isSystem())
        assertEquals("", AppLanguage.SYSTEM.getTag())
    }
}
