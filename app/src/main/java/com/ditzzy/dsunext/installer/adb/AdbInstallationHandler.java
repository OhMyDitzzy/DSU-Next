package com.ditzzy.dsunext.installer.adb;

import com.ditzzy.dsunext.core.StorageManager;
import com.ditzzy.dsunext.model.Session;

import java.io.IOException;

/**
 * Generates the shell script with the installation command.
 * Used only when installing over adb.
 */
public final class AdbInstallationHandler {

    private final StorageManager storageManager;
    private final Session session;

    public AdbInstallationHandler(StorageManager storageManager, Session session) {
        this.storageManager = storageManager;
        this.session = session;
    }

    /** @return the absolute path of the generated script. */
    public String generate() throws IOException {
        return new InstallationScript(
                storageManager,
                session.getInstallationParameters(),
                session.getPreferences()).writeToFile();
    }
}
