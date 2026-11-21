package com.ditzzy.dsunext.activity;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.databinding.ActivityGsiFileManagerBinding;
import com.ditzzy.dsunext.databinding.RowListItemBinding;
import com.ditzzy.dsunext.filemanager.api.*;
import com.ditzzy.dsunext.ui.Event;
import com.ditzzy.dsunext.ui.InsetsUtils;
import com.ditzzy.dsunext.viewmodel.GsiFileManagerViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class GsiFileManagerActivity extends AppCompatActivity {
    public static final String EXTRA_WORKSPACE = "workspace";
    private ActivityGsiFileManagerBinding binding;
    private GsiFileManagerViewModel model;
    private FileAdapter adapter;
    private AlertDialog dialog;
    private ActivityResultLauncher<String[]> pickFile;
    private ActivityResultLauncher<String> saveFile;

    @Override protected void onCreate(Bundle state) {
        EdgeToEdge.enable(this);
        super.onCreate(state);
        String name = getIntent().getStringExtra(EXTRA_WORKSPACE);
        if (name == null) { finish(); return; }
        binding = ActivityGsiFileManagerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtils.padBottomAndSides(binding.content);
        InsetsUtils.padSides(binding.path);
        binding.toolbar.setTitle(name);
        model = new ViewModelProvider(this).get(GsiFileManagerViewModel.class);
        adapter = new FileAdapter();
        binding.files.setAdapter(adapter);
        binding.files.setEmptyView(binding.empty);
        binding.toolbar.setNavigationOnClickListener(v -> up());
        binding.cancel.setOnClickListener(v -> model.cancel());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { up(); }
        });
        pickFile = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) prompt(R.string.fm_import, R.string.fm_name, displayName(uri), true,
                    value -> model.importFile(uri, value));
        });
        saveFile = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
            if (uri != null) model.export(uri);
        });
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (model.isBusy()) return true;
            int id = item.getItemId();
            if (id == R.id.fm_new_file) prompt(R.string.fm_new_file, R.string.fm_name, "", true,
                    value -> model.execute(new FileRequest.CreateFile(model.child(value))));
            else if (id == R.id.fm_new_folder) prompt(R.string.fm_new_folder, R.string.fm_name, "", true,
                    value -> model.execute(new FileRequest.CreateDirectory(model.child(value))));
            else if (id == R.id.fm_import) pickFile.launch(new String[]{"*/*"});
            else if (id == R.id.fm_paste) model.paste();
            else if (id == R.id.fm_refresh) model.refresh();
            else if (id == R.id.fm_symlink) prompt(R.string.fm_symlink, R.string.fm_name, "", true,
                    value -> prompt(R.string.fm_symlink, R.string.fm_target, "", false,
                            target -> model.execute(new FileRequest.CreateSymlink(model.child(value), target))));
            else return false;
            return true;
        });
        model.getEntries().observe(this, entries -> { adapter.clear(); adapter.addAll(entries); });
        model.getDirectory().observe(this, path -> binding.path.setText(path.isEmpty() ? "/" : "/" + path));
        model.getBusy().observe(this, busy -> {
            binding.operation.setVisibility(busy ? View.VISIBLE : View.GONE);
            binding.progress.setText(R.string.fm_working);
            for (int i = 0; i < binding.toolbar.getMenu().size(); i++) binding.toolbar.getMenu().getItem(i).setEnabled(!busy);
        });
        model.getClipboardLabel().observe(this, label -> {
            binding.toolbar.getMenu().findItem(R.id.fm_paste).setVisible(!label.isEmpty());
            binding.toolbar.getMenu().findItem(R.id.fm_paste).setTitle(getString(R.string.fm_paste_name, label));
        });
        model.getProgress().observe(this, progress -> {
            if (progress != null) binding.progress.setText(getString(R.string.fm_progress,
                    progress.getPath() == null ? "" : progress.getPath(),
                    Formatter.formatFileSize(this, progress.getBytesProcessed()), progress.getChangeCount()));
        });
        model.getOutcomes().observe(this, this::outcome);
        model.getDocuments().observe(this, event -> {
            GsiFileManagerViewModel.Document document = event == null ? null : event.getContentIfNotHandled();
            if (document != null) edit(document);
        });
        model.start(name);
    }

    @Override protected void onResume() {
        super.onResume();
        if (model != null && !model.isBusy()) model.refresh();
    }
    @Override protected void onDestroy() {
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }
    private void up() {
        if (model.isBusy()) { Toast.makeText(this, R.string.fm_working, Toast.LENGTH_SHORT).show(); return; }
        String path = model.path();
        if (path.isEmpty()) finish();
        else model.navigate(path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "");
    }

    private void open(FileEntry entry) {
        if (model.isBusy()) return;
        if (entry.getKind() == FileKind.DIRECTORY) model.navigate(entry.getPath());
        else actions(entry);
    }
    private void actions(FileEntry entry) {
        if (model.isBusy()) return;
        List<String> titles = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        add(titles, actions, R.string.fm_copy, () -> model.copy(entry, false));
        add(titles, actions, R.string.fm_cut, () -> model.copy(entry, true));
        add(titles, actions, R.string.fm_rename, () -> prompt(R.string.fm_rename, R.string.fm_name, entry.getName(), true,
                name -> model.execute(new FileRequest.Rename(entry.getPath(), name))));
        if (entry.getKind() == FileKind.FILE) {
            add(titles, actions, R.string.fm_edit, () -> {
                if (entry.getSize() > 256 * 1024) message(getString(R.string.fm_text_limit)); else model.openText(entry);
            });
            add(titles, actions, R.string.fm_export, () -> { model.setExportPath(entry.getPath()); saveFile.launch(entry.getName()); });
        }
        if (entry.getKind() != FileKind.SYMLINK && entry.getKind() != FileKind.OTHER)
            add(titles, actions, R.string.fm_permissions, () -> permissions(entry));
        add(titles, actions, R.string.gsi_delete, () -> {
            dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.gsi_delete)
                    .setMessage(getString(R.string.fm_delete_message, entry.getName()))
                    .setPositiveButton(R.string.gsi_delete, (d, which) -> model.execute(new FileRequest.Delete(entry.getPath(), true)))
                    .setNegativeButton(R.string.cancel, null).show();
        });
        add(titles, actions, R.string.fm_properties, () -> properties(entry));
        dialog = new MaterialAlertDialogBuilder(this).setTitle(entry.getName())
                .setItems(titles.toArray(new String[0]), (d, which) -> actions.get(which).run()).show();
    }
    private void add(List<String> titles, List<Runnable> actions, int title, Runnable action) {
        titles.add(getString(title)); actions.add(action);
    }
    private void properties(FileEntry entry) {
        String target = entry.getSymlinkTarget() == null ? "" : getString(R.string.fm_link_target, entry.getSymlinkTarget());
        dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.fm_properties)
                .setMessage(getString(R.string.fm_info, "/" + entry.getPath(), kind(entry),
                        Formatter.formatFileSize(this, entry.getSize()), mode(entry), target))
                .setPositiveButton(R.string.close, null).show();
    }
    private void prompt(int title, int hint, String initial, boolean fileName, Consumer<String> action) {
        TextInputLayout layout = input(hint);
        EditText edit = layout.getEditText();
        edit.setText(initial);
        dialog = new MaterialAlertDialogBuilder(this).setTitle(title).setView(layout)
                .setPositiveButton(R.string.ok, null).setNegativeButton(R.string.cancel, null).create();
        dialog.show();
        AlertDialog current = dialog;
        current.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = edit.getText().toString();
            if (value.isEmpty() || (fileName && (!validName(value)))) { layout.setError(getString(R.string.fm_invalid_name)); return; }
            current.dismiss();
            action.accept(value);
        });
    }
    private TextInputLayout input(int hint) {
        TextInputLayout layout = new TextInputLayout(this);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding / 2, padding, 0);
        layout.setHint(getString(hint));
        TextInputEditText edit = new TextInputEditText(layout.getContext());
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setSingleLine(true);
        layout.addView(edit);
        return layout;
    }
    private static boolean validName(String name) {
        return !name.equals(".") && !name.equals("..") && !name.contains("/")
                && name.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }
    private void permissions(FileEntry entry) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextInputLayout input = input(R.string.fm_mode);
        input.getEditText().setText(mode(entry));
        content.addView(input);
        CheckBox recursive = new CheckBox(this);
        recursive.setText(R.string.fm_recursive);
        recursive.setVisibility(entry.getKind() == FileKind.DIRECTORY ? View.VISIBLE : View.GONE);
        content.addView(recursive);
        dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.fm_permissions).setView(content)
                .setPositiveButton(R.string.ok, null).setNegativeButton(R.string.cancel, null).create();
        dialog.show();
        AlertDialog current = dialog;
        current.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getEditText().getText().toString();
            if (!value.matches("[0-7]{3,4}")) { input.setError(getString(R.string.fm_mode)); return; }
            current.dismiss();
            model.execute(new FileRequest.SetPermissions(entry.getPath(), Integer.parseInt(value, 8), recursive.isChecked()));
        });
    }
    private void edit(GsiFileManagerViewModel.Document document) {
        EditText text = new TextInputEditText(this);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setMinLines(10);
        text.setText(document.text);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        dialog = new MaterialAlertDialogBuilder(this).setTitle(document.path).setView(scroll)
                .setPositiveButton(R.string.fm_save, (d, which) -> model.saveText(document.path, text.getText().toString()))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void outcome(Event<GsiFileManagerViewModel.Outcome> event) {
        GsiFileManagerViewModel.Outcome outcome = event == null ? null : event.getContentIfNotHandled();
        if (outcome == null) return;
        if (outcome.error != null) { message(outcome.error); return; }
        FileResult result = outcome.result;
        if (result instanceof FileResult.Failure) {
            FileError error = ((FileResult.Failure) result).getError();
            if (error.getCode() == FileErrorCode.ALREADY_EXISTS && canRetry(outcome.request)) conflict(outcome.request);
            else message(describe(error));
        } else if (result instanceof FileResult.Partial) {
            message(getString(R.string.fm_partial, result.getReport().getChangeCount(), result.getReport().getSkippedCount(),
                    describe(((FileResult.Partial) result).getError())));
        } else if (result instanceof FileResult.Cancelled) {
            message(getString(R.string.fm_cancelled, result.getReport().getChangeCount()));
        } else Toast.makeText(this, result.getReport().getSkippedCount() > 0
                ? getString(R.string.fm_skipped, result.getReport().getSkippedCount()) : getString(R.string.fm_done), Toast.LENGTH_SHORT).show();
    }
    private boolean canRetry(FileRequest request) {
        return request instanceof FileRequest.Copy || request instanceof FileRequest.Move || request instanceof FileRequest.Rename
                || request instanceof FileRequest.CreateFile || request instanceof FileRequest.CreateDirectory || request instanceof FileRequest.CreateSymlink;
    }
    private void conflict(FileRequest request) {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> choices = new ArrayList<>();
        add(labels, choices, R.string.fm_replace, () -> retry(request, ConflictPolicy.REPLACE, DirectoryPolicy.FAIL));
        add(labels, choices, R.string.fm_keep_both, () -> retry(request, ConflictPolicy.KEEP_BOTH, DirectoryPolicy.FAIL));
        add(labels, choices, R.string.fm_skip, () -> retry(request, ConflictPolicy.SKIP, DirectoryPolicy.FAIL));
        if (request instanceof FileRequest.Copy || request instanceof FileRequest.Move)
            add(labels, choices, R.string.fm_merge, () -> mergeConflict(request));
        dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.fm_conflict)
                .setItems(labels.toArray(new String[0]), (d, which) -> choices.get(which).run())
                .setNegativeButton(R.string.cancel, null).show();
    }
    private void mergeConflict(FileRequest request) {
        String[] labels = {getString(R.string.fm_fail_on_conflict), getString(R.string.fm_replace),
                getString(R.string.fm_keep_both), getString(R.string.fm_skip)};
        ConflictPolicy[] policies = {ConflictPolicy.FAIL, ConflictPolicy.REPLACE, ConflictPolicy.KEEP_BOTH, ConflictPolicy.SKIP};
        dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.fm_child_conflicts)
                .setItems(labels, (d, which) -> retry(request, policies[which], DirectoryPolicy.MERGE))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void retry(FileRequest request, ConflictPolicy policy, DirectoryPolicy directories) {
        if (request instanceof FileRequest.Copy) {
            FileRequest.Copy r = (FileRequest.Copy) request;
            model.execute(new FileRequest.Copy(r.getSource(), r.getDestination(), policy, directories, r.getCreateParents(), r.getPreserveAttributes()));
        } else if (request instanceof FileRequest.Move) {
            FileRequest.Move r = (FileRequest.Move) request;
            model.execute(new FileRequest.Move(r.getSource(), r.getDestination(), policy, directories, r.getCreateParents()));
        } else if (request instanceof FileRequest.Rename) {
            FileRequest.Rename r = (FileRequest.Rename) request;
            model.execute(new FileRequest.Rename(r.getPath(), r.getNewName(), policy));
        } else if (request instanceof FileRequest.CreateFile) {
            FileRequest.CreateFile r = (FileRequest.CreateFile) request;
            model.execute(new FileRequest.CreateFile(r.getPath(), r.getContent(), r.getCreateParents(), policy));
        } else if (request instanceof FileRequest.CreateDirectory) {
            FileRequest.CreateDirectory r = (FileRequest.CreateDirectory) request;
            model.execute(new FileRequest.CreateDirectory(r.getPath(), r.getCreateParents(), policy));
        } else if (request instanceof FileRequest.CreateSymlink) {
            FileRequest.CreateSymlink r = (FileRequest.CreateSymlink) request;
            model.execute(new FileRequest.CreateSymlink(r.getPath(), r.getTarget(), r.getCreateParents(), policy));
        }
    }
    private String describe(FileError error) {
        switch (error.getCode()) {
            case ALREADY_EXISTS: return getString(R.string.fm_conflict);
            case ACCESS_DENIED: return getString(R.string.fm_access_denied);
            case NO_SPACE: return getString(R.string.fm_no_space);
            case NOT_FOUND: return getString(R.string.fm_not_found);
            case SYMLINK_NOT_ALLOWED: return getString(R.string.fm_link_blocked);
            case SOURCE_CHANGED: return getString(R.string.fm_changed);
            case UNSUPPORTED: return getString(R.string.fm_unsupported);
            case TYPE_MISMATCH: return getString(R.string.fm_type_mismatch);
            case DIRECTORY_NOT_EMPTY: return getString(R.string.fm_not_empty);
            case INVALID_PATH: case INVALID_MODE: case OUTSIDE_WORKSPACE: case SAME_FILE: case DESCENDANT_TARGET:
                return getString(R.string.fm_bad_path);
            default: return error.getMessage();
        }
    }
    private void message(String message) {
        dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.fm_title).setMessage(message).setPositiveButton(R.string.close, null).show();
    }
    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
        } catch (RuntimeException ignored) {
            // Some document providers do not expose a display name.
        }
        return "file";
    }
    private String kind(FileEntry entry) {
        switch (entry.getKind()) {
            case DIRECTORY: return getString(R.string.fm_directory);
            case SYMLINK: return getString(R.string.fm_link);
            case OTHER: return getString(R.string.fm_special);
            default: return getString(R.string.fm_file);
        }
    }
    private String mode(FileEntry entry) { return entry.getMode() == null ? "—" : String.format(Locale.ROOT, "%04o", entry.getMode()); }
    private final class FileAdapter extends ArrayAdapter<FileEntry> {
        FileAdapter() { super(GsiFileManagerActivity.this, 0, new ArrayList<>()); }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            RowListItemBinding row;
            if (convert == null) {
                row = RowListItemBinding.inflate(getLayoutInflater(), parent, false);
                convert = row.getRoot();
                convert.setTag(row);
            } else row = (RowListItemBinding) convert.getTag();
            FileEntry entry = getItem(position);
            row.itemTitle.setText(entry.getName());
            row.itemSupporting.setVisibility(View.VISIBLE);
            row.itemSupporting.setText(kind(entry) + " · " + mode(entry)
                    + (entry.getKind() == FileKind.FILE ? " · " + Formatter.formatFileSize(GsiFileManagerActivity.this, entry.getSize()) : ""));
            row.itemIcon.setImageResource(entry.getKind() == FileKind.DIRECTORY ? R.drawable.ic_folder_open : R.drawable.ic_file);
            row.itemCard.setOnClickListener(v -> open(entry));
            row.itemCard.setOnLongClickListener(v -> { actions(entry); return true; });
            row.itemTrailingButton.setVisibility(View.VISIBLE);
            row.itemTrailingButton.setIconResource(R.drawable.ic_more_vert);
            row.itemTrailingButton.setContentDescription(getString(R.string.gsi_more_options));
            row.itemTrailingButton.setOnClickListener(v -> actions(entry));
            return convert;
        }
    }
}
