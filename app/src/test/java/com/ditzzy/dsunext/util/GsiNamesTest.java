package com.ditzzy.dsunext.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class GsiNamesTest {

    @Test public void sanitizeCollapsesWhitespaceAndTrims() {
        assertEquals("LineageOS 21 vanilla", GsiNames.sanitizeLabel("  LineageOS \t 21\n vanilla  "));
    }

    @Test public void sanitizeDropsControlCharactersAndHandlesNull() {
        assertEquals("ab", GsiNames.sanitizeLabel("a\u0000b"));
        assertEquals("", GsiNames.sanitizeLabel(null));
        assertEquals("", GsiNames.sanitizeLabel("   "));
    }

    @Test public void sanitizeCapsTheLength() {
        String longName = String.join("", Collections.nCopies(100, "x"));
        assertEquals(GsiNames.MAX_LABEL_LENGTH, GsiNames.sanitizeLabel(longName).length());
    }

    @Test public void labelFromFileNameDropsImageExtensions() {
        assertEquals("crdroid-11", GsiNames.labelFromFileName("crdroid-11.img.xz"));
        assertEquals("system-arm64", GsiNames.labelFromFileName("system-arm64.IMG"));
        assertEquals("package", GsiNames.labelFromFileName("package.zip"));
        assertEquals("notes.txt", GsiNames.labelFromFileName("notes.txt"));
        assertEquals("", GsiNames.labelFromFileName(null));
    }

    @Test public void labelFromFileNameKeepsNameThatIsOnlyAnExtension() {
        assertEquals(".img", GsiNames.labelFromFileName(".img"));
    }

    @Test public void slotIdIsStableAndAvoidsCollisions() {
        String first = GsiNames.newSlotId(1_000_000L, Collections.emptyList());
        assertEquals("dsu_" + Long.toString(1_000_000L, 36), first);
        String second = GsiNames.newSlotId(1_000_000L, Arrays.asList(first));
        assertNotEquals(first, second);
        assertTrue(GsiNames.isValidSlotId(second));
    }

    @Test public void slotIdValidation() {
        assertTrue(GsiNames.isValidSlotId("dsu"));
        assertTrue(GsiNames.isValidSlotId("dsu_ab12"));
        assertFalse(GsiNames.isValidSlotId(""));
        assertFalse(GsiNames.isValidSlotId("../etc"));
        assertFalse(GsiNames.isValidSlotId("a b"));
        assertFalse(GsiNames.isValidSlotId(null));
    }
}
