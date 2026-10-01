package com.ditzzy.dsunext.model;

/**
 * Defines how the app talks to the system, ordered from the most to the least capable.
 *
 * <ul>
 *   <li>SYSTEM_AND_ROOT: installed as a privileged system app, with root granted.</li>
 *   <li>SYSTEM: installed as a privileged system app (INSTALL_DYNAMIC_SYSTEM granted).</li>
 *   <li>ROOT: root granted, exposes the DynamicSystem API and the built-in installer.</li>
 *   <li>SHIZUKU: running through Shizuku, can start the DSU installation and track it.</li>
 *   <li>ADB: default, only prepares the image and generates a command to run over adb.</li>
 * </ul>
 */
public enum OperationMode {
    SYSTEM_AND_ROOT("Root/System"),
    SYSTEM("System"),
    ROOT("Root"),
    SHIZUKU("Shizuku"),
    ADB("ADB");

    private final String label;

    OperationMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isRoot() {
        return this == SYSTEM_AND_ROOT || this == ROOT;
    }
}
