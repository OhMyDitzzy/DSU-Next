package com.ditzzy.dsunext.service;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.ditzzy.dsunext.IPrivilegedService;

public final class Connection implements ServiceConnection {

    private volatile IPrivilegedService service;

    public IPrivilegedService getService() {
        return service;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = IPrivilegedService.Stub.asInterface(binder);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }
}
