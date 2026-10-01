package com.ditzzy.dsunext.installer.adb;

import com.ditzzy.dsunext.core.StorageManager;
import com.ditzzy.dsunext.model.InstallationParameters;
import com.ditzzy.dsunext.model.InstallationPreferences;

import java.io.IOException;

/** Builds the shell script that is executed over adb to start the DSU installation. */
public final class InstallationScript {

    public static final String FILENAME = "install";
    public static final String ASSETS_SCRIPT_FILE = "install_script.sh";

    private final StorageManager storageManager;
    private final InstallationParameters parameters;
    private final InstallationPreferences preferences;

    public InstallationScript(
            StorageManager storageManager,
            InstallationParameters parameters,
            InstallationPreferences preferences) {
        this.storageManager = storageManager;
        this.parameters = parameters;
        this.preferences = preferences;
    }

    /** @return the absolute path of the written script. */
    public String writeToFile() throws IOException {
        return storageManager.writeStringToExternalFileDir(getShellScript(), FILENAME);
    }

    private String getShellScript() throws IOException {
        return storageManager.readFileFromAssets(ASSETS_SCRIPT_FILE)
                .replace("%ACTION_INSTALL", new InstallationCmdline(parameters).getCmd())
                .replace("%UNMOUNT_SD", String.valueOf(preferences.isUnmountSdCard()));
    }
}
