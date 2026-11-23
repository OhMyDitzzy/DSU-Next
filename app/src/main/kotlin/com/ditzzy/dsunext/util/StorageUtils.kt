package com.ditzzy.dsunext.util

import android.os.Environment
import android.os.StatFs

class StorageUtils {
    private constructor() {

    }

    class AllocInfo {
        private val hasAvailableStorage: Boolean

        private val maximumAllowedGb: Int

        constructor(hasAvailableStorage: Boolean, maximumAllowedGb: Int) {
            this.hasAvailableStorage = hasAvailableStorage
            this.maximumAllowedGb = maximumAllowedGb
        }

        fun hasAvailableStorage(): Boolean {
            return hasAvailableStorage
        }

        /** Maximum userdata size, in gigabytes, that can be requested. */
        fun getMaximumAllowedGb(): Int {
            return maximumAllowedGb
        }
    }

    companion object {
        /**
         * @param allowedPercentage minimum fraction of free storage required by gsid (eg: 0.40F).
         */
        @JvmStatic
        fun getAllocInfo(allowedPercentage: Float): AllocInfo {
            val statFs: StatFs = StatFs(Environment.getDataDirectory().getAbsolutePath())
            val blockSize: Long = statFs.getBlockSizeLong()
            val totalSize: Long = (statFs.getBlockCountLong() * blockSize)
            val availableSize: Long = (statFs.getAvailableBlocksLong() * blockSize)
            val hasAvailableStorage: Boolean = (((availableSize).toFloat() / (totalSize).toFloat()) > allowedPercentage)
            var availableGb: Int = ((((availableSize / 1024L) / 1024L) / 1024L)).toInt()
            // Reserve 4GB (an arbitrary number). When the user picks an "img" file it will be packed
            // into a "gz", and that new file takes some space too. This may be refined depending on
            // what is being installed.
            if ((availableGb >= 6)) {
                availableGb -= 4
            }
            return AllocInfo(hasAvailableStorage, (availableGb / 2))
        }
    }
}
