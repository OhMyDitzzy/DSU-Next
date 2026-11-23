package com.ditzzy.dsunext.util

import org.junit.Assert.assertEquals
import org.junit.Test

open class ModeBitsTest {
    @Test
    open fun ownerFullAndGroupReadMakes0740() {
        var mode: Int = 0
        mode = ModeBits.with(mode, ModeBits.OWNER, ModeBits.READ, true)
        mode = ModeBits.with(mode, ModeBits.OWNER, ModeBits.WRITE, true)
        mode = ModeBits.with(mode, ModeBits.OWNER, ModeBits.EXECUTE, true)
        mode = ModeBits.with(mode, ModeBits.GROUP, ModeBits.READ, true)
        assertEquals("0740", ModeBits.octal(mode))
        assertEquals("rwxr-----", ModeBits.symbolic(mode))
    }

    @Test
    open fun specialBitsAreIndependentOfTheNineBasicBits() {
        val mode: Int = ModeBits.withSpecial(493, (ModeBits.SETUID or ModeBits.STICKY), true)
        assertEquals("5755", ModeBits.octal(mode))
        assertEquals("rwsr-xr-t", ModeBits.symbolic(mode))
        assertEquals(493, ModeBits.withSpecial(mode, (ModeBits.SETUID or ModeBits.STICKY), false))
    }

    @Test
    open fun symbolicMarksSpecialBitsWithoutExecuteInCapitals() {
        assertEquals("rw-r-Sr-T", ModeBits.symbolic(1956))
    }

    @Test
    open fun parseAcceptsThreeOrFourOctalDigitsOnly() {
        assertEquals(420, ModeBits.parse("644"))
        assertEquals(2541, ModeBits.parse("4755"))
        assertEquals(-1, ModeBits.parse("0888"))
        assertEquals(-1, ModeBits.parse("64"))
        assertEquals(-1, ModeBits.parse("07777"))
    }
}
