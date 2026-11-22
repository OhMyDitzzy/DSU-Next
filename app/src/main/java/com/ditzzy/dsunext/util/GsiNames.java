package com.ditzzy.dsunext.util;

import java.util.Collection;
import java.util.Locale;

/**
 * Pure helpers for the names used by Multi-GSI: the label the user sees and the slot id that
 * gsid stores the installation under. Kept free of Android classes so they can be unit tested.
 */
public final class GsiNames {

    /** The slot used by a plain (single GSI) installation, and by every DSU Next before Multi-GSI. */
    public static final String DEFAULT_SLOT = "dsu";

    public static final int MAX_LABEL_LENGTH = 40;

    private static final String SLOT_PREFIX = "dsu_";
    private static final String[] IMAGE_SUFFIXES = {
            ".img.gz", ".img.xz", ".img.gzip", ".img", ".gzip", ".gz", ".xz", ".zip"
    };

    private GsiNames() {
    }

    /** Trims, collapses whitespace and drops control characters. Never returns null. */
    public static String sanitizeLabel(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean pendingSpace = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0;
            } else if (!Character.isISOControl(c)) {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.append(c);
            }
        }
        String label = out.toString();
        return label.length() > MAX_LABEL_LENGTH ? label.substring(0, MAX_LABEL_LENGTH).trim() : label;
    }

    /** The picked file name without its image or archive extension, used when no name is typed. */
    public static String labelFromFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : IMAGE_SUFFIXES) {
            if (lower.endsWith(suffix) && name.length() > suffix.length()) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        return sanitizeLabel(name);
    }

    /**
     * Slot ids only use [a-z0-9_], which is safe as a directory name on every Android version.
     * The id is derived from the time, and bumped until it does not collide with an existing slot.
     */
    public static String newSlotId(long epochSeconds, Collection<String> existing) {
        long value = Math.max(epochSeconds, 0L);
        String id = SLOT_PREFIX + Long.toString(value, 36);
        while (existing != null && existing.contains(id)) {
            value++;
            id = SLOT_PREFIX + Long.toString(value, 36);
        }
        return id;
    }

    public static boolean isValidSlotId(String slot) {
        if (slot == null || slot.isEmpty() || slot.length() > 64) {
            return false;
        }
        for (int i = 0; i < slot.length(); i++) {
            char c = slot.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
