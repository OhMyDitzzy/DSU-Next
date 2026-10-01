package com.ditzzy.dsunext.service;

import android.app.IActivityManager;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.gsi.GsiProgress;
import android.gsi.IGsiService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemProperties;
import android.os.image.IDynamicSystemService;
import android.os.storage.IStorageManager;
import android.os.storage.VolumeInfo;
import android.util.Log;

import com.ditzzy.dsunext.BuildConfig;
import com.ditzzy.dsunext.IPrivilegedService;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs with elevated privileges (root, shell through Shizuku, or system) and exposes the
 * framework services the app can't reach on its own.
 *
 * <p>Most methods of the Dynamic System service are guarded by MANAGE_DYNAMIC_SYSTEM, so they
 * are only reachable via root or when running as a properly installed system app. Shizuku can
 * call them too, but they won't work as shell (2000), which lacks that permission.
 *
 * <p>On stock Android, shell installs GSIs through the Dynamic System Updates app, which holds
 * MANAGE_DYNAMIC_SYSTEM, while shell itself only has INSTALL_DYNAMIC_SYSTEM.
 */
public final class PrivilegedService extends IPrivilegedService.Stub {

    private static final String TAG = "PrivilegedService";

    private IActivityManager activityManager;
    private IPackageManager packageManager;
    private IStorageManager storageManager;
    private IDynamicSystemService dynamicSystem;

    // This constructor must stay public and parameterless, Shizuku and libsu instantiate it by name
    public PrivilegedService() {
    }

    @Override
    public void exit() {
        destroy();
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    // ServiceManager is a hidden API, so it has to be reached through HiddenApiBypass
    private static IBinder getBinder(String service) {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            return (IBinder) HiddenApiBypass.invoke(serviceManager, null, "getService", service);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to obtain service: " + service, e);
        }
    }

    @Override
    public void setDynProp() {
        setProp("persist.sys.fflag.override.settings_dynamic_system", "true");
    }

    private static void setProp(String key, String value) {
        try {
            SystemProperties.set(key, value);
        } catch (Exception e) {
            Log.w(BuildConfig.APPLICATION_ID, "Unable to set property: " + key, e);
        }
    }

    @Override
    public int getUid() {
        return android.os.Process.myUid();
    }

    //
    // Activity Manager
    //

    private synchronized IActivityManager activityManager() {
        if (activityManager == null) {
            activityManager = IActivityManager.Stub.asInterface(getBinder("activity"));
        }
        return activityManager;
    }

    @Override
    public void startActivity(Intent intent) {
        int uid = getUid();
        String callerPackage = (uid == 2000 || uid == 0) ? "com.android.shell" : BuildConfig.APPLICATION_ID;

        if (Build.VERSION.SDK_INT > 29) {
            activityManager().startActivityAsUserWithFeature(
                    null, callerPackage, null, intent, null, null, null, 0, 0, null, null, 0);
        } else {
            activityManager().startActivityAsUser(
                    null, callerPackage, intent, null, null, null, 0, 0, null, null, 0);
        }
    }

    @Override
    public void forceStopPackage(String packageName) {
        activityManager().forceStopPackage(packageName, 0);
    }

    //
    // Package Manager
    //

    private synchronized IPackageManager packageManager() {
        if (packageManager == null) {
            packageManager = IPackageManager.Stub.asInterface(getBinder("package"));
        }
        return packageManager;
    }

    @Override
    public void grantPermission(String permissionName) {
        packageManager().grantRuntimePermission(BuildConfig.APPLICATION_ID, permissionName, 0);
    }

    //
    // Storage Manager
    //

    private synchronized IStorageManager storageManager() {
        if (storageManager == null) {
            storageManager = IStorageManager.Stub.asInterface(getBinder("mount"));
        }
        return storageManager;
    }

    @Override
    public List<VolumeInfo> getVolumes() {
        return new ArrayList<>(Arrays.asList(storageManager().getVolumes(0)));
    }

    @Override
    public void unmount(String volId) {
        storageManager().unmount(volId);
    }

    @Override
    public void mount(String volId) {
        storageManager().mount(volId);
    }

    //
    // Dynamic System
    //

    private synchronized IDynamicSystemService dynamicSystem() {
        if (dynamicSystem == null) {
            dynamicSystem = IDynamicSystemService.Stub.asInterface(getBinder("dynamic_system"));
        }
        return dynamicSystem;
    }

    // REQUIRES MANAGE_DYNAMIC_SYSTEM
    @Override
    public boolean closePartition() {
        // closePartition() only exists since Android S, on R the partition is closed implicitly
        if (Build.VERSION.SDK_INT <= 30) {
            return true;
        }
        return dynamicSystem().closePartition();
    }

    // REQUIRES MANAGE_DYNAMIC_SYSTEM
    @Override
    public boolean finishInstallation() {
        return dynamicSystem().finishInstallation();
    }

    // REQUIRES MANAGE_DYNAMIC_SYSTEM
    @Override
    public GsiProgress getInstallationProgress() {
        return dynamicSystem().getInstallationProgress();
    }

    // REQUIRES MANAGE_DYNAMIC_SYSTEM
    @Override
    public boolean abort() {
        return dynamicSystem().abort();
    }

    // REQUIRES MANAGE_DYNAMIC_SYSTEM
    @Override
    public boolean isEnabled() {
        return dynamicSystem().isEnabled();
    }

    // REQUIRES MANAGE_DYNAMIC_SYSTEM
    @Override
    public boolean remove() {
        return dynamicSystem().remove();
    }

    // REQUIRES MANAGE_DYNAMIC_SYSTEM
    @Override
    public boolean setEnable(boolean enable, boolean oneShot) {
        return dynamicSystem().setEnable(enable, oneShot);
    }

    // REQUIRES MANAGE_DYNAMIC_SYSTEM
    @Override
    public boolean startInstallation(String dsuSlot) {
        return dynamicSystem().startInstallation(dsuSlot);
    }

    // REQUIRES MANAGE_DYNAMIC_SYSTEM
    @Override
    public int createPartition(String name, long size, boolean readOnly) {
        IDynamicSystemService service = dynamicSystem();

        // Below Android T, createPartition() returns a boolean instead of an install status
        if (Build.VERSION.SDK_INT < 33) {
            try {
                Object result = HiddenApiBypass.invoke(
                        service.getClass(), service, "createPartition", name, size, readOnly);
                return Boolean.TRUE.equals(result)
                        ? IGsiService.INSTALL_OK
                        : IGsiService.INSTALL_ERROR_GENERIC;
            } catch (ReflectiveOperationException e) {
                Log.e(TAG, "Unable to create partition: " + name, e);
                return IGsiService.INSTALL_ERROR_GENERIC;
            }
        }
        return service.createPartition(name, size, readOnly);
    }

    // REQUIRES MANAGE_DYNAMIC_SYSTEM
    @Override
    public boolean setAshmem(ParcelFileDescriptor fd, long size) {
        return dynamicSystem().setAshmem(fd, size);
    }

    // REQUIRES MANAGE_DYNAMIC_SYSTEM
    @Override
    public boolean submitFromAshmem(long bytes) {
        return dynamicSystem().submitFromAshmem(bytes);
    }

    // REQUIRES MANAGE_DYNAMIC_SYSTEM
    @Override
    public long suggestScratchSize() {
        return dynamicSystem().suggestScratchSize();
    }

    @Override
    public boolean isInUse() {
        return dynamicSystem().isInUse();
    }

    @Override
    public boolean isInstalled() {
        return dynamicSystem().isInstalled();
    }
}
