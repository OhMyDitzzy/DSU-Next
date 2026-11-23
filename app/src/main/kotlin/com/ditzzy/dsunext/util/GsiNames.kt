package com.ditzzy.dsunext.util

import java.util.Locale

/**
 * Pure helpers for the names used by Multi-GSI: the label the user sees and the slot id that
 * gsid stores the installation under. Kept free of Android classes so they can be unit tested.
 */
class GsiNames {
    private constructor() {

    }

    companion object {
        /** The slot used by a plain (single GSI) installation, and by every DSU Next before Multi-GSI. */
        const val DEFAULT_SLOT: String = "dsu"

        const val MAX_LABEL_LENGTH: Int = 40

        private const val SLOT_PREFIX: String = "dsu_"

        private val IMAGE_SUFFIXES: Array<String> = arrayOf(".img.gz", ".img.xz", ".img.gzip", ".img", ".gzip", ".gz", ".xz", ".zip")

        /** Trims, collapses whitespace and drops control characters. Never returns null. */
        @JvmStatic
        fun sanitizeLabel(input: String?): String {
            if ((input == null)) {
                return ""
            }
            val `out`: StringBuilder = StringBuilder()
            var pendingSpace: Boolean = false
            run {
                var i: Int = 0
                while ((i < input!!.length)) {
                    val c: Char = input!![i]
                    if (java.lang.Character.isWhitespace(c)) {
                        pendingSpace = (`out`.length > 0)
                    } else if (!java.lang.Character.isISOControl(c)) {
                        if (pendingSpace) {
                            `out`.append(' ')
                            pendingSpace = false
                        }
                        `out`.append(c)
                    }
                    i++
                }
            }
            val label: String = `out`.toString()
            return (if ((label.length > MAX_LABEL_LENGTH)) label.substring(0, MAX_LABEL_LENGTH).trim() else label)
        }

        /** The picked file name without its image or archive extension, used when no name is typed. */
        @JvmStatic
        fun labelFromFileName(fileName: String?): String {
            if ((fileName == null)) {
                return ""
            }
            var name: String = fileName!!.trim()
            val lower: String = name.lowercase(Locale.ROOT)
            for (suffix in IMAGE_SUFFIXES) {
                if ((lower.endsWith(suffix) && (name.length > suffix.length))) {
                    name = name.substring(0, (name.length - suffix.length))
                    break
                }
            }
            return sanitizeLabel(name)
        }

        /**
         * Slot ids only use [a-z0-9_], which is safe as a directory name on every Android version.
         * The id is derived from the time, and bumped until it does not collide with an existing slot.
         */
        @JvmStatic
        fun newSlotId(epochSeconds: Long, existing: Collection<String>?): String {
            var value: Long = Math.max(epochSeconds, 0L)
            var id: String = (SLOT_PREFIX + java.lang.Long.toString(value, 36))
            while (((existing != null) && existing!!.contains(id))) {
                value++
                id = (SLOT_PREFIX + java.lang.Long.toString(value, 36))
            }
            return id
        }

        @JvmStatic
        fun isValidSlotId(slot: String?): Boolean {
            if ((((slot == null) || slot!!.isEmpty()) || (slot!!.length > 64))) {
                return false
            }
            run {
                var i: Int = 0
                while ((i < slot!!.length)) {
                    val c: Char = slot!![i]
                    val ok: Boolean = ((((((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z'))) || ((c >= '0') && (c <= '9'))) || (c == '_')) || (c == '-'))
                    if (!ok) {
                        return false
                    }
                    i++
                }
            }
            return true
        }
    }
}
