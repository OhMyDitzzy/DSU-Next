package com.ditzzy.dsunext.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Hosts [PrivilegedService] when the app itself runs as a privileged system app. */
class PrivilegedSystemService : Service() {
    override fun onBind(intent: Intent): IBinder? {
        return PrivilegedService()
    }
}
