package com.ditzzy.dsunext.workspace;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

public final class WorkspaceNames {

    public static final int MAX_LENGTH = 64;

    private static final Pattern NAME =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0," + (MAX_LENGTH - 1) + "}$");
    private static final Pattern SIZE =
            Pattern.compile("^(auto|[0-9]{1,15}[KMG]?)$", Pattern.CASE_INSENSITIVE);
    private static final String[] STRIPPED_SUFFIXES = {
            ".tar", ".tgz", ".txz", ".gz", ".gzip", ".xz", ".zip", ".7z", ".img"
    };

    private WorkspaceNames() {
    }

    public static boolean isValid(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Accepts "auto" or a byte count with an optional K, M or G suffix, like Yuki does. */
    public static boolean isValidSize(String size) {
        return size != null && SIZE.matcher(size).matches();
    }

    /** A valid workspace name derived from a file name: "aosp-arm64.img.xz" gives "aosp-arm64". */
    public static String suggest(String fileName) {
        String base = fileName == null ? "" : fileName.trim();

        boolean stripped = true;
        while (stripped) {
            stripped = false;
            String lower = base.toLowerCase(Locale.ROOT);
            for (String suffix : STRIPPED_SUFFIXES) {
                if (lower.endsWith(suffix) && base.length() > suffix.length()) {
                    base = base.substring(0, base.length() - suffix.length());
                    stripped = true;
                    break;
                }
            }
        }

        String clean = base.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^[^A-Za-z0-9]+", "");
        if (clean.length() > MAX_LENGTH) {
            clean = clean.substring(0, MAX_LENGTH);
        }
        return clean.isEmpty() ? "gsi" : clean;
    }

    /** Adds "-2", "-3"... until the name is not in {@code taken}. */
    public static String unique(String base, Collection<String> taken) {
        if (!taken.contains(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String suffix = "-" + i;
            String head = base.length() + suffix.length() > MAX_LENGTH
                    ? base.substring(0, MAX_LENGTH - suffix.length())
                    : base;
            String candidate = head + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }
}
