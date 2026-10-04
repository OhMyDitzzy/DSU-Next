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
}