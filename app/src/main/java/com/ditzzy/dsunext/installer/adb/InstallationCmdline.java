package com.ditzzy.dsunext.installer.adb;

import com.ditzzy.dsunext.model.DsuConstants;
import com.ditzzy.dsunext.model.InstallationParameters;

public final class InstallationCmdline {

    private final InstallationParameters parameters;

    public InstallationCmdline(InstallationParameters parameters) {
        this.parameters = parameters;
    }

    public String getCmd() {
        return "am start-activity "
                + "-n com.android.dynsystem/com.android.dynsystem.VerificationActivity "
                + "-a android.os.image.action.START_INSTALL "
                + genInstallationArguments();
    }

    private String genInstallationArguments() {
        StringBuilder arguments = new StringBuilder();
        arguments.append(argument("-d", parameters.getAbsoluteFilePath()));
        arguments.append(argument("--el", "KEY_USERDATA_SIZE", parameters.getUserdataSize()));

        if (parameters.getImageSize() != DsuConstants.DEFAULT_IMAGE_SIZE) {
            arguments.append(argument("--el", "KEY_SYSTEM_SIZE", parameters.getImageSize()));
        }
        return arguments.toString().trim();
    }

    private static String argument(String argument, String property, Object value) {
        return argument + " " + property + " " + value + " ";
    }

    private static String argument(String argument, Object value) {
        return argument + " " + value + " ";
    }
}
