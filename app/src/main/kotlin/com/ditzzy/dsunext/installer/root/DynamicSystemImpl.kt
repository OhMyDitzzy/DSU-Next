package com.ditzzy.dsunext.installer.root

import android.gsi.GsiProgress
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.image.IDynamicSystemService
import com.ditzzy.dsunext.IPrivilegedService
import com.ditzzy.dsunext.service.PrivilegedProvider

/**
 * Proxies every call of the DynamicSystem API to the privileged service, so the installer can be
 * written as if it were talking to [IDynamicSystemService] directly.
 */
abstract class DynamicSystemImpl : IDynamicSystemService {
    private fun interface RemoteCall<T> {
        @Throws(RemoteException::class)
        fun call(service: IPrivilegedService): T
    }

    override fun asBinder(): IBinder? {
        return null
    }

    override fun getInstallationProgress(): GsiProgress? {
        return remote(RemoteCall(IPrivilegedService::getInstallationProgress))
    }

    override fun abort(): Boolean {
        return remote(RemoteCall(IPrivilegedService::abort))
    }

    override fun isInUse(): Boolean {
        return remote(RemoteCall(IPrivilegedService::isInUse))
    }

    override fun isInstalled(): Boolean {
        return remote(RemoteCall(IPrivilegedService::isInstalled))
    }

    override fun isEnabled(): Boolean {
        return remote(RemoteCall(IPrivilegedService::isEnabled))
    }

    override fun remove(): Boolean {
        return remote(RemoteCall(IPrivilegedService::remove))
    }

    override fun setEnable(enable: Boolean, oneShot: Boolean): Boolean {
        return remote(RemoteCall { service ->
            service.setEnable(enable, oneShot)
        })
    }

    override fun finishInstallation(): Boolean {
        return remote(RemoteCall(IPrivilegedService::finishInstallation))
    }

    override fun startInstallation(dsuSlot: String): Boolean {
        return remote(RemoteCall { service ->
            service.startInstallation(dsuSlot)
        })
    }

    override fun createPartition(name: String, size: Long, readOnly: Boolean): Int {
        return remote(RemoteCall { service ->
            service.createPartition(name, size, readOnly)
        })
    }

    override fun closePartition(): Boolean {
        return remote(RemoteCall(IPrivilegedService::closePartition))
    }

    override fun setAshmem(fd: ParcelFileDescriptor, size: Long): Boolean {
        return remote(RemoteCall { service ->
            service.setAshmem(fd, size)
        })
    }

    override fun submitFromAshmem(bytes: Long): Boolean {
        return remote(RemoteCall { service ->
            service.submitFromAshmem(bytes)
        })
    }

    override fun suggestScratchSize(): Long {
        return remote(RemoteCall(IPrivilegedService::suggestScratchSize))
    }

    // Multi-GSI, these are not part of IDynamicSystemService and go straight to gsid
    open fun getInstalledSlots(): List<String> {
        return remote(RemoteCall(IPrivilegedService::getInstalledSlots))
    }

    open fun removeSlot(slot: String): Boolean {
        return remote(RemoteCall { service ->
            service.removeSlot(slot)
        })
    }

    open fun forceStopDsu() {
        remote(RemoteCall callback7@ { service ->
            service.forceStopPackage("com.android.dynsystem")
            return@callback7 null
        })
    }

    open fun setDynProp() {
        remote(RemoteCall callback8@ { service ->
            service.setDynProp()
            return@callback8 null
        })
    }

    companion object {
        // IDynamicSystemService doesn't declare RemoteException, so it is rethrown unchecked
        private fun <T> remote(call: RemoteCall<T>): T {
            try {
                return call.call(PrivilegedProvider.getService())
            } catch (e: RemoteException) {
                throw IllegalStateException("Privileged service call failed.", e)
            }
        }
    }
}
