package com.ditzzy.dsunext.checker.treble

import java.io.File
import java.io.IOException

data class MountPoint(
    val device: String,
    val mountPoint: String,
    val fileSystem: String,
    val prop: String,
    val dummy1: String,
    val dummy2: String
)

object SystemAsRoot {

    fun check(): Boolean? {

        val mountsPoints = ArrayList<MountPoint>()

        /**
         * Reads the mounted partitions and checks if the system is mounted as
         * root. Returns null if indeterminable.
         */
        try {
            File("/proc/mounts").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val mountDetails = line.split(" ")
                    if (mountDetails.size == 6) {
                        mountsPoints.add(
                            MountPoint(
                                mountDetails[0], mountDetails[1], mountDetails[2],
                                mountDetails[3], mountDetails[4], mountDetails[5]
                            )
                        )
                    }
                }
            }
        } catch (e: IOException) {
            return null
        }

        /**
         * Checks if system has a device block associated with it which isn't temporary
         */
        val systemOnBlock = mountsPoints.none { it.device != "none" && it.mountPoint == "/system" && it.fileSystem != "tmpfs" }

        /**
         * Checks if the device symlink is mounted on root
         */
        val deviceMountedOnRoot = mountsPoints.any { it.device == "/dev/root" && it.mountPoint == "/" }

        /**
         * Checks if a non-temporary block is mounted on system root
         */
        val systemOnRoot = mountsPoints.any { it.mountPoint == "/system_root" && it.fileSystem != "tmpfs" }

        return systemOnBlock || deviceMountedOnRoot || systemOnRoot

    }

}
