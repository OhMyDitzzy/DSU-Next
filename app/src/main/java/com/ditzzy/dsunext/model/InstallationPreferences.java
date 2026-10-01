package com.ditzzy.dsunext.model;

public final class InstallationPreferences {

    private volatile boolean unmountSdCard = false;
    private volatile boolean useBuiltinInstaller = false;

    public boolean isUnmountSdCard() {
        return unmountSdCard;
    }

    public void setUnmountSdCard(boolean unmountSdCard) {
        this.unmountSdCard = unmountSdCard;
    }

    public boolean isUseBuiltinInstaller() {
        return useBuiltinInstaller;
    }

    public void setUseBuiltinInstaller(boolean useBuiltinInstaller) {
        this.useBuiltinInstaller = useBuiltinInstaller;
    }

    @Override
    public String toString() {
        return "InstallationPreferences(unmountSdCard=" + unmountSdCard
                + ", useBuiltinInstaller=" + useBuiltinInstaller + ")";
    }
}
