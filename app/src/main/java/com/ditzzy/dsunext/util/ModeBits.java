package com.ditzzy.dsunext.util;

import java.util.Locale;

/** Converts between a POSIX mode and the per-role checkboxes shown in the permissions dialog. */
public final class ModeBits {
    public static final int READ = 4, WRITE = 2, EXECUTE = 1;
    public static final int OWNER = 6, GROUP = 3, OTHERS = 0;
    public static final int SETUID = 04000, SETGID = 02000, STICKY = 01000;
    public static final int MAX = 07777;

    private ModeBits() {}

    public static boolean has(int mode, int shift, int bit) { return ((mode >> shift) & bit) != 0; }

    public static int with(int mode, int shift, int bit, boolean on) {
        return on ? mode | (bit << shift) : mode & ~(bit << shift);
    }

    public static int withSpecial(int mode, int flag, boolean on) { return on ? mode | flag : mode & ~flag; }

    public static String octal(int mode) { return String.format(Locale.ROOT, "%04o", mode & MAX); }

    /** Accepts 3 or 4 octal digits; returns -1 when the text is not a valid mode. */
    public static int parse(CharSequence text) {
        String value = text == null ? "" : text.toString().trim();
        return value.matches("[0-7]{3,4}") ? Integer.parseInt(value, 8) : -1;
    }

    /** ls-style text such as rwxr-x--- or rwsr-Sr-T. */
    public static String symbolic(int mode) {
        char[] out = new char[9];
        for (int i = 0; i < 3; i++) {
            int shift = 6 - i * 3;
            out[i * 3] = has(mode, shift, READ) ? 'r' : '-';
            out[i * 3 + 1] = has(mode, shift, WRITE) ? 'w' : '-';
            boolean x = has(mode, shift, EXECUTE);
            int special = i == 0 ? SETUID : i == 1 ? SETGID : STICKY;
            char exec = x ? 'x' : '-';
            if ((mode & special) != 0) exec = i == 2 ? (x ? 't' : 'T') : (x ? 's' : 'S');
            out[i * 3 + 2] = exec;
        }
        return new String(out);
    }
}
