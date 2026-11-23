package com.ditzzy.dsunext.installer.privileged

import android.content.Intent
import android.net.Uri
import android.os.RemoteException
import android.os.storage.VolumeInfo
import android.util.Log
import com.ditzzy.dsunext.model.Session
import com.ditzzy.dsunext.service.PrivilegedProvider
import com.ditzzy.dsunext.service.PrivilegedProvider.ServiceAction
import java.util.ArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Installs images through the DSU app (com.android.dynsystem).
 * Supported modes are: Shizuku (as shell or root), root and system.
 */
class DsuInstallationHandler {
    private val session: Session

    /** The DSU slot to install into. Empty keeps the DSU app's own default slot ("dsu"). */
    private val dsuSlot: String

    constructor(session: Session) : this(session, "") {

    }

    constructor(session: Session, dsuSlot: String) {
        this.session = session
        this.dsuSlot = dsuSlot
    }

    /** May block while talking to the service, so it must be called from a background thread. */
    @Throws(RemoteException::class)
    fun startInstallation() {
        if (session.getPreferences().isUnmountSdCard()) {
            unmountSdTemporary()
        }
        forwardInstallationToDsu()
    }

    private fun forwardInstallationToDsu() {
        val userdataSize: Long = session.getUserSelection().getUserSelectedUserdata()
        val fileUri: Uri = session.getDsuInstallation().getUri()
        val length: Long = session.getDsuInstallation().getFileSize()
        PrivilegedProvider.run(ServiceAction { service ->
            service.setDynProp()
            service.forceStopPackage(DSU_PACKAGE)
            val dynIntent: Intent = Intent()
            dynIntent.setClassName(DSU_PACKAGE, (DSU_PACKAGE + ".VerificationActivity"))
            dynIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            dynIntent.setAction("android.os.image.action.START_INSTALL")
            dynIntent.setData(fileUri)
            dynIntent.putExtra("KEY_USERDATA_SIZE", userdataSize)
            dynIntent.putExtra("KEY_SYSTEM_SIZE", length)
            if (!dsuSlot.isEmpty()) {
                // VerificationActivity hands every extra to the installation service, which reads this one
                dynIntent.putExtra(KEY_DSU_SLOT, dsuSlot)
            }
            Log.d(TAG, ("Starting DSU VerificationActivity: " + dynIntent))
            service.startActivity(dynIntent)
        })
    }

    // The SD card is ejected while DSU allocates, so gsid doesn't pick it as the target
    @Throws(RemoteException::class)
    private fun unmountSdTemporary() {
        val volumes: List<VolumeInfo> = PrivilegedProvider.getService().getVolumes()
        val unmounted: MutableList<String> = ArrayList<String>()
        for (volume in volumes) {
            if (volume.id!!.contains("public")) {
                val volumeId: String = volume.id!!
                PrivilegedProvider.run(ServiceAction { service ->
                    service.unmount(volumeId)
                })
                unmounted.add(volumeId)
                Log.d(TAG, ("Volume unmounted: " + volumeId))
            }
        }
        if (!unmounted.isEmpty()) {
            REMOUNT_SCHEDULER.schedule({
                for (volumeId in unmounted) {
                    Log.d(TAG, ("Volume remounted: " + volumeId))
                    PrivilegedProvider.run(ServiceAction { service ->
                        service.mount(volumeId)
                    })
                }
            }, REMOUNT_DELAY_SECONDS, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val TAG: String = "DsuInstallationHandler"

        private const val DSU_PACKAGE: String = "com.android.dynsystem"

        const val KEY_DSU_SLOT: String = "KEY_DSU_SLOT"

        private const val REMOUNT_DELAY_SECONDS: Long = 30L

        private val REMOUNT_SCHEDULER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    }
}
