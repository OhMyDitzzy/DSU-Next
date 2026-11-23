package com.ditzzy.dsunext.installer.adb

import com.ditzzy.dsunext.core.StorageManager
import com.ditzzy.dsunext.model.Session
import java.io.IOException

/**
 * Generates the shell script with the installation command.
 * Used only when installing over adb.
 */
class AdbInstallationHandler {
    private val storageManager: StorageManager

    private val session: Session

    constructor(storageManager: StorageManager, session: Session) {
        this.storageManager = storageManager
        this.session = session
    }

    /** @return the absolute path of the generated script. */
    @Throws(IOException::class)
    fun generate(): String {
        return InstallationScript(storageManager, session.getInstallationParameters(), session.getPreferences()).writeToFile()
    }
}
