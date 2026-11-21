package com.ditzzy.dsunext.activity;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.databinding.ActivityGsiFileManagerBinding;
import com.ditzzy.dsunext.databinding.DialogFileInputBinding;
import com.ditzzy.dsunext.databinding.DialogPermissionsBinding;
import com.ditzzy.dsunext.databinding.RowFileEntryBinding;
import com.ditzzy.dsunext.databinding.SheetFileActionsBinding;
import com.ditzzy.dsunext.filemanager.api.*;
import com.ditzzy.dsunext.ui.Event;
import com.ditzzy.dsunext.ui.InsetsUtils;
import com.ditzzy.dsunext.ui.ListRow;
import com.ditzzy.dsunext.ui.SegmentedGroup;
import com.ditzzy.dsunext.util.ModeBits;
import com.ditzzy.dsunext.viewmodel.GsiFileManagerViewModel;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

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
    private BottomSheetDialog sheet;
    private String workspace;
    private ActivityResultLauncher<String[]> pickFile;
    private ActivityResultLauncher<String> saveFile;

    @Override protected void onCreate(Bundle state) {
        EdgeToEdge.enable(this);
        super.onCreate(state);
        workspace = getIntent().getStringExtra(EXTRA_WORKSPACE);
        if (workspace == null) { finish(); return; }
        binding = ActivityGsiFileManagerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtils.padBottomAndSides(binding.content);
        InsetsUtils.padSides(binding.header);
        if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            binding.folderTitle.setVisibility(View.GONE);
        binding.toolbar.setSubtitle(workspace);
        model = new ViewModelProvider(this).get(GsiFileManagerViewModel.class);
        adapter = new FileAdapter();
        binding.files.setAdapter(adapter);
        binding.toolbar.setNavigationOnClickListener(v -> up());
        binding.cancel.setOnClickListener(v -> model.cancel());
        binding.create.setOnClickListener(v -> creation());
        binding.paste.setOnClickListener(v -> model.paste());
        binding.clearClipboard.setOnClickListener(v -> model.clearClipboard());
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
            if (!model.isBusy() && item.getItemId() == R.id.fm_refresh) model.refresh();
            return true;
        });
        model.getEntries().observe(this, entries -> {
            adapter.setNotifyOnChange(false);
            adapter.clear();
            adapter.addAll(entries);
            adapter.notifyDataSetChanged();
            int folders = 0;
            for (FileEntry entry : entries) if (entry.getKind() == FileKind.DIRECTORY) folders++;
            String folderCount = getResources().getQuantityString(R.plurals.fm_folder_count, folders, folders);
            int files = entries.size() - folders;
            String fileCount = getResources().getQuantityString(R.plurals.fm_file_count, files, files);
            binding.summary.setText(getString(R.string.fm_folder_summary, folderCount, fileCount));
            renderControls();
        });
        model.getDirectory().observe(this, this::breadcrumbs);
        model.getBusy().observe(this, busy -> {
            binding.operation.setVisibility(busy ? View.VISIBLE : View.GONE);
            binding.progress.setText("");
            binding.paste.setEnabled(!busy);
            binding.clearClipboard.setEnabled(!busy);
            binding.toolbar.getMenu().findItem(R.id.fm_refresh).setEnabled(!busy);
            for (int i = 0; i < binding.breadcrumbs.getChildCount(); i++) binding.breadcrumbs.getChildAt(i).setEnabled(!busy);
            renderControls();
        });
        model.getClipboardLabel().observe(this, label -> {
            binding.clipboardName.setText(label);
            renderControls();
        });
        model.getProgress().observe(this, progress -> {
            if (progress != null) binding.progress.setText(getString(R.string.fm_progress,
                    progress.getPath() == null ? "" : progress.getPath(),
                    android.text.format.Formatter.formatFileSize(this, progress.getBytesProcessed()), progress.getChangeCount()));
        });
        model.getOutcomes().observe(this, this::outcome);
        model.start(workspace);
    }

    @Override protected void onResume() {
        super.onResume();
        if (model != null && !model.isBusy()) model.refresh();
    }

    @Override protected void onDestroy() {
        if (dialog != null) dialog.dismiss();
        if (sheet != null) sheet.dismiss();
        super.onDestroy();
    }

    private void renderControls() {
        String label = model.getClipboardLabel().getValue();
        boolean clipboard = label != null && !label.isEmpty();
        binding.clipboard.setVisibility(clipboard ? View.VISIBLE : View.GONE);
        binding.create.setVisibility(!clipboard && !model.isBusy() ? View.VISIBLE : View.GONE);
        binding.empty.setVisibility(adapter.isEmpty() && !model.isBusy() ? View.VISIBLE : View.GONE);
    }

    private void breadcrumbs(String path) {
        binding.folderTitle.setText(path.isEmpty() ? getString(R.string.fm_image_root) : path.substring(path.lastIndexOf('/') + 1));
        binding.breadcrumbs.removeAllViews();
        crumb(getString(R.string.fm_image_root), "", path.isEmpty());
        String relative = "";
        if (!path.isEmpty()) for (String segment : path.split("/")) {
            ImageView separator = new ImageView(this);
            separator.setImageResource(R.drawable.ic_chevron_right);
            separator.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(separator, com.google.android.material.R.attr.colorOnSurfaceVariant)));
            separator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            binding.breadcrumbs.addView(separator, new LinearLayout.LayoutParams(dp(16), dp(16)));
            relative = relative.isEmpty() ? segment : relative + "/" + segment;
            crumb(segment, relative, relative.equals(path));
        }
        binding.breadcrumbScroll.post(() -> binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT));
        binding.files.setSelection(0);
    }

    private void crumb(String title, String path, boolean current) {
        MaterialButton button = (MaterialButton) getLayoutInflater().inflate(R.layout.button_file_breadcrumb, binding.breadcrumbs, false);
        button.setText(title);
        button.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(button, current
                ? com.google.android.material.R.attr.colorSecondaryContainer : com.google.android.material.R.attr.colorSurface)));
        button.setTextColor(MaterialColors.getColor(button, current
                ? com.google.android.material.R.attr.colorOnSecondaryContainer : com.google.android.material.R.attr.colorOnSurfaceVariant));
        button.setContentDescription(getString(R.string.fm_open_folder, title));
        button.setEnabled(!model.isBusy());
        button.setOnClickListener(v -> { if (!model.isBusy() && !current) model.navigate(path); });
        binding.breadcrumbs.addView(button);
    }

    private void up() {
        if (model.isBusy()) return;
        String path = model.path();
        if (path.isEmpty()) finish();
        else model.navigate(path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "");
    }

    private void open(FileEntry entry) {
        if (model.isBusy()) return;
        if (entry.getKind() == FileKind.DIRECTORY) model.navigate(entry.getPath());
        else actions(entry);
    }

    private void showSheet(SheetFileActionsBinding content) {
        if (sheet != null) sheet.dismiss();
        sheet = new BottomSheetDialog(this);
        sheet.setContentView(content.getRoot());
        sheet.getBehavior().setMaxHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.85f));
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.show();
    }

    private void sheetAction(LinearLayout group, int icon, int label, Runnable action) {
        ListRow.create(group).icon(icon).title(label).onClick(v -> {
            if (sheet != null) sheet.dismiss();
            if (!model.isBusy()) action.run();
        });
    }

    private void creation() {
        if (model.isBusy()) return;
        SheetFileActionsBinding content = SheetFileActionsBinding.inflate(getLayoutInflater());
        content.title.setText(R.string.fm_create);
        content.subtitle.setText(getString(R.string.fm_create_in, model.path().isEmpty() ? getString(R.string.fm_image_root) : model.path()));
        sheetAction(content.actions, R.drawable.ic_description, R.string.fm_new_file,
                () -> prompt(R.string.fm_new_file, R.string.fm_name, "", true, name -> model.execute(new FileRequest.CreateFile(model.child(name)))));
        sheetAction(content.actions, R.drawable.ic_folder_open, R.string.fm_new_folder,
                () -> prompt(R.string.fm_new_folder, R.string.fm_name, "", true, name -> model.execute(new FileRequest.CreateDirectory(model.child(name)))));
        sheetAction(content.actions, R.drawable.ic_download, R.string.fm_import, () -> pickFile.launch(new String[]{"*/*"}));
        sheetAction(content.actions, R.drawable.ic_link, R.string.fm_symlink,
                () -> prompt(R.string.fm_symlink, R.string.fm_name, "", true, name -> prompt(R.string.fm_symlink, R.string.fm_target, "", false,
                        target -> model.execute(new FileRequest.CreateSymlink(model.child(name), target)))));
        SegmentedGroup.apply(content.actions);
        showSheet(content);
    }

    private void actions(FileEntry entry) {
        if (model.isBusy()) return;
        SheetFileActionsBinding content = SheetFileActionsBinding.inflate(getLayoutInflater());
        content.title.setText(entry.getName());
        content.subtitle.setText(kind(entry) + " · " + mode(entry));
        if (entry.getKind() == FileKind.FILE) {
            sheetAction(content.actions, R.drawable.ic_edit, R.string.fm_edit, () -> {
                if (entry.getSize() > 256 * 1024) message(getString(R.string.fm_text_limit));
                else startActivity(new Intent(this, GsiTextEditorActivity.class)
                        .putExtra(EXTRA_WORKSPACE, workspace).putExtra(GsiTextEditorActivity.EXTRA_PATH, entry.getPath()));
            });
        }
        sheetAction(content.actions, R.drawable.ic_content_copy, R.string.fm_copy, () -> model.copy(entry, false));
        sheetAction(content.actions, R.drawable.ic_content_cut, R.string.fm_cut, () -> model.copy(entry, true));
        sheetAction(content.actions, R.drawable.ic_edit, R.string.fm_rename, () -> prompt(R.string.fm_rename, R.string.fm_name, entry.getName(), true,
                name -> model.execute(new FileRequest.Rename(entry.getPath(), name))));
        if (entry.getKind() == FileKind.FILE) sheetAction(content.actions, R.drawable.ic_download, R.string.fm_export,
                () -> { model.setExportPath(entry.getPath()); saveFile.launch(entry.getName()); });
        if (entry.getKind() != FileKind.OTHER)
            sheetAction(content.actions, R.drawable.ic_settings, R.string.fm_permissions, () -> permissions(entry));
        sheetAction(content.actions, R.drawable.ic_info, R.string.fm_properties, () -> properties(entry));
        ListRow delete = ListRow.create(content.actions).icon(R.drawable.ic_delete).title(R.string.gsi_delete).onClick(v -> {
            sheet.dismiss();
            if (model.isBusy()) return;
            dialog = new MaterialAlertDialogBuilder(this).setIcon(R.drawable.ic_delete).setTitle(R.string.gsi_delete)
                    .setMessage(getString(R.string.fm_delete_message, entry.getName()))
                    .setPositiveButton(R.string.gsi_delete, (d, which) -> model.execute(new FileRequest.Delete(entry.getPath(), true)))
                    .setNegativeButton(R.string.cancel, null).show();
        });
        delete.root().setContentDescription(getString(R.string.gsi_delete_title, entry.getName()));
        SegmentedGroup.apply(content.actions);
        showSheet(content);
    }

    private void add(List<String> titles, List<Runnable> actions, int title, Runnable action) {
        titles.add(getString(title)); actions.add(action);
    }

    private void properties(FileEntry entry) {
        SheetFileActionsBinding content = SheetFileActionsBinding.inflate(getLayoutInflater());
        content.title.setText(entry.getName());
        content.subtitle.setText(R.string.fm_properties);
        ListRow.create(content.actions).icon(R.drawable.ic_folder_open).title(R.string.gsi_info_path).supporting("/" + entry.getPath()).notClickable();
        ListRow.create(content.actions).icon(R.drawable.ic_file).title(kind(entry)).supporting(android.text.format.Formatter.formatFileSize(this, entry.getSize())).notClickable();
        ListRow.create(content.actions).icon(R.drawable.ic_settings).title(R.string.fm_permissions).supporting(mode(entry)).notClickable();
        if (entry.getSymlinkTarget() != null)
            ListRow.create(content.actions).icon(R.drawable.ic_link).title(R.string.fm_target).supporting(entry.getSymlinkTarget()).notClickable();
        SegmentedGroup.apply(content.actions);
        showSheet(content);
    }

    private void prompt(int title, int hint, String initial, boolean fileName, Consumer<String> action) {
        DialogFileInputBinding content = DialogFileInputBinding.inflate(getLayoutInflater());
        content.inputLayout.setHint(getString(hint));
        content.inputEdit.setText(initial);
        dialog = new MaterialAlertDialogBuilder(this).setTitle(title).setView(content.getRoot())
                .setPositiveButton(R.string.ok, null).setNegativeButton(R.string.cancel, null).create();
        dialog.show();
        AlertDialog current = dialog;
        current.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = content.inputEdit.getText().toString();
            if (value.isEmpty() || (fileName && !validName(value))) {
                content.inputLayout.setError(getString(R.string.fm_invalid_name));
                return;
            }
            current.dismiss();
            action.accept(value);
        });
        content.inputEdit.requestFocus();
        content.inputEdit.setSelection(initial.length());
    }

    private static boolean validName(String name) {
        return !name.equals(".") && !name.equals("..") && !name.contains("/")
                && name.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    private void permissions(FileEntry entry) {
        DialogPermissionsBinding content = DialogPermissionsBinding.inflate(getLayoutInflater());
        int[] mode = {entry.getMode() == null ? 0644 : entry.getMode() & ModeBits.MAX};
        boolean[] syncing = {false};
        boolean directory = entry.getKind() == FileKind.DIRECTORY;
        content.folderOptions.setVisibility(directory ? View.VISIBLE : View.GONE);

        MaterialCheckBox[][] grid = {
                {content.ownerRead, content.ownerWrite, content.ownerExecute},
                {content.groupRead, content.groupWrite, content.groupExecute},
                {content.othersRead, content.othersWrite, content.othersExecute}};
        int[] shifts = {ModeBits.OWNER, ModeBits.GROUP, ModeBits.OTHERS};
        int[] bits = {ModeBits.READ, ModeBits.WRITE, ModeBits.EXECUTE};
        MaterialCheckBox[] specials = {content.specialSetuid, content.specialSetgid, content.specialSticky};
        int[] flags = {ModeBits.SETUID, ModeBits.SETGID, ModeBits.STICKY};

        Runnable render = () -> {
            syncing[0] = true;
            for (int row = 0; row < 3; row++)
                for (int col = 0; col < 3; col++) grid[row][col].setChecked(ModeBits.has(mode[0], shifts[row], bits[col]));
            for (int i = 0; i < 3; i++) specials[i].setChecked((mode[0] & flags[i]) != 0);
            content.modeValue.setText(ModeBits.octal(mode[0]));
            content.modeSymbolic.setText(ModeBits.symbolic(mode[0]));
            syncing[0] = false;
        };
        // Checkboxes drive the number. The number field is rewritten only when it is not the one being typed in.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int shift = shifts[row], bit = bits[col];
                grid[row][col].setOnCheckedChangeListener((button, checked) -> {
                    if (syncing[0]) return;
                    mode[0] = ModeBits.with(mode[0], shift, bit, checked);
                    render.run();
                    content.inputEdit.setText(ModeBits.octal(mode[0]));
                    content.inputLayout.setError(null);
                });
            }
        }
        for (int i = 0; i < 3; i++) {
            int flag = flags[i];
            specials[i].setOnCheckedChangeListener((button, checked) -> {
                if (syncing[0]) return;
                mode[0] = ModeBits.withSpecial(mode[0], flag, checked);
                render.run();
                content.inputEdit.setText(ModeBits.octal(mode[0]));
                content.inputLayout.setError(null);
            });
        }
        content.inputEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable text) {
                if (syncing[0]) return;
                int parsed = ModeBits.parse(text);
                if (parsed < 0) {
                    // Keep the last valid selection on screen until the number is valid again.
                    content.inputLayout.setError(text.length() >= 3 ? getString(R.string.fm_perm_invalid) : null);
                    return;
                }
                content.inputLayout.setError(null);
                mode[0] = parsed;
                render.run();
            }
        });

        render.run();
        content.inputEdit.setText(ModeBits.octal(mode[0]));

        dialog = new MaterialAlertDialogBuilder(this).setIcon(R.drawable.ic_settings).setTitle(R.string.fm_permissions).setView(content.getRoot())
                .setPositiveButton(R.string.ok, null).setNegativeButton(R.string.cancel, null).create();
        dialog.show();
        AlertDialog current = dialog;
        current.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int value = ModeBits.parse(content.inputEdit.getText());
            if (value < 0) { content.inputLayout.setError(getString(R.string.fm_perm_invalid)); return; }
            boolean files = directory && content.applyFiles.isChecked();
            boolean folders = directory && content.applyFolders.isChecked();
            current.dismiss();
            model.execute(new FileRequest.SetPermissions(entry.getPath(), value, files || folders, files, folders));
        });
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

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
        } else Snackbar.make(binding.getRoot(), result.getReport().getSkippedCount() > 0
                ? getString(R.string.fm_skipped, result.getReport().getSkippedCount()) : getString(R.string.fm_done), Snackbar.LENGTH_SHORT).show();
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
            RowFileEntryBinding row;
            if (convert == null) {
                row = RowFileEntryBinding.inflate(getLayoutInflater(), parent, false);
                convert = row.getRoot();
                convert.setTag(row);
            } else row = (RowFileEntryBinding) convert.getTag();
            row.getRoot().updateAppearance(position, getCount());
            FileEntry entry = getItem(position);
            row.itemTitle.setText(entry.getName());
            row.itemSupporting.setText(entry.getKind() == FileKind.FILE
                    ? kind(entry) + " · " + android.text.format.Formatter.formatFileSize(GsiFileManagerActivity.this, entry.getSize())
                    : entry.getKind() == FileKind.SYMLINK ? entry.getSymlinkTarget() : kind(entry));
            boolean folder = entry.getKind() == FileKind.DIRECTORY;
            boolean link = entry.getKind() == FileKind.SYMLINK;
            row.itemIcon.setImageResource(folder ? R.drawable.ic_folder_open : link ? R.drawable.ic_link : R.drawable.ic_description);
            row.iconContainer.setCardBackgroundColor(MaterialColors.getColor(row.iconContainer, folder
                    ? com.google.android.material.R.attr.colorPrimaryContainer : link
                    ? com.google.android.material.R.attr.colorTertiaryContainer : com.google.android.material.R.attr.colorSecondaryContainer));
            row.itemIcon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(row.itemIcon, folder
                    ? com.google.android.material.R.attr.colorOnPrimaryContainer : link
                    ? com.google.android.material.R.attr.colorOnTertiaryContainer : com.google.android.material.R.attr.colorOnSecondaryContainer)));
            row.itemCard.setOnClickListener(v -> open(entry));
            row.itemCard.setOnLongClickListener(v -> { actions(entry); return true; });
            row.itemMore.setOnClickListener(v -> actions(entry));
            return convert;
        }
    }
}
