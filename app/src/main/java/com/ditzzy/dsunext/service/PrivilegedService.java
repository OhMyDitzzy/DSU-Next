package com.ditzzy.dsunext.service;

import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.gsi.GsiProgress;
import android.gsi.IGsiService;
import android.os.Binder;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    private static final int PER_USER_RANGE = 100_000;
    private static final long SHELL_TIMEOUT_SECONDS = 15L;

    // Arguments: $1 user id, $2 package, $3 permission, $4 component to restart (may be empty).
    //
    // The restart is chained to a successful grant inside the script itself, and its output is
    // detached from ours, for two reasons: granting READ_LOGS makes the system kill the app, and
    // Shizuku tears a non-daemon service down once its client is gone. A restart driven from
    // Java could be cut short by either, while the shell child simply carries on.
    private static final String GRANT_SCRIPT =
            "out=$(/system/bin/cmd package grant --user \"$1\" \"$2\" \"$3\" 2>&1); rc=$?\n"
                    + "[ -n \"$out\" ] && echo \"$out\"\n"
                    + "if [ $rc -eq 0 ] && [ -n \"$4\" ]; then\n"
                    + "  ( /system/bin/sleep 1;"
                    + " /system/bin/cmd activity force-stop \"$2\";"
                    + " /system/bin/cmd activity start --user \"$1\" -n \"$4\" )"
                    + " >/dev/null 2>&1 </dev/null &\n"
                    + "fi\n"
                    + "exit $rc";

    private IActivityManager activityManager;
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
    // Permissions
    //

    /**
     * Grants {@code permission} to this app by running {@code pm grant} as the service's own user
     * (shell through Shizuku, or root).
     *
     * <p>Shizuku used to offer {@code Shizuku.newProcess()} for this, but it is not part of the
     * public API anymore. Running the command from the user service is the supported way, and it
     * is stable across Android releases, unlike the hidden binder call this used before
     * ({@code IPackageManager.grantRuntimePermission()}), whose signature and home vary by release.
     */
    @Override
    public boolean grantPermission(String permission, ComponentName restart) {
        // The caller is the app itself, so its uid tells which user it is installed for
        int userId = Binder.getCallingUid() / PER_USER_RANGE;
        String component = restart != null ? restart.flattenToShortString() : "";

        try {
            ShellResult result = runShell(
                    "/system/bin/sh", "-c", GRANT_SCRIPT, "dsunext",
                    String.valueOf(userId), BuildConfig.APPLICATION_ID, permission, component);
            if (result.exitCode == 0) {
                Log.i(TAG, "Granted " + permission + " (user " + userId + ")");
                return true;
            }
            Log.e(TAG, "Unable to grant " + permission + ", exit code " + result.exitCode
                    + ": " + result.output);
        } catch (IOException e) {
            Log.e(TAG, "Unable to run the grant command.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Interrupted while granting " + permission, e);
        }
        return false;
    }

    private static final class ShellResult {
        final int exitCode;
        final String output;

        ShellResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static ShellResult runShell(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();

        // The output is tiny, so it's safe to wait first and read afterwards
        if (!process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new ShellResult(-1, "Timed out after " + SHELL_TIMEOUT_SECONDS + "s");
        }
        return new ShellResult(process.exitValue(), readAll(process.getInputStream()).trim());
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
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
