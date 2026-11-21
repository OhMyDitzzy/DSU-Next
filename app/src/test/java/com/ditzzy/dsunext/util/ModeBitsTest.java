package com.ditzzy.dsunext.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ModeBitsTest {
    @Test public void ownerFullAndGroupReadMakes0740() {
        int mode = 0;
        mode = ModeBits.with(mode, ModeBits.OWNER, ModeBits.READ, true);
        mode = ModeBits.with(mode, ModeBits.OWNER, ModeBits.WRITE, true);
        mode = ModeBits.with(mode, ModeBits.OWNER, ModeBits.EXECUTE, true);
        mode = ModeBits.with(mode, ModeBits.GROUP, ModeBits.READ, true);
        assertEquals("0740", ModeBits.octal(mode));
        assertEquals("rwxr-----", ModeBits.symbolic(mode));
    }

    @Test public void specialBitsAreIndependentOfTheNineBasicBits() {
        int mode = ModeBits.withSpecial(0755, ModeBits.SETUID | ModeBits.STICKY, true);
        assertEquals("5755", ModeBits.octal(mode));
        assertEquals("rwsr-xr-t", ModeBits.symbolic(mode));
        assertEquals(0755, ModeBits.withSpecial(mode, ModeBits.SETUID | ModeBits.STICKY, false));
    }

    @Test public void symbolicMarksSpecialBitsWithoutExecuteInCapitals() {
        assertEquals("rw-r-Sr-T", ModeBits.symbolic(03644));
    }

    @Test public void parseAcceptsThreeOrFourOctalDigitsOnly() {
        assertEquals(0644, ModeBits.parse("644"));
        assertEquals(04755, ModeBits.parse("4755"));
        assertEquals(-1, ModeBits.parse("0888"));
        assertEquals(-1, ModeBits.parse("64"));
        assertEquals(-1, ModeBits.parse("07777"));
    }
}
