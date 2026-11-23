package com.ditzzy.dsunext.util

import android.os.Build
import com.ditzzy.dsunext.core.AppPrefs
import com.ditzzy.dsunext.model.OperationMode
import com.ditzzy.dsunext.model.Session

/**
 * Multi-GSI keeps every installed GSI in its own DSU slot.
 *
 * <p>Two things are needed, and neither needs root:
 * <ul>
 *   <li>Choosing the slot of an installation. The DSU app takes it as the KEY_DSU_SLOT extra, and
 *       the built-in installer (root) passes it to the DynamicSystem service directly.</li>
 *   <li>Listing, selecting and removing slots. These are calls to gsid, which accepts root, system
 *       and shell. Shell is what Shizuku runs as, with one gap: gsid lists slots for root and system
 *       only, so under Shizuku the list is rebuilt from what the app remembers.</li>
 * </ul>
 * Android 11 is the first release with slots. Plain adb has no privileged process, so there the app
 * can only prepare a command, and Multi-GSI stays off.
 */
class MultiGsi {
    private constructor() {

    }

    companion object {
        /** The device and operation mode are able to manage slots. */
        @JvmStatic
        fun isSupported(session: Session): Boolean {
            return ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) && hasPrivilegedAccess(session))
        }

        /** Multi-GSI is supported and switched on. */
        @JvmStatic
        fun isActive(prefs: AppPrefs, session: Session): Boolean {
            return (isSupported(session) && prefs.getBoolean(AppPrefs.MULTI_GSI))
        }

        /**
         * The privileged service runs as shell or better. Root, system and Shizuku all qualify,
         * only the plain adb mode does not.
         */
        @JvmStatic
        fun hasPrivilegedAccess(session: Session): Boolean {
            return (session.getOperationMode() != OperationMode.ADB)
        }
    }
}
