package com.ditzzy.dsunext.service;

import android.os.IBinder;
import android.os.SystemProperties;
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
    private static final String GSID_STATE_PROP = "init.svc.gsid";
    private static final long GSID_TIMEOUT_MS = 8_000L;
    private static final long GSID_SHELL_START_AFTER_MS = 2_000L;
    private static final long GSID_POLL_MS = 100L;

    private Object gsi;
    private IBinder gsiBinder;
    private volatile String lastError = "";

    private synchronized Object gsi() throws ReflectiveOperationException {
        // gsid can exit and come back, a proxy of the old process would only throw
        if (gsi != null && gsiBinder != null && !gsiBinder.isBinderAlive()) {
            gsi = null;
            gsiBinder = null;
        }
        if (gsi == null) {
            IBinder binder = awaitGsiBinder();
            Class<?> stub = Class.forName(GSI_STUB);
            gsi = HiddenApiBypass.invoke(stub, null, "asInterface", binder);
            gsiBinder = binder;
        }
        return gsi;
    }

    private static String gsidState() {
        try {
            Object state = HiddenApiBypass.invoke(
                    Class.forName("android.os.SystemProperties"), null, "get", GSID_STATE_PROP);
            return state == null ? "" : state.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * gsid is not running all the time: init starts it on demand, and "gsiservice" only exists while
     * it runs. The DynamicSystem service does the same before it talks to gsid, so it is started
     * here when needed, then the service is awaited.
     */
    private static IBinder awaitGsiBinder() {
        if (!"running".equals(gsidState())) {
            try {
                SystemProperties.set("ctl.start", "gsid");
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to start gsid through ctl.start.", e);
            }
        }

        boolean shellTried = false;
        for (long waited = 0L; waited < GSID_TIMEOUT_MS; waited += GSID_POLL_MS) {
            IBinder binder = PrivilegedService.getBinder(GSI_SERVICE);
            if (binder != null) {
                return binder;
            }
            if (!shellTried && waited >= GSID_SHELL_START_AFTER_MS && !"running".equals(gsidState())) {
                shellTried = true;
                startGsidWithShell();
            }
            try {
                Thread.sleep(GSID_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("gsiservice did not show up, init.svc.gsid is '" + gsidState() + "'.");
    }

    // The property route needs the right to set ctl.start, the command does not depend on that
    private static void startGsidWithShell() {
        try {
            new ProcessBuilder("/system/bin/start", "gsid").redirectErrorStream(true).start().waitFor();
        } catch (Exception e) {
            Log.w(TAG, "Unable to start gsid with the start command.", e);
        }
    }

    private Object call(String method, Object... args) throws ReflectiveOperationException {
        Object service = gsi();
        return HiddenApiBypass.invoke(service.getClass(), service, method, args);
    }

    /** True when gsid answers the slot queries, which needs Android 11 or newer. */
    boolean isAvailable() {
        try {
            call("getInstalledDsuSlots");
            lastError = "";
            return true;
        } catch (Exception e) {
            lastError = describe(e);
            Log.w(TAG, "The slot API is not available.", e);
            return false;
        }
    }

    /** Empty when the last availability check passed, otherwise the root cause of the failure. */
    String status() {
        return lastError;
    }

    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
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
