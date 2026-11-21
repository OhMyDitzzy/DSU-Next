package com.ditzzy.dsunext.workspace;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WorkspaceJobs {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private WorkspaceJobs() {}

    public static ExecutorService executor() {
        return WORKER;
    }
}
