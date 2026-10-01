package com.ditzzy.dsunext.model;

/** Values needed to build the adb installation command. */
public final class InstallationParameters {

    private final long userdataSize;
    private final String absoluteFilePath;
    private final long imageSize;

    public InstallationParameters(long userdataSize, String absoluteFilePath, long imageSize) {
        this.userdataSize = userdataSize;
        this.absoluteFilePath = absoluteFilePath;
        this.imageSize = imageSize;
    }

    public long getUserdataSize() {
        return userdataSize;
    }

    public String getAbsoluteFilePath() {
        return absoluteFilePath;
    }

    public long getImageSize() {
        return imageSize;
    }
}
