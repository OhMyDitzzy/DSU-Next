package com.ditzzy.dsunext.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

/** Hosts {@link PrivilegedService} when the app itself runs as a privileged system app. */
public final class PrivilegedSystemService extends Service {

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new PrivilegedService();
    }
}
