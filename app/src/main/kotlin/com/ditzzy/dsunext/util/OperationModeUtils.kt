package com.ditzzy.dsunext.util

import android.content.Context
import android.content.pm.PackageManager
import com.ditzzy.dsunext.model.OperationMode
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

class OperationModeUtils {
    private constructor() {

    }

    companion object {
        private const val PERMISSION_INSTALL_DYNAMIC_SYSTEM: String = "android.permission.INSTALL_DYNAMIC_SYSTEM"

        private const val PERMISSION_READ_LOGS: String = "android.permission.READ_LOGS"

        /**
         * Resolves the best operation mode available. Must not be called before the root shell
         * has been requested, otherwise it blocks until the shell is ready.
         *
         * @param checkShizuku only true once the Shizuku binder has been received, since asking
         *                     Shizuku for its state before that throws.
         */
        @JvmStatic
        fun getOperationMode(context: Context, checkShizuku: Boolean): OperationMode {
            val isRoot: Boolean = Shell.getShell().isRoot()
            if (isPermissionGranted(context, PERMISSION_INSTALL_DYNAMIC_SYSTEM)) {
                return (if (isRoot) OperationMode.SYSTEM_AND_ROOT else OperationMode.SYSTEM)
            }
            if (isRoot) {
                return OperationMode.ROOT
            }
            if ((checkShizuku && isShizukuPermissionGranted(context))) {
                return OperationMode.SHIZUKU
            }
            return OperationMode.ADB
        }

        @JvmStatic
        fun isReadLogsPermissionGranted(context: Context): Boolean {
            return isPermissionGranted(context, PERMISSION_READ_LOGS)
        }

        @JvmStatic
        fun isShizukuPermissionGranted(context: Context): Boolean {
            if ((Shizuku.isPreV11() || (Shizuku.getVersion() < 11))) {
                return isPermissionGranted(context, ShizukuProvider.PERMISSION)
            }
            return (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
        }

        private fun isPermissionGranted(context: Context, permission: String): Boolean {
            return (context.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }
    }
}
