package com.ditzzy.dsunext.viewmodel;

import com.ditzzy.dsunext.preparation.InstallationStep;

/**
 * Snapshot of the installation card. The view model keeps a private working copy and only ever
 * publishes copies, so whatever the UI receives must be treated as read-only.
 */
public final class InstallationCardState {

    public InstallationStep step = InstallationStep.NOT_INSTALLING;
    public boolean fileSelectionEnabled = true;
    public boolean installable = false;
    public boolean fileError = false;
    public String fileName = "";
    public String errorText = "";
    public float progress = 0F;
    public String partition = "";

    public InstallationCardState copy() {
        InstallationCardState copy = new InstallationCardState();
        copy.step = step;
        copy.fileSelectionEnabled = fileSelectionEnabled;
        copy.installable = installable;
        copy.fileError = fileError;
        copy.fileName = fileName;
        copy.errorText = errorText;
        copy.progress = progress;
        copy.partition = partition;
        return copy;
    }

    public boolean isInstalling() {
        return step != InstallationStep.NOT_INSTALLING;
    }

    /**
     * The installation is over (or an installed DSU was found) and nothing is running. The card
     * still has a message and buttons, but the rest of the home screen stays usable.
     */
    public boolean isSettled() {
        switch (step) {
            case INSTALL_SUCCESS:
            case INSTALL_SUCCESS_REBOOT_DYN_OS:
            case DSU_ALREADY_INSTALLED:
            case DSU_ALREADY_RUNNING_DYN_OS:
                return true;
            default:
                return false;
        }
    }

    /** An installation or one of its error screens is taking over the home screen. */
    public boolean isBusy() {
        return isInstalling() && !isSettled();
    }
}