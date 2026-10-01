package com.ditzzy.dsunext.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.documentfile.provider.DocumentFile;

public final class FilenameUtils {

    private FilenameUtils() {
    }

    /** Keeps only the digits of the given text, returns an empty string when there are none. */
    public static String getDigits(String input) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    /**
     * Tries to convert a DocumentFile uri to a real path, this isn't guaranteed to work
     * with every kind of document provider.
     *
     * @param addQuotes wraps the result in single quotes so it is safe to use in a shell.
     */
    public static String getFilePath(Uri uri, boolean addQuotes) {
        String input = String.valueOf(uri.getPath());
        String[] documentParts = input.split("/document/");
        if (documentParts.length < 2) {
            throw new IllegalArgumentException("Unable to resolve a file path from uri: " + uri);
        }

        String safStorage = documentParts[1].replace("/tree/", "");
        String[] storageParts = safStorage.split(":");
        if (storageParts.length < 2) {
            throw new IllegalArgumentException("Unable to resolve a file path from uri: " + uri);
        }
        String path = storageParts[1];

        if (path.contains("/storage/emulated")) {
            return addQuotes ? "'file://'" + path : "file://" + path;
        }

        String finalPath;
        if (safStorage.contains("primary")) {
            finalPath = "file:///storage/emulated/0/" + path;
        } else {
            finalPath = "file:///storage/" + safStorage.replace(":", "/");
        }
        return addQuotes ? "'" + finalPath + "'" : finalPath;
    }

    public static String queryName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    String name = cursor.getString(nameIndex);
                    if (name != null) {
                        return name;
                    }
                }
            }
        }
        // Not every provider exposes a display name, so fall back to the last path segment
        String lastSegment = uri.getLastPathSegment();
        return lastSegment != null ? lastSegment : "";
    }

    public static long getLengthFromFile(Context context, Uri uri) {
        DocumentFile file = DocumentFile.fromSingleUri(context, uri);
        return file != null ? file.length() : 0L;
    }
}
