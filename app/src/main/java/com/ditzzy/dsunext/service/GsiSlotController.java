package com.ditzzy.dsunext.service;

import android.os.IBinder;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-GSI support: lists, activates and removes the DSU slots kept by gsid.
 *
 * <p>The framework's DynamicSystem service only ever talks about one slot (the one being
 * installed, or the active one), so slots are handled through {@code IGsiService} itself. That
 * interface is hidden, so it is reached by reflection, the same way the rest of the privileged
 * service reaches framework internals. Every call is guarded: on a device where a method is
 * missing the controller reports a failure instead of throwing.
 *
 * <p>Runs inside the privileged process only (root or system), gsid refuses other callers.
 */
final class GsiSlotController {

    private static final String TAG = "GsiSlotController";
    private static final String GSI_SERVICE = "gsiservice";
    private static final String GSI_STUB = "android.gsi.IGsiService$Stub";

    private Object gsi;

    private synchronized Object gsi() throws ReflectiveOperationException {
        if (gsi == null) {
            IBinder binder = PrivilegedService.getBinder(GSI_SERVICE);
            if (binder == null) {
                throw new IllegalStateException("gsiservice is not available.");
            }
            Class<?> stub = Class.forName(GSI_STUB);
            gsi = HiddenApiBypass.invoke(stub, null, "asInterface", binder);
        }
        return gsi;
    }

    private Object call(String method, Object... args) throws ReflectiveOperationException {
        Object service = gsi();
        return HiddenApiBypass.invoke(service.getClass(), service, method, args);
    }

    /** True when gsid answers the slot queries, which needs Android 11 or newer. */
    boolean isAvailable() {
        try {
            call("getInstalledDsuSlots");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "The slot API is not available.", e);
            return false;
        }
    }

    List<String> installedSlots() {
        List<String> slots = new ArrayList<>();
        try {
            Object result = call("getInstalledDsuSlots");
            if (result instanceof List) {
                for (Object item : (List<?>) result) {
                    if (item != null && !item.toString().isEmpty()) {
                        slots.add(item.toString());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to list the installed slots.", e);
        }
        return slots;
    }

    String activeSlot() {
        try {
            Object result = call("getActiveDsuSlot");
            return result == null ? "" : result.toString();
        } catch (Exception e) {
            Log.e(TAG, "Unable to read the active slot.", e);
            return "";
        }
    }

    private boolean isGsiEnabled() {
        try {
            return Boolean.TRUE.equals(call("isGsiEnabled"));
        } catch (Exception e) {
            Log.w(TAG, "Unable to read whether the GSI is enabled.", e);
            return false;
        }
    }

    /**
     * Makes {@code slot} the one gsid boots the next time DSU is enabled.
     *
     * @param oneShot boot it once only. The device goes back to the normal system on the reboot after.
     */
    boolean activate(String slot, boolean oneShot) {
        try {
            if (!installedSlots().contains(slot)) {
                Log.e(TAG, "Slot is not installed: " + slot);
                return false;
            }
            return isOk(call("enableGsi", oneShot, slot));
        } catch (Exception e) {
            Log.e(TAG, "Unable to activate slot: " + slot, e);
            return false;
        }
    }

    /**
     * Deletes one slot and leaves the others alone.
     *
     * <p>gsid removes the <em>active</em> slot, so the slot is made active first. Making a slot
     * active also enables it, so whatever was active and enabled before is put back afterwards.
     * Without this, removing one GSI could change which one the device boots.
     */
    boolean remove(String slot) {
        try {
            List<String> slots = installedSlots();
            if (!slots.contains(slot)) {
                return true;
            }
            String previousActive = activeSlot();
            boolean previousEnabled = isGsiEnabled();

            if (!slot.equals(previousActive) && !isOk(call("enableGsi", false, slot))) {
                // A half installed slot can refuse to be activated. With no other slot around,
                // the plain removal cannot hit anything else, so it is safe to use.
                if (slots.size() == 1) {
                    return removeActive();
                }
                Log.e(TAG, "Unable to select slot for removal: " + slot);
                return false;
            }

            boolean removed = removeActive();
            restore(previousActive, previousEnabled, slot);
            return removed;
        } catch (Exception e) {
            Log.e(TAG, "Unable to remove slot: " + slot, e);
            return false;
        }
    }

    private boolean removeActive() throws ReflectiveOperationException {
        return isOk(call("removeGsi"));
    }

    private void restore(String previousActive, boolean previousEnabled, String removedSlot) {
        if (previousActive.isEmpty() || previousActive.equals(removedSlot)) {
            return;
        }
        try {
            if (!installedSlots().contains(previousActive)) {
                return;
            }
            call("enableGsi", false, previousActive);
            if (!previousEnabled) {
                call("disableGsiInstall");
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to restore the active slot: " + previousActive, e);
        }
    }

    // The status comes back as an int (0 is OK) or as a boolean, depending on the method and release
    private static boolean isOk(Object result) {
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        if (result instanceof Integer) {
            return (Integer) result == 0;
        }
        return false;
    }
}
