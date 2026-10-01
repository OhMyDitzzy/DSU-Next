package com.ditzzy.dsunext.util;

import android.os.Environment;
import android.os.StatFs;

public final class StorageUtils {

    private StorageUtils() {
    }

    public static final class AllocInfo {

        private final boolean hasAvailableStorage;
        private final int maximumAllowedGb;

        AllocInfo(boolean hasAvailableStorage, int maximumAllowedGb) {
            this.hasAvailableStorage = hasAvailableStorage;
            this.maximumAllowedGb = maximumAllowedGb;
        }

        public boolean hasAvailableStorage() {
            return hasAvailableStorage;
        }

        /** Maximum userdata size, in gigabytes, that can be requested. */
        public int getMaximumAllowedGb() {
            return maximumAllowedGb;
        }
    }

    /**
     * @param allowedPercentage minimum fraction of free storage required by gsid (eg: 0.40F).
     */
    public static AllocInfo getAllocInfo(float allowedPercentage) {
        StatFs statFs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        long blockSize = statFs.getBlockSizeLong();
        long totalSize = statFs.getBlockCountLong() * blockSize;
        long availableSize = statFs.getAvailableBlocksLong() * blockSize;

        boolean hasAvailableStorage = (float) availableSize / (float) totalSize > allowedPercentage;
        int availableGb = (int) (availableSize / 1024L / 1024L / 1024L);

        // Reserve 4GB (an arbitrary number). When the user picks an "img" file it will be packed
        // into a "gz", and that new file takes some space too. This may be refined depending on
        // what is being installed.
        if (availableGb >= 6) {
            availableGb -= 4;
        }
        return new AllocInfo(hasAvailableStorage, availableGb / 2);
    }
}
