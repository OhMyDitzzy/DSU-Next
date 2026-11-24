package com.ditzzy.dsunext.util

import android.os.Build
import com.ditzzy.dsunext.core.AppPrefs
import com.ditzzy.dsunext.model.Session

/**
 * Multi-GSI keeps every installed GSI in its own DSU slot. Choosing a slot is only possible
 * through the built-in installer and gsid, which means root and Android 11 or newer.
 */
class MultiGsi {
    private constructor() {

    }

    companion object {
        /** The device and operation mode are able to manage slots. */
        @JvmStatic
        fun isSupported(session: Session): Boolean {
            return ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) && session.isRoot())
        }

        /** Multi-GSI is supported, switched on, and the built-in installer that it relies on is on too. */
        @JvmStatic
        fun isActive(prefs: AppPrefs, session: Session): Boolean {
            return ((isSupported(session) && prefs.getBoolean(AppPrefs.MULTI_GSI)) && prefs.getBoolean(AppPrefs.USE_BUILTIN_INSTALLER))
        }
    }
}
