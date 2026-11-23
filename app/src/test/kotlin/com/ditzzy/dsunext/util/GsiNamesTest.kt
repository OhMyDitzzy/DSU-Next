package com.ditzzy.dsunext.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays
import java.util.Collections

open class GsiNamesTest {
    @Test
    open fun sanitizeCollapsesWhitespaceAndTrims() {
        assertEquals("LineageOS 21 vanilla", GsiNames.sanitizeLabel("  LineageOS \t 21\n vanilla  "))
    }

    @Test
    open fun sanitizeDropsControlCharactersAndHandlesNull() {
        assertEquals("ab", GsiNames.sanitizeLabel("a\u0000b"))
        assertEquals("", GsiNames.sanitizeLabel(null))
        assertEquals("", GsiNames.sanitizeLabel("   "))
    }

    @Test
    open fun sanitizeCapsTheLength() {
        val longName: String = java.lang.String.join("", Collections.nCopies(100, "x"))
        assertEquals(GsiNames.MAX_LABEL_LENGTH, GsiNames.sanitizeLabel(longName).length)
    }

    @Test
    open fun labelFromFileNameDropsImageExtensions() {
        assertEquals("crdroid-11", GsiNames.labelFromFileName("crdroid-11.img.xz"))
        assertEquals("system-arm64", GsiNames.labelFromFileName("system-arm64.IMG"))
        assertEquals("package", GsiNames.labelFromFileName("package.zip"))
        assertEquals("notes.txt", GsiNames.labelFromFileName("notes.txt"))
        assertEquals("", GsiNames.labelFromFileName(null))
    }

    @Test
    open fun labelFromFileNameKeepsNameThatIsOnlyAnExtension() {
        assertEquals(".img", GsiNames.labelFromFileName(".img"))
    }

    @Test
    open fun slotIdIsStableAndAvoidsCollisions() {
        val first: String = GsiNames.newSlotId(1000000L, Collections.emptyList())
        assertEquals(("dsu_" + java.lang.Long.toString(1000000L, 36)), first)
        val second: String = GsiNames.newSlotId(1000000L, Arrays.asList(first))
        assertNotEquals(first, second)
        assertTrue(GsiNames.isValidSlotId(second))
    }

    @Test
    open fun slotIdValidation() {
        assertTrue(GsiNames.isValidSlotId("dsu"))
        assertTrue(GsiNames.isValidSlotId("dsu_ab12"))
        assertFalse(GsiNames.isValidSlotId(""))
        assertFalse(GsiNames.isValidSlotId("../etc"))
        assertFalse(GsiNames.isValidSlotId("a b"))
        assertFalse(GsiNames.isValidSlotId(null))
    }
}
