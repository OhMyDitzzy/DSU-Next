package com.ditzzy.dsunext.util;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal cancellation token shared between the UI and the worker threads.
 * Workers are expected to poll {@link #isCancelled()} between units of work.
 */
public final class InstallationJob {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
