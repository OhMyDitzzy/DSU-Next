package com.ditzzy.dsunext.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.ditzzy.dsunext.IPrivilegedService

class Connection : ServiceConnection {
    @Volatile
    private var service: IPrivilegedService? = null

    fun getService(): IPrivilegedService? {
        return service
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = IPrivilegedService.Stub.asInterface(binder)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }
}
