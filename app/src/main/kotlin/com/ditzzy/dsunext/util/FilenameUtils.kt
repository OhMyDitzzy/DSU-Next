package com.ditzzy.dsunext.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile

class FilenameUtils {
    private constructor() {

    }

    companion object {
        /** Keeps only the digits of the given text, returns an empty string when there are none. */
        @JvmStatic
        fun getDigits(input: String): String {
            val digits: StringBuilder = StringBuilder()
            run {
                var i: Int = 0
                while ((i < input.length)) {
                    val c: Char = input[i]
                    if (java.lang.Character.isDigit(c)) {
                        digits.append(c)
                    }
                    i++
                }
            }
            return digits.toString()
        }

        /**
         * Tries to convert a DocumentFile uri to a real path, this isn't guaranteed to work
         * with every kind of document provider.
         *
         * @param addQuotes wraps the result in single quotes so it is safe to use in a shell.
         */
        @JvmStatic
        fun getFilePath(uri: Uri, addQuotes: Boolean): String {
            val input: String = java.lang.String.valueOf(uri.getPath())
            val documentParts: Array<String> = java.util.regex.Pattern.compile("/document/").split(input)
            if ((documentParts.size < 2)) {
                throw IllegalArgumentException(("Unable to resolve a file path from uri: " + uri))
            }
            val safStorage: String = documentParts[1].replace("/tree/", "")
            val storageParts: Array<String> = java.util.regex.Pattern.compile(":").split(safStorage)
            if ((storageParts.size < 2)) {
                throw IllegalArgumentException(("Unable to resolve a file path from uri: " + uri))
            }
            val path: String = storageParts[1]
            if (path.contains("/storage/emulated")) {
                return (if (addQuotes) ("'file://'" + path) else ("file://" + path))
            }
            var finalPath: String? = null
            if (safStorage.contains("primary")) {
                finalPath = ("file:///storage/emulated/0/" + path)
            } else {
                finalPath = ("file:///storage/" + safStorage.replace(":", "/"))
            }
            return (if (addQuotes) (("'" + finalPath) + "'") else finalPath!!)
        }

        @JvmStatic
        fun queryName(resolver: ContentResolver, uri: Uri): String {
            resolver.query(uri, null, null, null, null).use { cursor ->
                if (((cursor != null) && cursor.moveToFirst())) {
                    val nameIndex: Int = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if ((nameIndex >= 0)) {
                        val name: String? = cursor.getString(nameIndex)
                        if ((name != null)) {
                            return name!!
                        }
                    }
                }
            }
            // Not every provider exposes a display name, so fall back to the last path segment
            val lastSegment: String? = uri.getLastPathSegment()
            return (if ((lastSegment != null)) lastSegment!! else "")
        }

        @JvmStatic
        fun getLengthFromFile(context: Context, uri: Uri): Long {
            val file: DocumentFile? = DocumentFile.fromSingleUri(context, uri)
            return (if ((file != null)) (file!!.length()).toLong() else 0L)
        }
    }
}
