package com.ditzzy.dsunext.util

import android.os.RemoteException
import com.ditzzy.dsunext.IPrivilegedService
import com.ditzzy.dsunext.core.SlotRegistry
import java.util.ArrayList
import java.util.LinkedHashSet

/**
 * The installed slots, whatever the privileged service runs as.
 *
 * <p>As root or system gsid lists them. As shell (Shizuku) it refuses, so the list is rebuilt from
 * the slots this app registered plus the slot gsid has as active. A slot that was removed behind the
 * app's back stays listed until it is removed from the app, that is the price of not having root.
 */
class SlotQueries {
    private constructor() {

    }

    companion object {
        @JvmStatic
        @Throws(RemoteException::class)
        fun list(service: IPrivilegedService, registry: SlotRegistry): List<String> {
            val fromService: List<String> = service.getInstalledSlots()
            if (service.isSlotListAuthoritative()) {
                return fromService
            }
            val merged: MutableSet<String> = LinkedHashSet<String>(fromService)
            merged.addAll(registry.slotIds())
            // Only trusted while gsid says something is installed, the active file can outlive a removal
            val active: String = service.getActiveSlot()
            if ((!active.isEmpty() && service.isInstalled())) {
                merged.add(active)
            }
            return ArrayList<String>(merged)
        }
    }
}
