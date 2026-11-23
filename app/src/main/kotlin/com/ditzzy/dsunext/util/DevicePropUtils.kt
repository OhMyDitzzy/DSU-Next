package com.ditzzy.dsunext.util

import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

class DevicePropUtils {
    private constructor() {

    }

    companion object {
        private const val TAG: String = "DevicePropUtils"

        private const val DEFAULT_GSID_MIN_ALLOC: Float = 0.40f

        /**
         * Returns the minimum fraction of free storage gsid needs before allocating a DSU.
         * Stock gsid requires 40%, patched gsid binaries may lower this and advertise the value
         * through this property.
         */
        @JvmStatic
        fun getGsidBinaryAllowedPerc(): Float {
            val minAllowed: String = getSystemProperty("ro.vegabobo.dsusideloader.gsid_min_alloc")
            if (!minAllowed.isEmpty()) {
                try {
                    return java.lang.Float.parseFloat(minAllowed)
                } catch (e: NumberFormatException) {
                    Log.w(TAG, ("Invalid gsid_min_alloc value: " + minAllowed))
                }
            }
            return DEFAULT_GSID_MIN_ALLOC
        }

        @JvmStatic
        fun hasDynamicPartitions(): Boolean {
            return "true".equals(getSystemProperty("ro.boot.dynamic_partitions"))
        }

        // SystemProperties is a hidden API, so it has to be reached through HiddenApiBypass
        private fun getSystemProperty(key: String): String {
            try {
                val systemProperties: Class<*> = Class.forName("android.os.SystemProperties")
                val value: Any? = HiddenApiBypass.invoke(systemProperties, null, "get", key)
                return (if ((value != null)) value!!.toString() else "")
            } catch (e: ReflectiveOperationException) {
                Log.w(TAG, ("Unable to read system property: " + key), e)
                return ""
            }
        }
    }
}
