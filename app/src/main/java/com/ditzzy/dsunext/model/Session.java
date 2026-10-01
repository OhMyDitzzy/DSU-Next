package com.ditzzy.dsunext.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ditzzy.dsunext.util.FilenameUtils;

/** Holds everything that is shared between the UI and the installers during a run. */
public final class Session {

    private final UserSelection userSelection = new UserSelection();
    private final InstallationPreferences preferences = new InstallationPreferences();
    private final MutableLiveData<OperationMode> operationModeLiveData =
            new MutableLiveData<>(OperationMode.ADB);

    private volatile DsuInstallationSource dsuInstallation = DsuInstallationSource.none();
    private volatile OperationMode operationMode = OperationMode.ADB;

    // Only populated when running in ADB mode
    private volatile String installationScriptPath = "";

    // The mode is resolved once per process, so a recreated activity must not bind services again
    private volatile boolean operationModeResolved = false;

    public UserSelection getUserSelection() {
        return userSelection;
    }

    public InstallationPreferences getPreferences() {
        return preferences;
    }

    public DsuInstallationSource getDsuInstallation() {
        return dsuInstallation;
    }

    public void setDsuInstallation(DsuInstallationSource dsuInstallation) {
        this.dsuInstallation = dsuInstallation;
    }

    public String getInstallationScriptPath() {
        return installationScriptPath;
    }

    public void setInstallationScriptPath(String installationScriptPath) {
        this.installationScriptPath = installationScriptPath;
    }

    public OperationMode getOperationMode() {
        return operationMode;
    }

    public LiveData<OperationMode> getOperationModeLiveData() {
        return operationModeLiveData;
    }

    public void setOperationMode(OperationMode newMode) {
        if (operationMode == newMode) {
            return;
        }
        operationMode = newMode;
        // The mode can be resolved off the main thread (eg: Shizuku callbacks)
        operationModeLiveData.postValue(newMode);
    }
    
    public boolean isOperationModeResolved() {
        return operationModeResolved;
    }

    public void setOperationModeResolved(boolean operationModeResolved) {
        this.operationModeResolved = operationModeResolved;
    }

    public boolean isRoot() {
        return operationMode.isRoot();
    }

    public InstallationParameters getInstallationParameters() {
        long userdataSize = userSelection.getUserSelectedUserdata();
        String absoluteFilePath = FilenameUtils.getFilePath(dsuInstallation.getUri(), true);

        long imageSize = dsuInstallation.getFileSize();
        if (userSelection.isCustomImageSize()) {
            imageSize = userSelection.getUserSelectedImageSize();
        }
        return new InstallationParameters(userdataSize, absoluteFilePath, imageSize);
    }

    @Override
    public String toString() {
        return userSelection + "\n" + dsuInstallation + "\n" + preferences
                + "\noperationMode: " + operationMode;
    }
}
