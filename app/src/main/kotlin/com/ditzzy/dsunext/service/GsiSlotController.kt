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

    /** True when gsid answers the slot queries, which needs Android 11 or newer. */
    fun isAvailable(): Boolean {
        try {
            call("getInstalledDsuSlots")
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

    fun installedSlots(): List<String> {
        val slots: MutableList<String> = ArrayList<String>()
        try {
            val result: Any? = call("getInstalledDsuSlots")
            if ((result is List<*>)) {
                for (item in (result as List<*>)) {
                    if (((item != null) && !item.toString().isEmpty())) {
                        slots.add(item.toString())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to list the installed slots.", e)
        }
        return slots
    }

    fun activeSlot(): String {
        try {
            val result: Any? = call("getActiveDsuSlot")
            return (if ((result == null)) "" else result!!.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read the active slot.", e)
            return ""
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
     * @param oneShot boot it once only. The device goes back to the normal system on the reboot after.
     */
    fun activate(slot: String, oneShot: Boolean): Boolean {
        try {
            if (!installedSlots().contains(slot)) {
                Log.e(TAG, ("Slot is not installed: " + slot))
                return false
            }
            return isOk(call("enableGsi", oneShot, slot))
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
     */
    fun remove(slot: String): Boolean {
        try {
            val slots: List<String> = installedSlots()
            if (!slots.contains(slot)) {
                wipeSlotFiles(slot)
                return true
            }
            val previousActive: String = activeSlot()
            val previousEnabled: Boolean = isGsiEnabled()
            var removed: Boolean = false
            if ((!slot.equals(previousActive) && !isOk(call("enableGsi", false, slot)))) {
                // A half installed slot can refuse to be activated. With no other slot around,
                // the plain removal cannot hit anything else, so it is safe to use.
                if ((slots.size == 1)) {
                    removed = removeActive()
                } else {
                    Log.e(TAG, ("Unable to select slot for removal: " + slot))
                    removed = false
                }
            } else {
                removed = removeActive()
                if ((!removed || installedSlots().contains(slot))) {
                    removed = removeActive()
                }
                restore(previousActive, previousEnabled, slot)
            }
            // gsid is the one that should delete the slot. Whatever it left behind is wiped here,
            // so no folder with images stays in /data/gsi/dsu after a removal.
            val gone: Boolean = !installedSlots().contains(slot)
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

    private fun restore(previousActive: String, previousEnabled: Boolean, removedSlot: String) {
        if ((previousActive.isEmpty() || previousActive.equals(removedSlot))) {
            return
        }
        try {
            if (!installedSlots().contains(previousActive)) {
                return
            }
            call("enableGsi", false, previousActive)
            if (!previousEnabled) {
                call("disableGsiInstall")
            }
        } catch (e: Exception) {
            Log.w(TAG, ("Unable to restore the active slot: " + previousActive), e)
        }
    }

    companion object {
        private const val TAG: String = "GsiSlotController"

        private const val GSI_SERVICE: String = "gsiservice"

        private const val GSI_STUB: String = "android.gsi.IGsiService\$Stub"

        private const val GSI_DATA_DIR: String = "/data/gsi/dsu"

        private const val GSI_METADATA_DIR: String = "/metadata/gsi/dsu"

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

        private fun describe(error: Throwable): String {
            var root: Throwable = error
            while (((root.cause != null) && (root.cause != root))) {
                root = root.cause!!!!
            }
            val message: String? = root.message
            return ((root.javaClass.getSimpleName()).toString() + (if ((message == null)) "" else (": " + message)))
        }

        /** Deletes the image folder of a slot and its gsid metadata, when gsid left them behind. */
        private fun wipeSlotFiles(slot: String?) {
            // The slot ends up in a path, only the characters of a slot id are accepted
            if (((slot == null) || !slot!!.matches(Regex("[A-Za-z0-9_]+")))) {
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
