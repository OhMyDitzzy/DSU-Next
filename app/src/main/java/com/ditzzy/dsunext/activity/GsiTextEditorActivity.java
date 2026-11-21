package com.ditzzy.dsunext.activity;

import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.databinding.ActivityGsiTextEditorBinding;
import com.ditzzy.dsunext.ui.editor.EditorDocument;
import com.ditzzy.dsunext.viewmodel.GsiTextEditorViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

public final class GsiTextEditorActivity extends AppCompatActivity {
    public static final String EXTRA_PATH = "path";
    private ActivityGsiTextEditorBinding binding;
    private GsiTextEditorViewModel model;
    private boolean rendering;
    private boolean changingText;
    private boolean closeAfterSave;
    private boolean initialized;
    private int restoreScroll;
    private AlertDialog discard;
    private final Runnable recountSearch = this::updateMatches;

    @Override protected void onCreate(Bundle state) {
        EdgeToEdge.enable(this);
        super.onCreate(state);
        String workspace = getIntent().getStringExtra(GsiFileManagerActivity.EXTRA_WORKSPACE);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (workspace == null || path == null) { finish(); return; }
        binding = ActivityGsiTextEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            view.setPadding(bars.left, 0, bars.right, Math.max(bars.bottom, keyboard));
            boolean typing = insets.isVisible(WindowInsetsCompat.Type.ime());
            binding.editorHeader.setVisibility(typing ? View.GONE : View.VISIBLE);
            binding.toolbar.getMenu().findItem(R.id.editor_save).setVisible(typing);
            return insets;
        });
        ViewCompat.requestApplyInsets(binding.getRoot());
        int slash = path.lastIndexOf('/');
        binding.toolbar.setTitle(path.substring(slash + 1));
        binding.toolbar.setSubtitle(slash < 0 ? getString(R.string.fm_image_root) : path.substring(0, slash));
        model = new ViewModelProvider(this).get(GsiTextEditorViewModel.class);
        binding.gutter.attach(binding.editor);
        binding.editor.setOnSelectionChanged(() -> {
            EditorDocument document = model.document();
            if (document != null && !rendering && !changingText) document.selection(binding.editor.getSelectionStart(), binding.editor.getSelectionEnd());
            updateCursor();
        });
        binding.editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                if (!rendering && model.document() != null) {
                    model.document().selection(binding.editor.getSelectionStart(), binding.editor.getSelectionEnd());
                    changingText = true;
                }
            }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                if (rendering || model.document() == null) { changingText = false; return; }
                model.document().edit(text.toString(), binding.editor.getSelectionStart(), binding.editor.getSelectionEnd(), SystemClock.uptimeMillis());
                changingText = false;
                model.changed();
                binding.search.removeCallbacks(recountSearch);
                binding.search.postDelayed(recountSearch, 200);
            }
        });
        binding.save.setOnClickListener(v -> model.save());
        binding.retry.setOnClickListener(v -> model.load());
        binding.toolbar.setNavigationOnClickListener(v -> leave());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (binding.searchBar.getVisibility() == View.VISIBLE) toggleSearch(); else leave();
            }
        });
        binding.toolbar.setOnMenuItemClickListener(item -> {
            EditorDocument document = model.document();
            if (document == null || model.saving()) return true;
            int id = item.getItemId();
            if (id == R.id.editor_undo) { document.undo(); model.changed(); }
            else if (id == R.id.editor_redo) { document.redo(); model.changed(); }
            else if (id == R.id.editor_find) toggleSearch();
            else if (id == R.id.editor_save) model.save();
            return true;
        });
        binding.search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) { updateMatches(); }
        });
        binding.findNext.setOnClickListener(v -> find(false));
        binding.findPrevious.setOnClickListener(v -> find(true));
        binding.search.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { find(false); return true; }
            return false;
        });
        if (state != null) {
            closeAfterSave = state.getBoolean("close_after_save");
            restoreScroll = state.getInt("editor_scroll");
            if (state.getBoolean("search_open")) binding.searchBar.setVisibility(View.VISIBLE);
        }
        model.state().observe(this, revision -> render());
        model.errors().observe(this, event -> {
            String error = event == null ? null : event.getContentIfNotHandled();
            if (error == null) return;
            closeAfterSave = false;
            if (model.document() == null) binding.loadMessage.setText(error);
            else Snackbar.make(binding.getRoot(), error, Snackbar.LENGTH_LONG).show();
        });
        model.saved().observe(this, event -> {
            Boolean saved = event == null ? null : event.getContentIfNotHandled();
            if (saved == null) return;
            if (closeAfterSave && !model.document().dirty()) finish();
            else Snackbar.make(binding.getRoot(), R.string.fm_editor_saved, Snackbar.LENGTH_SHORT).show();
        });
        model.start(workspace, path);
    }

    private void render() {
        EditorDocument document = model.document();
        boolean ready = document != null;
        binding.editorContent.setVisibility(ready ? View.VISIBLE : View.INVISIBLE);
        binding.loadState.setVisibility(ready ? View.GONE : View.VISIBLE);
        binding.loading.setVisibility(model.loading() ? View.VISIBLE : View.GONE);
        binding.retry.setVisibility(!ready && !model.loading() ? View.VISIBLE : View.GONE);
        if (model.loading()) binding.loadMessage.setText(R.string.fm_editor_loading);
        binding.editor.setEnabled(ready && !model.saving());
        binding.save.setEnabled(ready && document.dirty() && !model.saving());
        binding.toolbar.getMenu().findItem(R.id.editor_save).setEnabled(ready && document.dirty() && !model.saving());
        binding.save.setText(model.saving() ? R.string.fm_editor_saving : R.string.fm_editor_save);
        binding.saveStatus.setText(model.saving() ? R.string.fm_editor_saving : ready && document.dirty()
                ? R.string.fm_editor_unsaved : R.string.fm_editor_saved);
        binding.toolbar.getMenu().findItem(R.id.editor_undo).setEnabled(ready && document.canUndo() && !model.saving());
        binding.toolbar.getMenu().findItem(R.id.editor_redo).setEnabled(ready && document.canRedo() && !model.saving());
        binding.toolbar.getMenu().findItem(R.id.editor_find).setEnabled(ready && !model.saving());
        if (ready && (!initialized || !binding.editor.getText().toString().equals(document.text()))) {
            rendering = true;
            binding.editor.setText(document.text());
            binding.editor.setSelection(document.selectionStart(), document.selectionEnd());
            rendering = false;
            if (!initialized) {
                initialized = true;
                binding.editor.post(() -> binding.editor.scrollTo(0, restoreScroll));
            }
            updateMatches();
        }
        updateCursor();
    }

    private void updateCursor() {
        if (binding.editor.getText() == null) return;
        int offset = Math.max(0, binding.editor.getSelectionStart());
        binding.cursor.setText(getString(R.string.fm_editor_cursor, binding.gutter.lineAt(offset) + 1, binding.gutter.columnAt(offset) + 1));
        binding.gutter.invalidate();
    }

    private void toggleSearch() {
        boolean opening = binding.searchBar.getVisibility() != View.VISIBLE;
        binding.searchBar.setVisibility(opening ? View.VISIBLE : View.GONE);
        if (opening) {
            binding.search.requestFocus();
            binding.search.post(() -> {
                if (ViewCompat.getWindowInsetsController(binding.search) != null)
                    ViewCompat.getWindowInsetsController(binding.search).show(WindowInsetsCompat.Type.ime());
            });
            updateMatches();
        } else {
            binding.search.setText("");
            binding.matches.setText("");
            binding.editor.requestFocus();
        }
    }

    private void find(boolean backwards) {
        String query = binding.search.getText().toString();
        if (query.isEmpty() || model.document() == null) return;
        String text = model.document().text();
        int found;
        if (backwards) {
            int before = binding.editor.getSelectionStart() - 1;
            found = text.lastIndexOf(query, before);
            if (found < 0) found = text.lastIndexOf(query);
        } else {
            found = text.indexOf(query, Math.max(0, binding.editor.getSelectionEnd()));
            if (found < 0) found = text.indexOf(query);
        }
        if (found >= 0) {
            binding.editor.requestFocus();
            binding.editor.setSelection(found, found + query.length());
            binding.editor.post(() -> binding.editor.bringPointIntoView(binding.editor.getSelectionStart()));
        }
        updateMatches();
    }

    private void updateMatches() {
        String query = binding.search.getText().toString();
        if (query.isEmpty() || model.document() == null || binding.searchBar.getVisibility() != View.VISIBLE) {
            binding.matches.setText("");
            binding.findPrevious.setEnabled(false);
            binding.findNext.setEnabled(false);
            return;
        }
        String text = model.document().text();
        int count = 0;
        for (int offset = text.indexOf(query); offset >= 0; offset = text.indexOf(query, offset + query.length())) count++;
        binding.matches.setText(getResources().getQuantityString(R.plurals.fm_editor_matches, count, count));
        binding.findPrevious.setEnabled(count > 0);
        binding.findNext.setEnabled(count > 0);
    }

    private void leave() {
        if (model.saving()) return;
        EditorDocument document = model.document();
        if (document == null || !document.dirty()) { finish(); return; }
        discard = new MaterialAlertDialogBuilder(this).setTitle(R.string.fm_editor_leave_title)
                .setMessage(R.string.fm_editor_leave_message)
                .setPositiveButton(R.string.fm_editor_save, (d, which) -> { closeAfterSave = true; model.save(); })
                .setNegativeButton(R.string.discard, (d, which) -> finish())
                .setNeutralButton(R.string.cancel, null).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("close_after_save", closeAfterSave);
        state.putBoolean("search_open", binding.searchBar.getVisibility() == View.VISIBLE);
        state.putInt("editor_scroll", binding.editor.getScrollY());
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        if (binding != null) binding.search.removeCallbacks(recountSearch);
        if (discard != null) discard.dismiss();
        super.onDestroy();
    }
}
