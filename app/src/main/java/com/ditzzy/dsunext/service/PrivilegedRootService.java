package com.ditzzy.dsunext.service;

import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.ipc.RootService;

/** Hosts {@link PrivilegedService} in a root process. */
public final class PrivilegedRootService extends RootService {

    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return new PrivilegedService();
    }
}
