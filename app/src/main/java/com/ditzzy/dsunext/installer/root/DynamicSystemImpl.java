package com.ditzzy.dsunext.installer.root;

import android.gsi.GsiProgress;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.image.IDynamicSystemService;

import com.ditzzy.dsunext.IPrivilegedService;
import com.ditzzy.dsunext.service.PrivilegedProvider;

/**
 * Proxies every call of the DynamicSystem API to the privileged service, so the installer can be
 * written as if it were talking to {@link IDynamicSystemService} directly.
 */
public abstract class DynamicSystemImpl implements IDynamicSystemService {

    private interface RemoteCall<T> {
        T call(IPrivilegedService service) throws RemoteException;
    }

    // IDynamicSystemService doesn't declare RemoteException, so it is rethrown unchecked
    private static <T> T remote(RemoteCall<T> call) {
        try {
            return call.call(PrivilegedProvider.getService());
        } catch (RemoteException e) {
            throw new IllegalStateException("Privileged service call failed.", e);
        }
    }

    // There is no real binder behind this implementation
    @Override
    public IBinder asBinder() {
        return null;
    }

    @Override
    public GsiProgress getInstallationProgress() {
        return remote(IPrivilegedService::getInstallationProgress);
    }

    @Override
    public boolean abort() {
        return remote(IPrivilegedService::abort);
    }

    @Override
    public boolean isInUse() {
        return remote(IPrivilegedService::isInUse);
    }

    @Override
    public boolean isInstalled() {
        return remote(IPrivilegedService::isInstalled);
    }

    @Override
    public boolean isEnabled() {
        return remote(IPrivilegedService::isEnabled);
    }

    @Override
    public boolean remove() {
        return remote(IPrivilegedService::remove);
    }

    @Override
    public boolean setEnable(boolean enable, boolean oneShot) {
        return remote(service -> service.setEnable(enable, oneShot));
    }

    @Override
    public boolean finishInstallation() {
        return remote(IPrivilegedService::finishInstallation);
    }

    @Override
    public boolean startInstallation(String dsuSlot) {
        return remote(service -> service.startInstallation(dsuSlot));
    }

    @Override
    public int createPartition(String name, long size, boolean readOnly) {
        return remote(service -> service.createPartition(name, size, readOnly));
    }

    @Override
    public boolean closePartition() {
        return remote(IPrivilegedService::closePartition);
    }

    @Override
    public boolean setAshmem(ParcelFileDescriptor fd, long size) {
        return remote(service -> service.setAshmem(fd, size));
    }

    @Override
    public boolean submitFromAshmem(long bytes) {
        return remote(service -> service.submitFromAshmem(bytes));
    }

    @Override
    public long suggestScratchSize() {
        return remote(IPrivilegedService::suggestScratchSize);
    }

    public void forceStopDsu() {
        remote(service -> {
            service.forceStopPackage("com.android.dynsystem");
            return null;
        });
    }

    public void setDynProp() {
        remote(service -> {
            service.setDynProp();
            return null;
        });
    }
}
