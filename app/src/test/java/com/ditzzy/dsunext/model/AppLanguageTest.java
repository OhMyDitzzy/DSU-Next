package com.ditzzy.dsunext.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class AppLanguageTest {

    @Test
    public void idsAreUniqueAndRoundTrip() {
        Set<Integer> seen = new HashSet<>();
        for (AppLanguage language : AppLanguage.values()) {
            assertTrue("duplicate id " + language.getId(), seen.add(language.getId()));
            assertEquals(language, AppLanguage.fromId(language.getId()));
        }
    }

    @Test
    public void persistedIdsStayStable() {
        // These are stored in user preferences, changing them resets everybody's choice
        assertEquals(0, AppLanguage.SYSTEM.getId());
        assertEquals(1, AppLanguage.ENGLISH.getId());
        assertEquals(2, AppLanguage.INDONESIAN.getId());
    }

    @Test
    public void unknownIdFollowsTheSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromId(-1));
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromId(99));
    }

    @Test
    public void matchesOnLanguageOnly() {
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromLocale(Locale.forLanguageTag("id-ID")));
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLocale(Locale.forLanguageTag("en-GB")));
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLocale(Locale.forLanguageTag("fr-FR")));
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLocale(null));
    }

    @Test
    public void legacyIndonesianCodeIsRecognized() {
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromLanguageCode("in"));
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromLanguageCode("ID"));
    }

    @Test
    public void resolveTakesTheFirstTranslatedSystemLanguage() {
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.resolve("fr", "in", "en"));
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve("en", "id"));
    }

    @Test
    public void resolveFallsBackToEnglish() {
        assertEquals(AppLanguage.FALLBACK, AppLanguage.resolve("fr", "de"));
        assertEquals(AppLanguage.FALLBACK, AppLanguage.resolve());
    }

    @Test
    public void systemHasNoTag() {
        assertTrue(AppLanguage.SYSTEM.isSystem());
        assertEquals("", AppLanguage.SYSTEM.getTag());
    }
}
