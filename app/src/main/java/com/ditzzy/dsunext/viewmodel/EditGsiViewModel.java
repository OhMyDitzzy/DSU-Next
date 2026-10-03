package com.ditzzy.dsunext.viewmodel;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ditzzy.dsunext.IWorkspaceCallback;
import com.ditzzy.dsunext.IWorkspaceService;
import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.model.Workspace;
import com.ditzzy.dsunext.model.WorkspaceInfo;
import com.ditzzy.dsunext.service.WorkspaceRootService;
import com.ditzzy.dsunext.ui.Event;
import com.ditzzy.dsunext.ui.UiMessage;
import com.ditzzy.dsunext.workspace.WorkspaceNames;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * State of the "Edit your GSI" screen: root detection, the connection to the root service, the
 * workspace list and the one job that can run at a time. It outlives rotations, so a running
 * job keeps going and its dialog is simply shown again.
 */
public final class EditGsiViewModel extends AndroidViewModel {

    private static final String TAG = "EditGsiViewModel";
    private static final int MAX_LOG_CHARS = 48 * 1024;

    public enum Access { CHECKING, NO_ROOT, SERVICE_ERROR, READY }

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

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object operationLock = new Object();

    private volatile IWorkspaceService service;
    private Operation current;
    private boolean bound;
    private boolean started;
    private boolean warningAccepted;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IWorkspaceService.Stub.asInterface(binder);
            verifyService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            accessDetail.postValue(getApplication().getString(R.string.gsi_service_disconnected));
            access.postValue(Access.SERVICE_ERROR);
        }
    };

    private final IWorkspaceCallback.Stub callback = new IWorkspaceCallback.Stub() {
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

    /** Starts the root check once, entering the screen again from a rotation does nothing. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        checkRoot();
    }

    /** Must be called on the main thread. */
    public void checkRoot() {
        access.setValue(Access.CHECKING);

        // A shell that fell back to a plain one stays cached, a retry has to recycle it
        Shell cached = Shell.getCachedShell();
        if (cached != null && !cached.isRoot()) {
            try {
                cached.close();
            } catch (IOException ignored) {
                // A new shell is requested right below either way
            }
        }
        Shell.getShell(shell -> {
            if (shell.isRoot()) {
                connect();
            } else {
                access.setValue(Access.NO_ROOT);
            }
        });
    }

    private void connect() {
        if (service != null) {
            verifyService();
            return;
        }
        if (bound) {
            // Bound once but the root process went away, start over
            RootService.unbind(connection);
            bound = false;
        }
        RootService.bind(new Intent(getApplication(), WorkspaceRootService.class), connection);
        bound = true;
    }

    private void verifyService() {
        io.execute(() -> {
            IWorkspaceService s = service;
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
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to talk to the root service.", e);
                accessDetail.postValue(String.valueOf(e.getMessage()));
                access.postValue(Access.SERVICE_ERROR);
            }
        });
    }

    public void refresh() {
        io.execute(() -> loadWorkspaces(service));
    }

    private void loadWorkspaces(@Nullable IWorkspaceService s) {
        if (s == null) {
            return;
        }
        try {
            workspaces.postValue(Workspace.parseList(s.listWorkspaces()));
        } catch (RemoteException | JSONException e) {
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
            IWorkspaceService s = service;
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
            } catch (RemoteException | JSONException e) {
                Log.e(TAG, "Unable to read the workspace details.", e);
                postMessage(new UiMessage(R.string.gsi_info_failed));
            }
        });
    }

    public void importFile(Uri uri, String sourceName, String name) {
        publish(Operation.running(Operation.Type.IMPORT, name));
        io.execute(() -> {
            IWorkspaceService s = service;
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
        publish(Operation.running(Operation.Type.REPACK, name));
        io.execute(() -> {
            IWorkspaceService s = service;
            if (s == null) {
                failJob(text(R.string.gsi_service_unavailable));
                return;
            }
            try {
                s.repackWorkspace(name, size, outputName, callback);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to start the repack.", e);
                failJob(describe(e));
            }
        });
    }

    /** Copies a repacked image to the file the user chose. */
    public void export(String name, String fileName, Uri target) {
        publish(Operation.running(Operation.Type.EXPORT, name));
        io.execute(() -> {
            IWorkspaceService s = service;
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
        publish(Operation.running(Operation.Type.DELETE, name));
        io.execute(() -> {
            IWorkspaceService s = service;
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
            } catch (RemoteException e) {
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

    @Override
    protected void onCleared() {
        if (bound) {
            RootService.unbind(connection);
            bound = false;
        }
        io.shutdownNow();
    }
}
