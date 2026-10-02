package com.ditzzy.dsunext.checker.treble

data class ABResult(
    val isVirtual: Boolean
)

object AB {

    fun check(): ABResult? {

        /**
         * Checks if the device supports Virtual A/B partition. Only retrofitted
         * devices set ro.virtual_ab.retrofit (to true), so anything else that
         * has Virtual A/B enabled is a regular Virtual A/B device.
         */
        if (getProperty("ro.virtual_ab.enabled") == "true" && getProperty("ro.virtual_ab.retrofit") != "true") {
            return ABResult(true)
        }

        /**
         * Checks if the device supports the conventional A/B partition
         */
        if (!getProperty("ro.boot.slot_suffix").isNullOrBlank() || getProperty("ro.build.ab_update") == "true") {
            return ABResult(false)
        }

        /**
         * Returns null if the device doesn't support A/B partitions at all
         */
        return null
    }

}
