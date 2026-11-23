package com.ditzzy.dsunext.service

import android.os.IBinder
import android.os.SystemProperties
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.ArrayList

/**
 * Multi-GSI support: lists, activates and removes the DSU slots kept by gsid.
 *
 * <p>The framework's DynamicSystem service only ever talks about one slot (the one being
 * installed, or the active one), so slots are handled through `IGsiService` itself. That
 * interface is hidden, so it is reached by reflection, the same way the rest of the privileged
 * service reaches framework internals. Every call is guarded: on a device where a method is
 * missing the controller reports a failure instead of throwing.
 *
 * <p>Runs inside the privileged process only (root or system), gsid refuses other callers.
 */
class GsiSlotController {
    private var gsi: Any? = null

    private var gsiBinder: IBinder? = null

    @Volatile
    private var lastError: String = ""

    @Synchronized
    @Throws(ReflectiveOperationException::class)
    private fun gsi(): Any {
        if ((((gsi != null) && (gsiBinder != null)) && !gsiBinder!!.isBinderAlive())) {
            gsi = null
            gsiBinder = null
        }
        if ((gsi == null)) {
            val binder: IBinder = awaitGsiBinder()
            val stub: Class<*> = Class.forName(GSI_STUB)
            gsi = HiddenApiBypass.invoke(stub, null, "asInterface", binder)
            gsiBinder = binder
        }
        return gsi!!
    }

    @Throws(ReflectiveOperationException::class)
    private fun call(method: String, vararg args: Any?): Any? {
        val service: Any = gsi()
        return HiddenApiBypass.invoke(service.javaClass, service, method, *args)
    }

    /**
     * True when gsid answers. Probed with getActiveDsuSlot(), which root, system and shell may all
     * call, unlike getInstalledDsuSlots() that gsid keeps for root and system only.
     */
    fun isAvailable(): Boolean {
        try {
            call("getActiveDsuSlot")
            lastError = ""
            return true
        } catch (e: Exception) {
            lastError = describe(e)
            Log.w(TAG, "The slot API is not available.", e)
            return false
        }
    }

    /** Empty when the last availability check passed, otherwise the root cause of the failure. */
    fun status(): String {
        return lastError
    }

    /** gsid refused getInstalledDsuSlots() to this caller (shell). Remembered, the uid never changes. */
    @Volatile
    private var listingDenied: Boolean = false

    /** The last call of [installedSlots] returned the real list of slots. */
    @Volatile
    private var listAuthoritative: Boolean = false

    /**
     * The installed slots, from the best source this caller may use:
     * gsid (root, system), then the gsid metadata folder (when SELinux lets the caller read it).
     * When neither works the list is empty and [isListAuthoritative] is false: the caller then has
     * to rely on what it remembers.
     */
    fun installedSlots(): List<String> {
        val fromGsid: List<String>? = listFromGsid()
        if ((fromGsid != null)) {
            listAuthoritative = true
            return fromGsid!!
        }
        val fromMetadata: List<String>? = listFromMetadata()
        if ((fromMetadata != null)) {
            listAuthoritative = true
            return fromMetadata!!
        }
        listAuthoritative = false
        return ArrayList<String>()
    }

    /** Whether [installedSlots] can be trusted to be complete. Refreshes the answer. */
    fun isListAuthoritative(): Boolean {
        installedSlots()
        return listAuthoritative
    }

    private fun listFromGsid(): List<String>? {
        if (listingDenied) {
            return null
        }
        try {
            val result: Any? = call("getInstalledDsuSlots")
            val slots: MutableList<String> = ArrayList<String>()
            if ((result is List<*>)) {
                for (item in (result as List<*>)) {
                    if (((item != null) && !item.toString().isEmpty())) {
                        slots.add(item.toString())
                    }
                }
            }
            return slots
        } catch (e: Exception) {
            if (isSecurityDenied(e)) {
                // Expected when running as shell (Shizuku): gsid lists slots for root and system only
                listingDenied = true
                Log.i(TAG, "gsid does not list the slots to this caller, falling back.")
            } else {
                Log.e(TAG, "Unable to list the installed slots.", e)
            }
            return null
        }
    }

    /**
     * Every slot folder of /metadata/gsi/dsu holds an install_dir file, which is also how gsid
     * itself tells slots from the other files. Null when the folder is not readable, or when it has
     * folders but none can be verified (SELinux hiding the files inside).
     */
    private fun listFromMetadata(): List<String>? {
        try {
            val entries: Array<File>? = File(GSI_METADATA_DIR).listFiles()
            if ((entries == null)) {
                return null
            }
            val candidates: MutableList<String> = ArrayList<String>()
            val verified: MutableList<String> = ArrayList<String>()
            for (entry in entries!!) {
                val name: String = entry.getName()
                if ((name.startsWith(".") || !entry.isDirectory())) {
                    continue
                }
                candidates.add(name)
                if (File(entry, INSTALL_DIR_FILE).exists()) {
                    verified.add(name)
                }
            }
            if ((verified.isEmpty() && !candidates.isEmpty())) {
                return null
            }
            return verified
        } catch (e: SecurityException) {
            return null
        }
    }

    /**
     * The slot gsid boots when DSU is enabled. The active file is read first: getActiveDsuSlot()
     * answers with the slot of the last installation for as long as gsid keeps running, which is not
     * always the slot that is active now.
     */
    fun activeSlot(): String {
        val fromFile: String? = readActiveFile()
        if ((fromFile != null && !fromFile.isEmpty())) {
            return fromFile
        }
        try {
            val result: Any? = call("getActiveDsuSlot")
            return (if ((result == null)) "" else result!!.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read the active slot.", e)
            return ""
        }
    }

    private fun readActiveFile(): String? {
        try {
            val file: File = File(GSI_ACTIVE_FILE)
            if (!file.isFile()) {
                return null
            }
            return file.readText().trim()
        } catch (e: Exception) {
            return null
        }
    }

    /** Any slot is installed (gsid's install status file exists). */
    fun isInstalled(): Boolean {
        try {
            return java.lang.Boolean.TRUE.equals(call("isGsiInstalled"))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read whether a GSI is installed.", e)
            return false
        }
    }

    /** A DSU is the running system right now. */
    fun isRunning(): Boolean {
        try {
            return java.lang.Boolean.TRUE.equals(call("isGsiRunning"))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read whether a GSI is running.", e)
            return false
        }
    }

    private fun isGsiEnabled(): Boolean {
        try {
            return java.lang.Boolean.TRUE.equals(call("isGsiEnabled"))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read whether the GSI is enabled.", e)
            return false
        }
    }

    /**
     * Makes `slot` the one gsid boots the next time DSU is enabled.
     *
     * <p>gsid does not check that the slot exists, it only writes the name down. So whoever calls
     * this with a slot that cannot be verified here (the shell case) must know it is installed.
     *
     * @param oneShot boot it once only. The device goes back to the normal system on the reboot after.
     */
    fun activate(slot: String, oneShot: Boolean): Boolean {
        try {
            if ((isListAuthoritative() && !installedSlots().contains(slot))) {
                Log.e(TAG, ("Slot is not installed: " + slot))
                return false
            }
            if (!isOk(call("enableGsi", oneShot, slot))) {
                return false
            }
            val now: String = activeSlot()
            if ((!now.isEmpty() && !now.equals(slot))) {
                Log.w(TAG, (((("enableGsi() succeeded but the active slot reads '" + now) + "' instead of '") + slot) + "'."))
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, ("Unable to activate slot: " + slot), e)
            return false
        }
    }

    /**
     * Deletes one slot and leaves the others alone.
     *
     * <p>gsid removes the <em>active</em> slot, so the slot is made active first. Making a slot
     * active also enables it, so whatever was active and enabled before is put back afterwards.
     * Without this, removing one GSI could change which one the device boots.
     *
     * <p>When the slot list cannot be verified (shell) the caller must only pass slots it knows are
     * installed. Removing a slot that does not exist would make gsid fall back to the default
     * folder, and delete the default "dsu" slot instead. For that reason the active slot is
     * re-read after the switch and the removal is dropped if it is not the slot asked for.
     */
    fun remove(slot: String): Boolean {
        try {
            val authoritative: Boolean = isListAuthoritative()
            val slots: List<String> = installedSlots()
            if ((authoritative && !slots.contains(slot))) {
                wipeSlotFiles(slot)
                return true
            }
            val previousActive: String = activeSlot()
            val previousEnabled: Boolean = isGsiEnabled()
            var removed: Boolean = false
            if ((!slot.equals(previousActive) && !isOk(call("enableGsi", false, slot)))) {
                // A half installed slot can refuse to be activated. With no other slot around,
                // the plain removal cannot hit anything else, so it is safe to use.
                if ((authoritative && (slots.size == 1))) {
                    removed = removeActive()
                } else {
                    Log.e(TAG, ("Unable to select slot for removal: " + slot))
                    removed = false
                }
            } else if ((!authoritative && !slot.equals(activeSlot()))) {
                Log.e(TAG, ("The active slot is not " + slot + ", not removing anything."))
                restore(previousActive, previousEnabled, slot, authoritative)
                return false
            } else {
                removed = removeActive()
                if ((!removed || (authoritative && installedSlots().contains(slot)))) {
                    removed = removeActive()
                }
                restore(previousActive, previousEnabled, slot, authoritative)
            }
            // gsid is the one that should delete the slot. Whatever it left behind is wiped here,
            // so no folder with images stays in /data/gsi/dsu after a removal.
            val gone: Boolean = (authoritative && !installedSlots().contains(slot))
            if ((gone || removed)) {
                wipeSlotFiles(slot)
            }
            return (gone || removed)
        } catch (e: Exception) {
            Log.e(TAG, ("Unable to remove slot: " + slot), e)
            return false
        }
    }

    @Throws(ReflectiveOperationException::class)
    private fun removeActive(): Boolean {
        return isOk(call("removeGsi"))
    }

    private fun restore(previousActive: String, previousEnabled: Boolean, removedSlot: String, authoritative: Boolean) {
        if ((previousActive.isEmpty() || previousActive.equals(removedSlot))) {
            return
        }
        try {
            if ((authoritative && !installedSlots().contains(previousActive))) {
                return
            }
            call("enableGsi", false, previousActive)
            if (!previousEnabled) {
                disableGsi()
            }
        } catch (e: Exception) {
            Log.w(TAG, ("Unable to restore the active slot: " + previousActive), e)
        }
    }

    // The method is named disableGsi in current gsid and disableGsiInstall in older releases
    @Throws(ReflectiveOperationException::class)
    private fun disableGsi() {
        try {
            call("disableGsi")
        } catch (e: NoSuchMethodException) {
            call("disableGsiInstall")
        }
    }

    companion object {
        private const val TAG: String = "GsiSlotController"

        private const val GSI_SERVICE: String = "gsiservice"

        private const val GSI_STUB: String = "android.gsi.IGsiService\$Stub"

        private const val GSI_DATA_DIR: String = "/data/gsi/dsu"

        private const val GSI_METADATA_DIR: String = "/metadata/gsi/dsu"

        private const val GSI_ACTIVE_FILE: String = "/metadata/gsi/dsu/active"

        private const val INSTALL_DIR_FILE: String = "install_dir"

        private const val GSID_STATE_PROP: String = "init.svc.gsid"

        private const val GSID_TIMEOUT_MS: Long = 8000L

        private const val GSID_SHELL_START_AFTER_MS: Long = 2000L

        private const val GSID_POLL_MS: Long = 100L

        private fun gsidState(): String {
            try {
                val state: Any? = HiddenApiBypass.invoke(Class.forName("android.os.SystemProperties"), null, "get", GSID_STATE_PROP)
                return (if ((state == null)) "" else state!!.toString())
            } catch (e: Exception) {
                return ""
            }
        }

        /**
         * gsid is not running all the time: init starts it on demand, and "gsiservice" only exists while
         * it runs. The DynamicSystem service does the same before it talks to gsid, so it is started
         * here when needed, then the service is awaited.
         */
        private fun awaitGsiBinder(): IBinder {
            if (!"running".equals(gsidState())) {
                try {
                    SystemProperties.`set`("ctl.start", "gsid")
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Unable to start gsid through ctl.start.", e)
                }
            }
            var shellTried: Boolean = false
            run {
                var waited: Long = 0L
                while ((waited < GSID_TIMEOUT_MS)) {
                    val binder: IBinder? = PrivilegedService.getBinder(GSI_SERVICE)
                    if ((binder != null)) {
                        return binder!!
                    }
                    if (((!shellTried && (waited >= GSID_SHELL_START_AFTER_MS)) && !"running".equals(gsidState()))) {
                        shellTried = true
                        startGsidWithShell()
                    }
                    try {
                        Thread.sleep(GSID_POLL_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    (GSID_POLL_MS).also { waited += it }
                }
            }
            throw IllegalStateException((("gsiservice did not show up, init.svc.gsid is '" + gsidState()) + "'."))
        }

        // The property route needs the right to set ctl.start, the command does not depend on that
        private fun startGsidWithShell() {
            try {
                ProcessBuilder("/system/bin/start", "gsid").redirectErrorStream(true).start().waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start gsid with the start command.", e)
            }
        }

        private fun rootCause(error: Throwable): Throwable {
            var root: Throwable = error
            while (((root.cause != null) && (root.cause != root))) {
                root = root.cause!!
            }
            return root
        }

        private fun isSecurityDenied(error: Throwable): Boolean {
            return (rootCause(error) is SecurityException)
        }

        // Only root and system may touch the gsid folders. Shell leaves it all to gsid.
        private fun canTouchGsiFiles(): Boolean {
            val uid: Int = android.os.Process.myUid()
            return ((uid == 0) || (uid == 1000))
        }

        private fun describe(error: Throwable): String {
            val root: Throwable = rootCause(error)
            val message: String? = root.message
            return ((root.javaClass.getSimpleName()).toString() + (if ((message == null)) "" else (": " + message)))
        }

        /** Deletes the image folder of a slot and its gsid metadata, when gsid left them behind. */
        private fun wipeSlotFiles(slot: String?) {
            // The slot ends up in a path, only the characters of a slot id are accepted
            if (((slot == null) || !slot!!.matches(Regex("[A-Za-z0-9_]+")))) {
                return
            }
            if (!canTouchGsiFiles()) {
                return
            }
            deleteRecursively(File(GSI_DATA_DIR, slot))
            val metadata: Array<File>? = File(GSI_METADATA_DIR).listFiles()
            if ((metadata != null)) {
                for (file in metadata!!) {
                    val name: String = file.getName()
                    if ((name.equals(slot) || name.startsWith(((slot).toString() + ".")))) {
                        deleteRecursively(file)
                    }
                }
            }
        }

        private fun deleteRecursively(file: File) {
            if (!file.exists()) {
                return
            }
            val children: Array<File>? = (if (file.isDirectory()) file.listFiles() else null)
            if ((children != null)) {
                for (child in children!!) {
                    deleteRecursively(child)
                }
            }
            if ((!file.delete() && file.exists())) {
                Log.w(TAG, ("Unable to delete " + file))
            }
        }

        // The status comes back as an int (0 is OK) or as a boolean, depending on the method and release
        private fun isOk(result: Any?): Boolean {
            if ((result is Boolean)) {
                return (result as Boolean)
            }
            if ((result is Int)) {
                return ((result as Int) == 0)
            }
            return false
        }
    }
}
