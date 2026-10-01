package com.ditzzy.dsunext.util;

import android.content.Context;
import android.content.pm.PackageManager;

import com.ditzzy.dsunext.model.OperationMode;
import com.topjohnwu.superuser.Shell;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

public final class OperationModeUtils {

    private static final String PERMISSION_INSTALL_DYNAMIC_SYSTEM =
            "android.permission.INSTALL_DYNAMIC_SYSTEM";
    private static final String PERMISSION_READ_LOGS = "android.permission.READ_LOGS";

    private OperationModeUtils() {
    }

    /**
     * Resolves the best operation mode available. Must not be called before the root shell
     * has been requested, otherwise it blocks until the shell is ready.
     *
     * @param checkShizuku only true once the Shizuku binder has been received, since asking
     *                     Shizuku for its state before that throws.
     */
    public static OperationMode getOperationMode(Context context, boolean checkShizuku) {
        boolean isRoot = Shell.getShell().isRoot();

        if (isPermissionGranted(context, PERMISSION_INSTALL_DYNAMIC_SYSTEM)) {
            return isRoot ? OperationMode.SYSTEM_AND_ROOT : OperationMode.SYSTEM;
        }
        if (isRoot) {
            return OperationMode.ROOT;
        }
        if (checkShizuku && isShizukuPermissionGranted(context)) {
            return OperationMode.SHIZUKU;
        }
        return OperationMode.ADB;
    }

    public static boolean isReadLogsPermissionGranted(Context context) {
        return isPermissionGranted(context, PERMISSION_READ_LOGS);
    }

    public static boolean isShizukuPermissionGranted(Context context) {
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            return isPermissionGranted(context, ShizukuProvider.PERMISSION);
        }
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isPermissionGranted(Context context, String permission) {
        return context.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
