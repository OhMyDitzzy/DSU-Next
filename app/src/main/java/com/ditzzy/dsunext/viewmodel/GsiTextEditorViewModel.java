package com.ditzzy.dsunext.viewmodel;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.filemanager.api.*;
import com.ditzzy.dsunext.ui.Event;
import com.ditzzy.dsunext.ui.editor.EditorDocument;
import com.ditzzy.dsunext.workspace.GsiFileEditor;
import com.ditzzy.dsunext.workspace.WorkspaceJobs;
import com.ditzzy.dsunext.workspace.WorkspaceStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class GsiTextEditorViewModel extends AndroidViewModel {
    public static final int MAX_BYTES = 256 * 1024;
    private final MutableLiveData<Integer> state = new MutableLiveData<>(0);
    private final MutableLiveData<Event<String>> errors = new MutableLiveData<>();
    private final MutableLiveData<Event<Boolean>> saved = new MutableLiveData<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CancellationToken cancellation = new CancellationToken();
    private String workspace;
    private String path;
    private boolean loading;
    private boolean saving;
    private volatile boolean cleared;
    private EditorDocument document;

    public GsiTextEditorViewModel(@NonNull Application application) {
        super(application);
        WorkspaceStore.initialize(application);
    }
    public LiveData<Integer> state() { return state; }
    public LiveData<Event<String>> errors() { return errors; }
    public LiveData<Event<Boolean>> saved() { return saved; }
    public EditorDocument document() { return document; }
    public boolean loading() { return loading; }
    public boolean saving() { return saving; }
    public void changed() { state.setValue(state.getValue() + 1); }

    public void start(String workspace, String path) {
        if (this.path != null) return;
        this.workspace = workspace;
        this.path = path;
        load();
    }

    public void load() {
        if (loading || document != null) return;
        loading = true;
        changed();
        WorkspaceJobs.executor().execute(() -> {
            String contents = null;
            String error = null;
            try (GsiFileEditor editor = new GsiFileEditor(workspace)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream() {
                    @Override public synchronized void write(byte[] data, int offset, int length) {
                        if (count + length > MAX_BYTES) throw new IllegalArgumentException(getApplication().getString(R.string.fm_text_limit));
                        super.write(data, offset, length);
                    }
                };
                FileResult result = editor.execute(new FileRequest.ReadFile(path, () -> bytes), cancellation, null);
                requireSuccess(result);
                contents = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                if (contents.indexOf('\0') >= 0) throw new IOException(getApplication().getString(R.string.fm_text_binary));
            } catch (Exception failure) { error = describe(failure); }
            String loaded = contents;
            String failure = error;
            main.post(() -> {
                if (cleared) return;
                loading = false;
                if (failure == null) document = new EditorDocument(loaded);
                changed();
                if (failure != null) errors.setValue(new Event<>(failure));
            });
        });
    }

    public void save() {
        if (saving || document == null || !document.dirty()) return;
        String snapshot = document.text();
        byte[] bytes = snapshot.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            errors.setValue(new Event<>(getApplication().getString(R.string.fm_text_limit)));
            return;
        }
        saving = true;
        changed();
        WorkspaceJobs.executor().execute(() -> {
            String error = null;
            try (GsiFileEditor editor = new GsiFileEditor(workspace)) {
                FileResult result = editor.execute(new FileRequest.WriteFile(path, () -> new ByteArrayInputStream(bytes)), cancellation, null);
                requireSuccess(result);
            } catch (Exception failure) { error = describe(failure); }
            String failure = error;
            main.post(() -> {
                if (cleared) return;
                saving = false;
                if (failure == null) document.markSaved(snapshot);
                changed();
                if (failure != null) errors.setValue(new Event<>(failure));
                else saved.setValue(new Event<>(true));
            });
        });
    }

    private void requireSuccess(FileResult result) throws IOException {
        if (result instanceof FileResult.Success) return;
        if (result instanceof FileResult.Failure) throw new IOException(((FileResult.Failure) result).getError().getMessage());
        if (result instanceof FileResult.Partial) throw new IOException(((FileResult.Partial) result).getError().getMessage());
        throw new IOException(getApplication().getString(R.string.fm_editor_cancelled));
    }
    private String describe(Exception failure) {
        if (failure instanceof java.nio.charset.CharacterCodingException) return getApplication().getString(R.string.fm_text_binary);
        return failure.getMessage() == null ? getApplication().getString(R.string.fm_failed) : failure.getMessage();
    }
    @Override protected void onCleared() { cleared = true; cancellation.cancel(); }
}
