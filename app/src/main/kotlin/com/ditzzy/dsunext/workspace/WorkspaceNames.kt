package com.ditzzy.dsunext.workspace

import java.util.Locale
import java.util.regex.Pattern

class WorkspaceNames {
    private constructor() {

    }

    companion object {
        const val MAX_LENGTH: Int = 64

        private val NAME: Pattern = Pattern.compile((("^[A-Za-z0-9][A-Za-z0-9._-]{0," + (MAX_LENGTH - 1)) + "}\$"))

        private val SIZE: Pattern = Pattern.compile("^(auto|[0-9]{1,15}[KMG]?)\$", Pattern.CASE_INSENSITIVE)

        private val STRIPPED_SUFFIXES: Array<String> = arrayOf(".tar", ".tgz", ".txz", ".gz", ".gzip", ".xz", ".zip", ".7z", ".img")

        @JvmStatic
        fun isValid(name: String?): Boolean {
            return ((name != null) && NAME.matcher(name).matches())
        }

        /** Accepts "auto" or a byte count with an optional K, M or G suffix, like Yuki does. */
        @JvmStatic
        fun isValidSize(size: String?): Boolean {
            return ((size != null) && SIZE.matcher(size).matches())
        }

        /** A valid workspace name derived from a file name: "aosp-arm64.img.xz" gives "aosp-arm64". */
        @JvmStatic
        fun suggest(fileName: String?): String {
            var base: String = (if ((fileName == null)) "" else fileName!!.trim())
            var stripped: Boolean = true
            while (stripped) {
                stripped = false
                val lower: String = base.lowercase(Locale.ROOT)
                for (suffix in STRIPPED_SUFFIXES) {
                    if ((lower.endsWith(suffix) && (base.length > suffix.length))) {
                        base = base.substring(0, (base.length - suffix.length))
                        stripped = true
                        break
                    }
                }
            }
            var clean: String = base.replace(Regex("[^A-Za-z0-9._-]"), "_").replace(Regex("^[^A-Za-z0-9]+"), "")
            if ((clean.length > MAX_LENGTH)) {
                clean = clean.substring(0, MAX_LENGTH)
            }
            return (if (clean.isEmpty()) "gsi" else clean)
        }

        /** Adds "-2", "-3"... until the name is not in `taken`. */
        @JvmStatic
        fun unique(base: String, taken: Collection<String>): String {
            if (!taken.contains(base)) {
                return base
            }
            run {
                var i: Int = 2
                while (true) {
                    val suffix: String = ("-" + i)
                    val head: String = (if (((base.length + suffix.length) > MAX_LENGTH)) base.substring(0, (MAX_LENGTH - suffix.length)) else base)
                    val candidate: String = (head + suffix)
                    if (!taken.contains(candidate)) {
                        return candidate
                    }
                    i++
                }
            }
        }
    }
}
