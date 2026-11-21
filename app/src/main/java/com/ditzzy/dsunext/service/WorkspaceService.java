package com.ditzzy.dsunext.service;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.ditzzy.dsunext.workspace.ImageImporter;
import com.ditzzy.dsunext.workspace.ImageRepacker;
import com.ditzzy.dsunext.workspace.JobReporter;
import com.ditzzy.dsunext.workspace.OutputExporter;
import com.ditzzy.dsunext.workspace.WorkspaceJobs;
import com.ditzzy.dsunext.workspace.WorkspaceStore;

import java.io.IOException;

/* Image jobs and browser edits share one queue so repacking sees a stable workspace. */
public final class WorkspaceService {
    private static final String TAG = "WorkspaceService";

    public interface Listener {
        void onLog(String line);
        void onProgress(int percent);
        void onFinished(String result);
        void onError(String message);
    }

    private interface Job { String run() throws Exception; }

    public WorkspaceService(Context context) {
        WorkspaceStore.initialize(context);
    }

    public String getNativeError() throws IOException {
        if (!WorkspaceStore.baseDir().isDirectory() && !WorkspaceStore.baseDir().mkdirs()) {
            throw new IOException("Cannot create workspace storage");
        }
        return WorkspaceStore.nativeError();
    }

    public String listWorkspaces() throws Exception {
        return WorkspaceStore.listJson();
    }

    public String getWorkspaceInfo(String name) throws Exception {
        return WorkspaceStore.infoJson(name);
    }

    public boolean deleteWorkspace(String name) throws IOException {
        return WorkspaceStore.delete(name);
    }

    public void importWorkspace(ParcelFileDescriptor source, String sourceName,
            String name, Listener listener) throws IOException {
        // The picker descriptor can close as soon as this call returns; the queued job owns a duplicate.
        ParcelFileDescriptor owned = ParcelFileDescriptor.dup(source.getFileDescriptor());
        enqueue(owned, listener, () -> ImageImporter.run(owned, sourceName, name, reporter(listener)));
    }

    public void repackWorkspace(String name, String size, String outputName, Listener listener) {
        enqueue(null, listener, () -> ImageRepacker.run(name, size, outputName, reporter(listener)));
    }

    public void exportOutput(String name, String fileName, ParcelFileDescriptor target,
            Listener listener) throws IOException {
        ParcelFileDescriptor owned = ParcelFileDescriptor.dup(target.getFileDescriptor());
        enqueue(owned, listener, () -> OutputExporter.run(name, fileName, owned, reporter(listener)));
    }

    private void enqueue(ParcelFileDescriptor owned, Listener listener, Job job) {
        WorkspaceJobs.executor().execute(() -> {
            try {
                listener.onFinished(job.run());
            } catch (Exception error) {
                Log.e(TAG, "Workspace job failed", error);
                listener.onError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            } finally {
                if (owned != null) {
                    try {
                        owned.close();
                    } catch (IOException ignored) {
                        // Import/export normally closes the descriptor through its stream.
                    }
                }
            }
        });
    }

    private static JobReporter reporter(Listener listener) {
        return new JobReporter() {
            @Override public void log(String line) { listener.onLog(line); }
            @Override public void progress(int percent) { listener.onProgress(percent); }
        };
    }
}
