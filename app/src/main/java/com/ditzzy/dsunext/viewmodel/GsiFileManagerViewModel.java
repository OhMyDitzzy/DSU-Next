package com.ditzzy.dsunext.viewmodel;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ditzzy.dsunext.filemanager.api.*;
import com.ditzzy.dsunext.ui.Event;
import com.ditzzy.dsunext.workspace.GsiFileEditor;
import com.ditzzy.dsunext.workspace.WorkspaceJobs;
import com.ditzzy.dsunext.workspace.WorkspaceStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;

public final class GsiFileManagerViewModel extends AndroidViewModel {
    public static final class Outcome {
        public final FileRequest request;
        public final FileResult result;
        public final String error;
        Outcome(FileRequest request, FileResult result, String error) {
            this.request = request; this.result = result; this.error = error;
        }
    }
    private final MutableLiveData<List<FileEntry>> entries = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<String> directory = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<OperationProgress> progress = new MutableLiveData<>();
    private final MutableLiveData<Event<Outcome>> outcomes = new MutableLiveData<>();
    private final MutableLiveData<String> clipboardLabel = new MutableLiveData<>("");
    private volatile GsiFileEditor editor;
    private volatile CancellationToken cancellation;
    private volatile boolean cleared;
    private volatile String currentDirectory = "";
    private volatile FileEntry clipboard;
    private boolean cut;
    private String workspaceName;
    private String exportPath;

    public GsiFileManagerViewModel(@NonNull Application application) {
        super(application);
        WorkspaceStore.initialize(application);
    }

    public LiveData<List<FileEntry>> getEntries() { return entries; }
    public LiveData<String> getDirectory() { return directory; }
    public LiveData<Boolean> getBusy() { return busy; }
    public LiveData<OperationProgress> getProgress() { return progress; }
    public LiveData<Event<Outcome>> getOutcomes() { return outcomes; }
    public LiveData<String> getClipboardLabel() { return clipboardLabel; }
    public boolean isBusy() { return Boolean.TRUE.equals(busy.getValue()); }
    public String path() { return currentDirectory; }
    public String child(String name) { return currentDirectory.isEmpty() ? name : currentDirectory + "/" + name; }

    public void start(String name) {
        if (workspaceName != null) return;
        workspaceName = name;
        execute(new FileRequest.ListDirectory(""));
    }

    public void navigate(String path) { execute(new FileRequest.ListDirectory(path)); }
    public void refresh() { navigate(currentDirectory); }
    public void cancel() { CancellationToken token = cancellation; if (token != null) token.cancel(); }
    public void copy(FileEntry entry, boolean cut) {
        if (isBusy()) return;
        clipboard = entry;
        this.cut = cut;
        clipboardLabel.setValue(entry.getName());
    }
    public void paste() {
        if (clipboard == null) return;
        String destination = child(clipboard.getName());
        execute(cut ? new FileRequest.Move(clipboard.getPath(), destination)
                : new FileRequest.Copy(clipboard.getPath(), destination));
    }
    public void setExportPath(String path) { exportPath = path; }
    public void export(Uri uri) {
        if (exportPath == null) return;
        execute(new FileRequest.ReadFile(exportPath, () -> {
            OutputStream out = getApplication().getContentResolver().openOutputStream(uri, "wt");
            if (out == null) throw new IOException("Cannot open the selected destination");
            return out;
        }));
    }
    public void importFile(Uri uri, String name) {
        execute(new FileRequest.CreateFile(child(name), () -> {
            InputStream input = getApplication().getContentResolver().openInputStream(uri);
            if (input == null) throw new IOException("Cannot open the selected file");
            return input;
        }));
    }
    public void clearClipboard() {
        if (isBusy()) return;
        clipboard = null;
        clipboardLabel.setValue("");
    }

    public void execute(FileRequest request) {
        if (isBusy() || cleared) return;
        busy.setValue(true);
        progress.setValue(null);
        CancellationToken token = new CancellationToken();
        cancellation = token;
        WorkspaceJobs.executor().execute(() -> {
            if (cleared) { busy.postValue(false); return; }
            try {
                GsiFileEditor session = session();
                FileResult result = session.execute(request, token, progress::postValue);
                if (request instanceof FileRequest.ListDirectory) {
                    if (result instanceof FileResult.Success) {
                        currentDirectory = ((FileRequest.ListDirectory) request).getPath();
                        directory.postValue(currentDirectory);
                        entries.postValue(((FileValue.Listing) ((FileResult.Success) result).getValue()).getEntries());
                    } else outcomes.postValue(new Event<>(new Outcome(request, result, null)));
                } else {
                    if (request instanceof FileRequest.Move && result instanceof FileResult.Success
                            && ((FileValue.Destination) ((FileResult.Success) result).getValue()).getPath() != null) {
                        clipboard = null;
                        clipboardLabel.postValue("");
                    }
                    try {
                        FileResult listing = session.execute(new FileRequest.ListDirectory(currentDirectory), new CancellationToken(), null);
                        if (listing instanceof FileResult.Success) entries.postValue(((FileValue.Listing) ((FileResult.Success) listing).getValue()).getEntries());
                    } catch (IOException ignored) {
                        // The original operation result carries the useful failure details.
                    }
                    outcomes.postValue(new Event<>(new Outcome(request, result, null)));
                }
            } catch (Exception error) { error(request, error); }
            finally { busy.postValue(false); }
        });
    }

    private GsiFileEditor session() throws IOException {
        if (editor == null) editor = new GsiFileEditor(workspaceName);
        return editor;
    }
    private void error(FileRequest request, Exception error) {
        outcomes.postValue(new Event<>(new Outcome(request, null,
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())));
    }
    @Override protected void onCleared() {
        cleared = true;
        cancel();
        WorkspaceJobs.executor().execute(() -> { if (editor != null) editor.close(); });
    }
}
