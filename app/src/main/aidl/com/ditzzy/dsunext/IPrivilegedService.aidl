package com.ditzzy.dsunext;

import android.content.ComponentName;
import android.content.Intent;
import android.gsi.GsiProgress;
import android.os.ParcelFileDescriptor;
import android.os.storage.VolumeInfo;

interface IPrivilegedService {
    void exit() = 1;
    void destroy() = 16777114;
    void setDynProp() = 100;
    int getUid() = 1000;

    void startActivity(in Intent intent) = 1001;
    void forceStopPackage(String packageName) = 1003;

    // Grants one of our own permissions. When restart is not null the app is restarted afterwards
    // (the new group of READ_LOGS only reaches a fresh process). Returns false if the grant failed.
    boolean grantPermission(String permission, in ComponentName restart) = 2001;

    List<VolumeInfo> getVolumes() = 3001;
    void unmount(String volId) = 3002;
    void mount(String volId) = 3003;

    GsiProgress getInstallationProgress() = 4001;
    boolean abort() = 4002;
    boolean isInUse() = 4003;
    boolean isInstalled() = 4004;
    boolean isEnabled() = 4005;
    boolean remove() = 4006;
    boolean setEnable(boolean enable, boolean oneShot) = 4007;
    boolean finishInstallation() = 4008;
    boolean startInstallation(String dsuSlot) = 4009;
    int createPartition(@utf8InCpp String name, long size, boolean readOnly) = 4010;
    boolean closePartition() = 4011;
    boolean setAshmem(in ParcelFileDescriptor fd, long size) = 4012;
    boolean submitFromAshmem(long bytes) = 4013;
    long suggestScratchSize() = 4014;

    // Multi-GSI: every installed GSI lives in its own DSU slot of gsid. These never throw, a failure
    // is reported as false, an empty list or an empty string.
    boolean isSlotApiAvailable() = 4015;
    List<String> getInstalledSlots() = 4016;
    String getActiveSlot() = 4017;
    boolean activateSlot(String slot, boolean oneShot) = 4018;
    boolean removeSlot(String slot) = 4019;
    // Empty when the slot API works, otherwise why it does not (shown on the Multi-GSI screen)
    String getSlotApiStatus() = 4020;

    // True when getInstalledSlots() is the full truth: gsid lists the slots (root or system) or the
    // gsid metadata folder is readable. False when running as shell (Shizuku), where gsid refuses to
    // list slots and the caller has to rely on what it remembers plus getActiveSlot().
    boolean isSlotListAuthoritative() = 4021;

    // Restarts the device. Works as root and as shell, so Shizuku does not need libsu for it.
    boolean rebootDevice() = 4022;
}