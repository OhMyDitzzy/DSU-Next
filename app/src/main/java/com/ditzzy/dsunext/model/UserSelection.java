package com.ditzzy.dsunext.model;

import android.net.Uri;

import com.ditzzy.dsunext.util.FilenameUtils;

public final class UserSelection {

    private static final long GIGABYTE = 1024L * 1024L * 1024L;

    private volatile long userSelectedUserdata = DsuConstants.DEFAULT_USERDATA;
    private volatile long userSelectedImageSize = DsuConstants.DEFAULT_IMAGE_SIZE;
    private volatile Uri selectedFileUri = Uri.EMPTY;
    private volatile String selectedFileName = "";

    public long getUserSelectedUserdata() {
        return userSelectedUserdata;
    }

    public long getUserSelectedImageSize() {
        return userSelectedImageSize;
    }

    public Uri getSelectedFileUri() {
        return selectedFileUri;
    }

    public String getSelectedFileName() {
        return selectedFileName;
    }

    public void setSelectedFile(Uri uri, String name) {
        this.selectedFileUri = uri;
        this.selectedFileName = name;
    }

    public String getUserDataSizeAsGb() {
        return String.valueOf(userSelectedUserdata / GIGABYTE);
    }

    /** @param size the userdata size in gigabytes, empty means "use the default". */
    public void setUserDataSize(String size) {
        long gigabytes = parseDigits(size);
        userSelectedUserdata = gigabytes >= 0 ? gigabytes * GIGABYTE : DsuConstants.DEFAULT_USERDATA;
    }

    /** @param size the image size in bytes, empty means "let the app decide". */
    public void setImageSize(String size) {
        long bytes = parseDigits(size);
        userSelectedImageSize = bytes >= 0 ? bytes : DsuConstants.DEFAULT_IMAGE_SIZE;
    }

    public boolean isCustomImageSize() {
        return userSelectedImageSize != DsuConstants.DEFAULT_IMAGE_SIZE;
    }

    // Returns -1 when there is nothing usable, this also covers values that overflow a long
    private static long parseDigits(String input) {
        String digits = FilenameUtils.getDigits(input);
        if (digits.isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    @Override
    public String toString() {
        return "UserSelection(userSelectedUserdata=" + userSelectedUserdata
                + ", userSelectedImageSize=" + userSelectedImageSize
                + ", selectedFileUri=" + selectedFileUri
                + ", selectedFileName=" + selectedFileName + ")";
    }
}
