package com.ditzzy.dsunext.service;

import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.ipc.RootService;

/** Hosts {@link WorkspaceService} in a root process, for the "Edit your GSI" screen. */
public final class WorkspaceRootService extends RootService {

    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return new WorkspaceService();
    }
}
