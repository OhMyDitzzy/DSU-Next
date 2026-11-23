package com.ditzzy.dsunext.service

import android.app.IActivityManager
import android.content.ComponentName
import android.content.Intent
import android.gsi.GsiProgress
import android.gsi.IGsiService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemProperties
import android.os.image.IDynamicSystemService
import android.os.storage.IStorageManager
import android.os.storage.VolumeInfo
import android.util.Log
import com.ditzzy.dsunext.BuildConfig
import com.ditzzy.dsunext.IPrivilegedService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Arrays
import java.util.concurrent.TimeUnit

/**
 * Runs with elevated privileges (root, shell through Shizuku, or system) and exposes the
 * framework services the app can't reach on its own.
 *
 * <p>Most methods of the Dynamic System service are guarded by MANAGE_DYNAMIC_SYSTEM, so they
 * are only reachable via root or when running as a properly installed system app. Shizuku can
 * call them too, but they won't work as shell (2000), which lacks that permission.
 *
 * <p>On stock Android, shell installs GSIs through the Dynamic System Updates app, which holds
 * MANAGE_DYNAMIC_SYSTEM, while shell itself only has INSTALL_DYNAMIC_SYSTEM.
 */
class PrivilegedService : IPrivilegedService.Stub {
    private var activityManager: IActivityManager? = null

    private var storageManager: IStorageManager? = null

    private var dynamicSystem: IDynamicSystemService? = null

    private val slotController: GsiSlotController = GsiSlotController()

    // This constructor must stay public and parameterless, Shizuku and libsu instantiate it by name
    constructor() : super() {

    }

    override fun exit() {
        destroy()
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun setDynProp() {
        setProp("persist.sys.fflag.override.settings_dynamic_system", "true")
    }

    override fun getUid(): Int {
        return android.os.Process.myUid()
    }

    //
    // Activity Manager
    //
    @Synchronized
    private fun activityManager(): IActivityManager {
        if ((activityManager == null)) {
            activityManager = IActivityManager.Stub.asInterface(getBinder("activity"))
        }
        return activityManager!!
    }

    override fun startActivity(intent: Intent) {
        val uid: Int = getUid()
        val callerPackage: String = (if (((uid == 2000) || (uid == 0))) "com.android.shell" else BuildConfig.APPLICATION_ID)
        if ((Build.VERSION.SDK_INT > 29)) {
            activityManager().startActivityAsUserWithFeature(null, callerPackage, null, intent, null, null, null, 0, 0, null, null, 0)
        } else {
            activityManager().startActivityAsUser(null, callerPackage, intent, null, null, null, 0, 0, null, null, 0)
        }
    }

    override fun forceStopPackage(packageName: String) {
        activityManager().forceStopPackage(packageName, 0)
    }

    /**
     * Grants `permission` to this app by running `pm grant` as the service's own user
     * (shell through Shizuku, or root).
     *
     * <p>Shizuku used to offer `Shizuku.newProcess()` for this, but it is not part of the
     * public API anymore. Running the command from the user service is the supported way, and it
     * is stable across Android releases, unlike the hidden binder call this used before
     * (`IPackageManager.grantRuntimePermission()`), whose signature and home vary by release.
     */
    override fun grantPermission(permission: String, restart: ComponentName?): Boolean {
        val userId: Int = (Binder.getCallingUid() / PER_USER_RANGE)
        val component: String = (if ((restart != null)) restart!!.flattenToShortString() else "")
        try {
            val result: ShellResult = runShell("/system/bin/sh", "-c", GRANT_SCRIPT, "dsunext", java.lang.String.valueOf(userId), BuildConfig.APPLICATION_ID, permission, component)
            if ((result.exitCode == 0)) {
                Log.i(TAG, (((("Granted " + permission) + " (user ") + userId) + ")"))
                return true
            }
            Log.e(TAG, ((((("Unable to grant " + permission) + ", exit code ") + result.exitCode) + ": ") + result.output))
        } catch (e: IOException) {
            Log.e(TAG, "Unable to run the grant command.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, ("Interrupted while granting " + permission), e)
        }
        return false
    }

    private class ShellResult {
        val exitCode: Int

        val output: String

        constructor(exitCode: Int, output: String) {
            this.exitCode = exitCode
            this.output = output
        }
    }

    //
    // Storage Manager
    //
    @Synchronized
    private fun storageManager(): IStorageManager {
        if ((storageManager == null)) {
            storageManager = IStorageManager.Stub.asInterface(getBinder("mount"))
        }
        return storageManager!!
    }

    override fun getVolumes(): List<VolumeInfo> {
        return ArrayList<VolumeInfo>(storageManager().getVolumes(0).toList())
    }

    override fun unmount(volId: String) {
        storageManager().unmount(volId)
    }

    override fun mount(volId: String) {
        storageManager().mount(volId)
    }

    //
    // Dynamic System
    //
    @Synchronized
    private fun dynamicSystem(): IDynamicSystemService {
        if ((dynamicSystem == null)) {
            dynamicSystem = IDynamicSystemService.Stub.asInterface(getBinder("dynamic_system"))
        }
        return dynamicSystem!!
    }

    override fun closePartition(): Boolean {
        if ((Build.VERSION.SDK_INT <= 30)) {
            return true
        }
        return dynamicSystem().closePartition()
    }

    override fun finishInstallation(): Boolean {
        return dynamicSystem().finishInstallation()
    }

    override fun getInstallationProgress(): GsiProgress? {
        return dynamicSystem().getInstallationProgress()
    }

    override fun abort(): Boolean {
        return dynamicSystem().abort()
    }

    override fun isEnabled(): Boolean {
        return dynamicSystem().isEnabled()
    }

    override fun remove(): Boolean {
        return dynamicSystem().remove()
    }

    override fun setEnable(enable: Boolean, oneShot: Boolean): Boolean {
        return dynamicSystem().setEnable(enable, oneShot)
    }

    override fun startInstallation(dsuSlot: String): Boolean {
        return dynamicSystem().startInstallation(dsuSlot)
    }

    override fun createPartition(name: String, size: Long, readOnly: Boolean): Int {
        val service: IDynamicSystemService = dynamicSystem()
        if ((Build.VERSION.SDK_INT < 33)) {
            try {
                val result: Any = HiddenApiBypass.invoke(service.javaClass, service, "createPartition", name, size, readOnly)
                return (if (java.lang.Boolean.TRUE.equals(result)) IGsiService.INSTALL_OK else IGsiService.INSTALL_ERROR_GENERIC)
            } catch (e: ReflectiveOperationException) {
                Log.e(TAG, ("Unable to create partition: " + name), e)
                return IGsiService.INSTALL_ERROR_GENERIC
            }
        }
        return service.createPartition(name, size, readOnly)
    }

    override fun setAshmem(fd: ParcelFileDescriptor, size: Long): Boolean {
        return dynamicSystem().setAshmem(fd, size)
    }

    override fun submitFromAshmem(bytes: Long): Boolean {
        return dynamicSystem().submitFromAshmem(bytes)
    }

    override fun suggestScratchSize(): Long {
        return dynamicSystem().suggestScratchSize()
    }

    // As shell (Shizuku) the dynamic_system service refuses to answer, gsid answers the same two
    // questions to shell, and they mean the same thing: "is a DSU running" and "is a GSI installed".
    override fun isInUse(): Boolean {
        if (isShell()) {
            return slotController.isRunning()
        }
        return dynamicSystem().isInUse()
    }

    override fun isInstalled(): Boolean {
        if (isShell()) {
            return slotController.isInstalled()
        }
        return dynamicSystem().isInstalled()
    }

    private fun isShell(): Boolean {
        return (android.os.Process.myUid() == SHELL_UID)
    }

    override fun isSlotApiAvailable(): Boolean {
        return ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) && slotController.isAvailable())
    }

    override fun getInstalledSlots(): List<String> {
        return slotController.installedSlots()
    }

    override fun getActiveSlot(): String {
        return slotController.activeSlot()
    }

    override fun activateSlot(slot: String, oneShot: Boolean): Boolean {
        return slotController.activate(slot, oneShot)
    }

    override fun removeSlot(slot: String): Boolean {
        return slotController.remove(slot)
    }

    override fun isSlotListAuthoritative(): Boolean {
        return slotController.isListAuthoritative()
    }

    // svc needs the REBOOT permission, which both root and shell have
    override fun rebootDevice(): Boolean {
        val commands: Array<Array<String>> = arrayOf(arrayOf("/system/bin/svc", "power", "reboot"), arrayOf("/system/bin/reboot"))
        for (command in commands) {
            try {
                val result: ShellResult = runShell(*command)
                if ((result.exitCode == 0)) {
                    return true
                }
                Log.w(TAG, (((("Reboot command failed (" + command[0]) + "), exit code ") + result.exitCode) + ": ") + result.output)
            } catch (e: IOException) {
                Log.w(TAG, ("Unable to run " + command[0]), e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    override fun getSlotApiStatus(): String {
        if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.R)) {
            return (("Android " + Build.VERSION.RELEASE) + " is older than Android 11")
        }
        return slotController.status()
    }

    companion object {
        private const val TAG: String = "PrivilegedService"

        private const val PER_USER_RANGE: Int = 100000

        private const val SHELL_UID: Int = 2000

        private const val SHELL_TIMEOUT_SECONDS: Long = 15L

        // Arguments: $1 user id, $2 package, $3 permission, $4 component to restart (may be empty).
        //
        // The restart is chained to a successful grant inside the script itself, and its output is
        // detached from ours, for two reasons: granting READ_LOGS makes the system kill the app, and
        // Shizuku tears a non-daemon service down once its client is gone. A restart driven from
        // Java could be cut short by either, while the shell child simply carries on.
        private const val GRANT_SCRIPT: String = (((((((("out=\$(/system/bin/cmd package grant --user \"\$1\" \"\$2\" \"\$3\" 2>&1); rc=\$?\n" + "[ -n \"\$out\" ] && echo \"\$out\"\n") + "if [ \$rc -eq 0 ] && [ -n \"\$4\" ]; then\n") + "  ( /system/bin/sleep 1;") + " /system/bin/cmd activity force-stop \"\$2\";") + " /system/bin/cmd activity start --user \"\$1\" -n \"\$4\" )") + " >/dev/null 2>&1 </dev/null &\n") + "fi\n") + "exit \$rc")

        // ServiceManager is a hidden API, so it has to be reached through HiddenApiBypass
        @JvmStatic
        fun getBinder(service: String): IBinder? {
            try {
                val serviceManager: Class<*> = Class.forName("android.os.ServiceManager")
                return (HiddenApiBypass.invoke(serviceManager, null, "getService", service) as IBinder?)
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException(("Unable to obtain service: " + service), e)
            }
        }

        private fun setProp(key: String, value: String) {
            try {
                SystemProperties.`set`(key, value)
            } catch (e: Exception) {
                Log.w(BuildConfig.APPLICATION_ID, ("Unable to set property: " + key), e)
            }
        }

        @Throws(IOException::class, InterruptedException::class)
        private fun runShell(vararg command: String): ShellResult {
            val process: Process = ProcessBuilder(*command).redirectErrorStream(true).start()
            process.getOutputStream().close()
            if (!process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return ShellResult(-1, (("Timed out after " + SHELL_TIMEOUT_SECONDS) + "s"))
            }
            return ShellResult(process.exitValue(), readAll(process.getInputStream()).trim())
        }

        @Throws(IOException::class)
        private fun readAll(`in`: InputStream): String {
            val `out`: ByteArrayOutputStream = ByteArrayOutputStream()
            val buffer: ByteArray = ByteArray(1024)
            var read: Int = 0
            while (((`in`.read(buffer)).also { read = it } != -1)) {
                `out`.write(buffer, 0, read)
            }
            return String(`out`.toByteArray(), StandardCharsets.UTF_8)
        }
    }
}
