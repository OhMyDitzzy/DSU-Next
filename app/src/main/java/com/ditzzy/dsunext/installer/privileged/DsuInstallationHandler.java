package com.ditzzy.dsunext.installer.privileged;

import android.content.Intent;
import android.net.Uri;
import android.os.RemoteException;
import android.os.storage.VolumeInfo;
import android.util.Log;

import com.ditzzy.dsunext.model.Session;
import com.ditzzy.dsunext.service.PrivilegedProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Installs images through the DSU app (com.android.dynsystem).
 * Supported modes are: Shizuku (as shell or root), root and system.
 */
public final class DsuInstallationHandler {

    private static final String TAG = "DsuInstallationHandler";
    private static final String DSU_PACKAGE = "com.android.dynsystem";
    private static final long REMOUNT_DELAY_SECONDS = 30L;

    private static final ScheduledExecutorService REMOUNT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor();

    private final Session session;

    public DsuInstallationHandler(Session session) {
        this.session = session;
    }

    /** May block while talking to the service, so it must be called from a background thread. */
    public void startInstallation() throws RemoteException {
        if (session.getPreferences().isUnmountSdCard()) {
            unmountSdTemporary();
        }
        forwardInstallationToDsu();
    }

    private void forwardInstallationToDsu() {
        long userdataSize = session.getUserSelection().getUserSelectedUserdata();
        Uri fileUri = session.getDsuInstallation().getUri();
        long length = session.getDsuInstallation().getFileSize();

        PrivilegedProvider.run(service -> {
            service.setDynProp();
            service.forceStopPackage(DSU_PACKAGE);

            Intent dynIntent = new Intent();
            dynIntent.setClassName(DSU_PACKAGE, DSU_PACKAGE + ".VerificationActivity");
            dynIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            dynIntent.setAction("android.os.image.action.START_INSTALL");
            dynIntent.setData(fileUri);
            dynIntent.putExtra("KEY_USERDATA_SIZE", userdataSize);
            dynIntent.putExtra("KEY_SYSTEM_SIZE", length);

            Log.d(TAG, "Starting DSU VerificationActivity: " + dynIntent);
            service.startActivity(dynIntent);
        });
    }

    // The SD card is ejected while DSU allocates, so gsid doesn't pick it as the target
    private void unmountSdTemporary() throws RemoteException {
        List<VolumeInfo> volumes = PrivilegedProvider.getService().getVolumes();
        List<String> unmounted = new ArrayList<>();

        for (VolumeInfo volume : volumes) {
            if (volume.id.contains("public")) {
                String volumeId = volume.id;
                PrivilegedProvider.run(service -> service.unmount(volumeId));
                unmounted.add(volumeId);
                Log.d(TAG, "Volume unmounted: " + volumeId);
            }
        }

        if (!unmounted.isEmpty()) {
            REMOUNT_SCHEDULER.schedule(() -> {
                for (String volumeId : unmounted) {
                    Log.d(TAG, "Volume remounted: " + volumeId);
                    PrivilegedProvider.run(service -> service.mount(volumeId));
                }
            }, REMOUNT_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }
}
