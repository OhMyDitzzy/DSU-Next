package com.ditzzy.dsunext.installer.adb

import com.ditzzy.dsunext.model.DsuConstants
import com.ditzzy.dsunext.model.InstallationParameters

class InstallationCmdline {
    private val parameters: InstallationParameters

    constructor(parameters: InstallationParameters) {
        this.parameters = parameters
    }

    fun getCmd(): String {
        return ((("am start-activity " + "-n com.android.dynsystem/com.android.dynsystem.VerificationActivity ") + "-a android.os.image.action.START_INSTALL ") + genInstallationArguments())
    }

    private fun genInstallationArguments(): String {
        val arguments: StringBuilder = StringBuilder()
        arguments.append(argument("-d", parameters.getAbsoluteFilePath()))
        arguments.append(argument("--el", "KEY_USERDATA_SIZE", parameters.getUserdataSize()))
        if ((parameters.getImageSize() != DsuConstants.DEFAULT_IMAGE_SIZE)) {
            arguments.append(argument("--el", "KEY_SYSTEM_SIZE", parameters.getImageSize()))
        }
        if (!parameters.getDsuSlot().isEmpty()) {
            arguments.append(argument("--es", "KEY_DSU_SLOT", parameters.getDsuSlot()))
        }
        return arguments.toString().trim()
    }

    companion object {
        private fun argument(argument: String, property: String, value: Any): String {
            return (((((argument + " ") + property) + " ") + value) + " ")
        }

        private fun argument(argument: String, value: Any): String {
            return (((argument + " ") + value) + " ")
        }
    }
}
