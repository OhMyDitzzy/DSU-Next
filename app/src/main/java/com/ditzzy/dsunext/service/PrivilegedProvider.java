package com.ditzzy.dsunext.service;

import android.util.Log;

import androidx.annotation.Nullable;

import com.ditzzy.dsunext.IPrivilegedService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single access point to the {@link IPrivilegedService}, no matter how it got bound
 * (Shizuku, root or as a system app).
 */
public final class PrivilegedProvider {

    private static final String TAG = "PrivilegedProvider";
    private static final long TIMEOUT_MS = 20_000L;
    private static final long POLL_INTERVAL_MS = 250L;

    private static final Connection CONNECTION = new Connection();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private PrivilegedProvider() {
    }

    public interface ServiceAction {
        void run(IPrivilegedService service) throws Exception;
    }

    public static Connection getConnection() {
        return CONNECTION;
    }

    public static boolean isConnected() {
        return CONNECTION.getService() != null;
    }

    public static void run(ServiceAction action) {
        run(action, null);
    }

    /**
     * Runs {@code action} in a background thread as soon as the service is available.
     *
     * @param onFail called when the service did not show up in time.
     */
    public static void run(ServiceAction action, @Nullable Runnable onFail) {
        EXECUTOR.execute(() -> {
            IPrivilegedService service = awaitService();
            if (service == null) {
                Log.e(TAG, "Service unavailable.");
                if (onFail != null) {
                    onFail.run();
                }
                return;
            }
            try {
                action.run(service);
            } catch (Exception e) {
                Log.e(TAG, "Privileged action failed.", e);
            }
        });
    }

    /**
     * Blocking variant, must never be called from the main thread.
     *
     * @throws IllegalStateException when the service is not available after the timeout.
     */
    public static IPrivilegedService getService() {
        IPrivilegedService service = awaitService();
        if (service == null) {
            throw new IllegalStateException("Service unavailable.");
        }
        return service;
    }

    @Nullable
    private static IPrivilegedService awaitService() {
        long waited = 0L;
        IPrivilegedService service = CONNECTION.getService();
        while (service == null) {
            if (waited >= TIMEOUT_MS) {
                return null;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            waited += POLL_INTERVAL_MS;
            service = CONNECTION.getService();
        }
        return service;
    }
}
