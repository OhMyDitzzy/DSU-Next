package com.ditzzy.dsunext.installer.adb

import com.ditzzy.dsunext.core.StorageManager
import com.ditzzy.dsunext.model.InstallationParameters
import com.ditzzy.dsunext.model.InstallationPreferences
import java.io.IOException

/** Builds the shell script that is executed over adb to start the DSU installation. */
class InstallationScript {
    private val storageManager: StorageManager

    private val parameters: InstallationParameters

    private val preferences: InstallationPreferences

    constructor(storageManager: StorageManager, parameters: InstallationParameters, preferences: InstallationPreferences) {
        this.storageManager = storageManager
        this.parameters = parameters
        this.preferences = preferences
    }

    /** @return the absolute path of the written script. */
    @Throws(IOException::class)
    fun writeToFile(): String {
        return storageManager.writeStringToExternalFileDir(getShellScript(), FILENAME)
    }

    @Throws(IOException::class)
    private fun getShellScript(): String {
        return storageManager.readFileFromAssets(ASSETS_SCRIPT_FILE).replace("%ACTION_INSTALL", InstallationCmdline(parameters).getCmd()).replace("%UNMOUNT_SD", java.lang.String.valueOf(preferences.isUnmountSdCard()))
    }

    companion object {
        const val FILENAME: String = "install"

        const val ASSETS_SCRIPT_FILE: String = "install_script.sh"
    }
}
