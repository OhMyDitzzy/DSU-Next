package com.ditzzy.dsunext.viewmodel;

import android.app.Application;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.service.WorkspaceService;
import com.ditzzy.dsunext.workspace.WorkspaceJobs;
import com.ditzzy.dsunext.model.Workspace;
import com.ditzzy.dsunext.model.WorkspaceInfo;
import com.ditzzy.dsunext.ui.Event;
import com.ditzzy.dsunext.ui.UiMessage;
import com.ditzzy.dsunext.workspace.WorkspaceNames;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

/* The view model keeps the workspace list and running job across rotations. */
public final class EditGsiViewModel extends AndroidViewModel {

    private static final String TAG = "EditGsiViewModel";
    private static final int MAX_LOG_CHARS = 48 * 1024;

    public enum Access { CHECKING, SERVICE_ERROR, READY }

    /** What the progress dialog shows. Immutable, every change is a new instance. */
    public static final class Operation {

        public enum Type { IMPORT, REPACK, EXPORT, DELETE }

        public enum Status { RUNNING, DONE, FAILED }

        public final Type type;
        public final String workspace;
        public final Status status;
        /** 0-100, or -1 when there is nothing to measure. */
        public final int percent;
        public final String log;
        /** The reason of a failure. */
        public final String message;
        /** Result of a repack: the image that was written. */
        public final String file;
        public final long size;
        public final int newEntries;

        private Operation(Type type, String workspace, Status status, int percent, String log,
                String message, String file, long size, int newEntries) {
            this.type = type;
            this.workspace = workspace;
            this.status = status;
            this.percent = percent;
            this.log = log;
            this.message = message;
            this.file = file;
            this.size = size;
            this.newEntries = newEntries;
        }

        static Operation running(Type type, String workspace) {
            return new Operation(type, workspace, Status.RUNNING, -1, "", "", "", 0L, 0);
        }

        Operation withPercent(int next) {
            return new Operation(type, workspace, status, next, log, message, file, size, newEntries);
        }

        Operation withLog(String line) {
            String next = log.isEmpty() ? line : log + "\n" + line;
            if (next.length() > MAX_LOG_CHARS) {
                next = next.substring(next.length() - MAX_LOG_CHARS);
            }
            return new Operation(type, workspace, status, percent, next, message, file, size, newEntries);
        }

        Operation done(String resultFile, long resultSize, int resultNewEntries) {
            return new Operation(type, workspace, Status.DONE, 100, log, "", resultFile, resultSize,
                    resultNewEntries);
        }

        Operation failed(String reason) {
            return new Operation(type, workspace, Status.FAILED, percent, log, reason, file, size,
                    newEntries);
        }
    }

    private final MutableLiveData<Access> access = new MutableLiveData<>(Access.CHECKING);
    private final MutableLiveData<String> accessDetail = new MutableLiveData<>("");
    private final MutableLiveData<List<Workspace>> workspaces =
            new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<Operation> operation = new MutableLiveData<>();
    private final MutableLiveData<Event<UiMessage>> messages = new MutableLiveData<>();
    private final MutableLiveData<Event<WorkspaceInfo>> info = new MutableLiveData<>();

    private final ExecutorService io = WorkspaceJobs.executor();
    private final Object operationLock = new Object();

    private final WorkspaceService service;
    private Operation current;
    private boolean started;
    private boolean warningAccepted;

    private final WorkspaceService.Listener callback = new WorkspaceService.Listener() {
        @Override
        public void onLog(String line) {
            Operation next;
            synchronized (operationLock) {
                if (current == null || current.status != Operation.Status.RUNNING) {
                    return;
                }
                current = current.withLog(line);
                next = current;
            }
            operation.postValue(next);
        }

        @Override
        public void onProgress(int percent) {
            Operation next;
            synchronized (operationLock) {
                if (current == null || current.status != Operation.Status.RUNNING) {
                    return;
                }
                current = current.withPercent(percent);
                next = current;
            }
            operation.postValue(next);
        }

        @Override
        public void onFinished(String resultJson) {
            onJobFinished(resultJson);
        }

        @Override
        public void onError(String message) {
            failJob(message);
        }
    };

    public EditGsiViewModel(@NonNull Application application) {
        super(application);
        service = new WorkspaceService(application);
    }

    public LiveData<Access> getAccess() {
        return access;
    }

    public LiveData<String> getAccessDetail() {
        return accessDetail;
    }

    public LiveData<List<Workspace>> getWorkspaces() {
        return workspaces;
    }

    public LiveData<Operation> getOperation() {
        return operation;
    }

    public LiveData<Event<UiMessage>> getMessages() {
        return messages;
    }

    public LiveData<Event<WorkspaceInfo>> getInfo() {
        return info;
    }

    public boolean isWarningAccepted() {
        return warningAccepted;
    }

    public void acceptWarning() {
        warningAccepted = true;
    }

    public void start() {
        if (started) return;
        started = true;
        checkAccess();
    }

    public void checkAccess() {
        access.setValue(Access.CHECKING);
        verifyService();
    }

    private void verifyService() {
        io.execute(() -> {
            WorkspaceService s = service;
            if (s == null) {
                return;
            }
            try {
                String error = s.getNativeError();
                if (error != null && !error.isEmpty()) {
                    accessDetail.postValue(error);
                    access.postValue(Access.SERVICE_ERROR);
                    return;
                }
                access.postValue(Access.READY);
                loadWorkspaces(s);
            } catch (Exception e) {
                Log.e(TAG, "Unable to open workspace storage.", e);
                accessDetail.postValue(String.valueOf(e.getMessage()));
                access.postValue(Access.SERVICE_ERROR);
            }
        });
    }

    public void refresh() {
        io.execute(() -> loadWorkspaces(service));
    }

    private void loadWorkspaces(@Nullable WorkspaceService s) {
        if (s == null) {
            return;
        }
        try {
            workspaces.postValue(Workspace.parseList(s.listWorkspaces()));
        } catch (Exception e) {
            Log.e(TAG, "Unable to read the workspace list.", e);
            postMessage(new UiMessage(R.string.gsi_list_failed));
        }
    }

    public boolean isNameTaken(String name) {
        for (Workspace workspace : currentNames()) {
            if (workspace.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** {@code base} with a number added when a workspace of that name already exists. */
    public String uniqueName(String base) {
        List<String> taken = new ArrayList<>();
        for (Workspace workspace : currentNames()) {
            taken.add(workspace.getName());
        }
        return WorkspaceNames.unique(base, taken);
    }

    private List<Workspace> currentNames() {
        List<Workspace> list = workspaces.getValue();
        return list == null ? Collections.emptyList() : list;
    }

    public void loadInfo(String name) {
        io.execute(() -> {
            WorkspaceService s = service;
            if (s == null) {
                postMessage(new UiMessage(R.string.gsi_service_unavailable));
                return;
            }
            try {
                String json = s.getWorkspaceInfo(name);
                if (json == null || json.isEmpty()) {
                    postMessage(new UiMessage(R.string.gsi_info_failed));
                    return;
                }
                info.postValue(new Event<>(WorkspaceInfo.parse(json)));
            } catch (Exception e) {
                Log.e(TAG, "Unable to read the workspace details.", e);
                postMessage(new UiMessage(R.string.gsi_info_failed));
            }
        });
    }

    public void importFile(Uri uri, String sourceName, String name) {
        if (!begin(Operation.Type.IMPORT, name)) return;
        io.execute(() -> {
            WorkspaceService s = service;
            if (s == null) {
                failJob(text(R.string.gsi_service_unavailable));
                return;
            }
            try (ParcelFileDescriptor pfd =
                         getApplication().getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) {
                    failJob(text(R.string.gsi_open_file_failed));
                    return;
                }
                s.importWorkspace(pfd, sourceName, name, callback);
            } catch (Exception e) {
                Log.e(TAG, "Unable to start the import.", e);
                failJob(describe(e));
            }
        });
    }

    /** @param size null for the original size, "auto", or bytes with an optional K, M or G. */
    public void repack(String name, @Nullable String size, String outputName) {
        if (!begin(Operation.Type.REPACK, name)) return;
        io.execute(() -> {
            WorkspaceService s = service;
            if (s == null) {
                failJob(text(R.string.gsi_service_unavailable));
                return;
            }
            try {
                s.repackWorkspace(name, size, outputName, callback);
            } catch (Exception e) {
                Log.e(TAG, "Unable to start the repack.", e);
                failJob(describe(e));
            }
        });
    }

    /** Copies a repacked image to the file the user chose. */
    public void export(String name, String fileName, Uri target) {
        if (!begin(Operation.Type.EXPORT, name)) return;
        io.execute(() -> {
            WorkspaceService s = service;
            if (s == null) {
                failJob(text(R.string.gsi_service_unavailable));
                return;
            }
            try (ParcelFileDescriptor pfd =
                         getApplication().getContentResolver().openFileDescriptor(target, "wt")) {
                if (pfd == null) {
                    failJob(text(R.string.gsi_open_file_failed));
                    return;
                }
                s.exportOutput(name, fileName, pfd, callback);
            } catch (Exception e) {
                Log.e(TAG, "Unable to start saving the image.", e);
                failJob(describe(e));
            }
        });
    }

    public void delete(String name) {
        if (!begin(Operation.Type.DELETE, name)) return;
        io.execute(() -> {
            WorkspaceService s = service;
            if (s == null) {
                failJob(text(R.string.gsi_service_unavailable));
                return;
            }
            try {
                boolean deleted = s.deleteWorkspace(name);
                loadWorkspaces(s);
                if (deleted) {
                    finishQuietly(new UiMessage(R.string.gsi_deleted, name));
                } else {
                    failJob(getApplication().getString(R.string.gsi_delete_failed, name));
                }
            } catch (Exception e) {
                Log.e(TAG, "Unable to delete the workspace.", e);
                failJob(describe(e));
            }
        });
    }

    /** Closes the dialog of a finished or failed job. */
    public void dismissOperation() {
        synchronized (operationLock) {
            if (current != null && current.status == Operation.Status.RUNNING) {
                return;
            }
            current = null;
        }
        operation.postValue(null);
    }

    private void publish(Operation next) {
        synchronized (operationLock) {
            current = next;
        }
        operation.postValue(next);
    }

    private void onJobFinished(String resultJson) {
        Operation finished;
        synchronized (operationLock) {
            if (current == null) {
                return;
            }
            finished = current;
        }
        switch (finished.type) {
            case REPACK:
                String file = "";
                long size = 0L;
                int newEntries = 0;
                try {
                    JSONObject result = new JSONObject(resultJson);
                    file = result.optString("file", "");
                    size = result.optLong("size", 0L);
                    newEntries = result.optInt("new_entries", 0);
                } catch (JSONException e) {
                    Log.w(TAG, "Unreadable repack result.", e);
                }
                publish(finished.done(file, size, newEntries));
                break;
            case IMPORT:
                finishQuietly(new UiMessage(R.string.gsi_imported, finished.workspace));
                refresh();
                break;
            case EXPORT:
                finishQuietly(new UiMessage(R.string.gsi_saved));
                break;
            default:
                finishQuietly(null);
                break;
        }
    }

    private void failJob(String reason) {
        Operation base;
        synchronized (operationLock) {
            if (current == null) {
                return;
            }
            base = current;
        }
        publish(base.failed(reason));
        // An import that failed leaves nothing behind, but the list may have changed anyway
        refresh();
    }

    /** Ends a job that has nothing more to show than a toast. */
    private void finishQuietly(@Nullable UiMessage message) {
        synchronized (operationLock) {
            current = null;
        }
        operation.postValue(null);
        if (message != null) {
            postMessage(message);
        }
    }

    private void postMessage(UiMessage message) {
        messages.postValue(new Event<>(message));
    }

    private String text(int resId) {
        return getApplication().getString(resId);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private boolean begin(Operation.Type type, String name) {
        synchronized (operationLock) {
            if (current != null && current.status == Operation.Status.RUNNING) return false;
            current = Operation.running(type, name);
            operation.postValue(current);
            return true;
        }
    }
}
