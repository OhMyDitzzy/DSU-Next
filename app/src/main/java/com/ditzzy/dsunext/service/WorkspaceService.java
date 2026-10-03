package com.ditzzy.dsunext.service;

import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import com.ditzzy.dsunext.IWorkspaceCallback;
import com.ditzzy.dsunext.IWorkspaceService;
import com.ditzzy.dsunext.workspace.ImageImporter;
import com.ditzzy.dsunext.workspace.ImageRepacker;
import com.ditzzy.dsunext.workspace.JobReporter;
import com.ditzzy.dsunext.workspace.OutputExporter;
import com.ditzzy.dsunext.workspace.WorkspaceStore;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs as root and does the file work of the "Edit your GSI" screen. Jobs that take a while run
 * one at a time on a worker thread and report back through the callback they were started with.
 */
public final class WorkspaceService extends IWorkspaceService.Stub {

    private static final String TAG = "WorkspaceService";

    private interface Job {
        String run() throws Exception;
    }

    private interface RemoteCall {
        void call() throws RemoteException;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // This constructor must stay public and parameterless, libsu instantiates it by name
    public WorkspaceService() {
    }

    @Override
    public String getNativeError() {
        int uid = Process.myUid();
        if (uid != 0) {
            return "The service is not running as root (uid " + uid + ")";
        }
        return WorkspaceStore.nativeError();
    }

    @Override
    public String listWorkspaces() {
        try {
            return WorkspaceStore.listJson();
        } catch (Exception e) {
            Log.e(TAG, "Unable to list the workspaces.", e);
            return "[]";
        }
    }

    @Override
    public String getWorkspaceInfo(String name) {
        try {
            return WorkspaceStore.infoJson(name);
        } catch (Exception e) {
            Log.e(TAG, "Unable to read the workspace info of " + name, e);
            return "";
        }
    }

    @Override
    public boolean deleteWorkspace(String name) {
        try {
            return WorkspaceStore.delete(name);
        } catch (Exception e) {
            Log.e(TAG, "Unable to delete the workspace " + name, e);
            return false;
        }
    }

    @Override
    public void importWorkspace(ParcelFileDescriptor source, String sourceName,
            String workspaceName, IWorkspaceCallback callback) {
        worker.execute(() -> execute(callback, source,
                () -> ImageImporter.run(source, sourceName, workspaceName, reporter(callback))));
    }

    @Override
    public void repackWorkspace(String workspaceName, String size, String outputName,
            IWorkspaceCallback callback) {
        worker.execute(() -> execute(callback, null,
                () -> ImageRepacker.run(workspaceName, size, outputName, reporter(callback))));
    }

    @Override
    public void exportOutput(String workspaceName, String fileName, ParcelFileDescriptor target,
            IWorkspaceCallback callback) {
        worker.execute(() -> execute(callback, target,
                () -> OutputExporter.run(workspaceName, fileName, target, reporter(callback))));
    }

    private void execute(IWorkspaceCallback callback, ParcelFileDescriptor toClose, Job job) {
        try {
            String result = job.run();
            send(() -> callback.onFinished(result));
        } catch (Throwable t) {
            Log.e(TAG, "Workspace job failed.", t);
            send(() -> callback.onError(describe(t)));
        } finally {
            if (toClose != null) {
                try {
                    toClose.close();
                } catch (IOException ignored) {
                    // Jobs close what they own, this only covers a job that never started
                }
            }
        }
    }

    private static JobReporter reporter(IWorkspaceCallback callback) {
        return new JobReporter() {
            @Override
            public void log(String line) {
                send(() -> callback.onLog(line));
            }

            @Override
            public void progress(int percent) {
                send(() -> callback.onProgress(percent));
            }
        };
    }

    // The app may be gone by the time a job ends, which is not an error worth failing the job for
    private static void send(RemoteCall call) {
        try {
            call.call();
        } catch (RemoteException e) {
            Log.w(TAG, "The app is no longer listening.", e);
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (t instanceof IOException && message != null && !message.isEmpty()) {
            return message;
        }
        return message == null || message.isEmpty()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }
}
