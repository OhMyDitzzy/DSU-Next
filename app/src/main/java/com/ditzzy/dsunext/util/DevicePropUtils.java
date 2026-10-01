package com.ditzzy.dsunext.util;

import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public final class DevicePropUtils {

    private static final String TAG = "DevicePropUtils";
    private static final float DEFAULT_GSID_MIN_ALLOC = 0.40F;

    private DevicePropUtils() {
    }

    /**
     * Returns the minimum fraction of free storage gsid needs before allocating a DSU.
     * Stock gsid requires 40%, patched gsid binaries may lower this and advertise the value
     * through this property.
     */
    public static float getGsidBinaryAllowedPerc() {
        String minAllowed = getSystemProperty("ro.vegabobo.dsusideloader.gsid_min_alloc");
        if (!minAllowed.isEmpty()) {
            try {
                return Float.parseFloat(minAllowed);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid gsid_min_alloc value: " + minAllowed);
            }
        }
        return DEFAULT_GSID_MIN_ALLOC;
    }

    public static boolean hasDynamicPartitions() {
        return "true".equals(getSystemProperty("ro.boot.dynamic_partitions"));
    }

    // SystemProperties is a hidden API, so it has to be reached through HiddenApiBypass
    private static String getSystemProperty(String key) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Object value = HiddenApiBypass.invoke(systemProperties, null, "get", key);
            return value != null ? value.toString() : "";
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "Unable to read system property: " + key, e);
            return "";
        }
    }
}
