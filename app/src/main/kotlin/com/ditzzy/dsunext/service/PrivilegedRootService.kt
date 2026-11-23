package com.ditzzy.dsunext.service

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService

/** Hosts [PrivilegedService] in a root process. */
class PrivilegedRootService : RootService() {
    override fun onBind(intent: Intent): IBinder {
        return PrivilegedService()
    }
}
