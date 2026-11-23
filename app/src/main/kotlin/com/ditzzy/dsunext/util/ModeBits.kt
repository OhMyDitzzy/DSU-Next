package com.ditzzy.dsunext.util

import java.util.Locale

/** Converts between a POSIX mode and the per-role checkboxes shown in the permissions dialog. */
class ModeBits {
    private constructor() {

    }

    companion object {
        const val READ: Int = 4
        const val WRITE: Int = 2
        const val EXECUTE: Int = 1

        const val OWNER: Int = 6
        const val GROUP: Int = 3
        const val OTHERS: Int = 0

        const val SETUID: Int = 2048
        const val SETGID: Int = 1024
        const val STICKY: Int = 512

        const val MAX: Int = 4095

        @JvmStatic
        fun has(mode: Int, shift: Int, bit: Int): Boolean {
            return (((mode shr shift) and bit) != 0)
        }

        @JvmStatic
        fun with(mode: Int, shift: Int, bit: Int, on: Boolean): Int {
            return (if (on) (mode or (bit shl shift)) else (mode and ((bit shl shift)).inv()))
        }

        @JvmStatic
        fun withSpecial(mode: Int, flag: Int, on: Boolean): Int {
            return (if (on) (mode or flag) else (mode and (flag).inv()))
        }

        @JvmStatic
        fun octal(mode: Int): String {
            return java.lang.String.format(Locale.ROOT, "%04o", (mode and MAX))
        }

        /** Accepts 3 or 4 octal digits; returns -1 when the text is not a valid mode. */
        @JvmStatic
        fun parse(text: CharSequence?): Int {
            val value: String = (if ((text == null)) "" else text!!.toString().trim())
            return (if (value.matches(Regex("[0-7]{3,4}"))) java.lang.Integer.parseInt(value, 8) else -1)
        }

        /** ls-style text such as rwxr-x--- or rwsr-Sr-T. */
        @JvmStatic
        fun symbolic(mode: Int): String {
            var `out`: CharArray = CharArray(9)
            run {
                var i: Int = 0
                while ((i < 3)) {
                    val shift: Int = (6 - (i * 3))
                    `out`[(i * 3)] = (if (has(mode, shift, READ)) 'r' else '-')
                    `out`[((i * 3) + 1)] = (if (has(mode, shift, WRITE)) 'w' else '-')
                    val x: Boolean = has(mode, shift, EXECUTE)
                    val special: Int = (if ((i == 0)) SETUID else (if ((i == 1)) SETGID else STICKY))
                    var exec: Char = (if (x) 'x' else '-')
                    if (((mode and special) != 0)) {
                        exec = (if ((i == 2)) (if (x) 't' else 'T') else (if (x) 's' else 'S'))
                    }
                    `out`[((i * 3) + 2)] = exec
                    i++
                }
            }
            return String(`out`)
        }
    }
}
