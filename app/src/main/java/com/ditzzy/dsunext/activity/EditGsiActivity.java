package com.ditzzy.dsunext.activity;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.StrictMode;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.lifecycle.ViewModelProvider;

import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.databinding.ActivityEditGsiBinding;
import com.ditzzy.dsunext.databinding.DialogImportBinding;
import com.ditzzy.dsunext.databinding.DialogOperationBinding;
import com.ditzzy.dsunext.databinding.DialogRepackBinding;
import com.ditzzy.dsunext.databinding.RowDialogItemBinding;
import com.ditzzy.dsunext.model.Workspace;
import com.ditzzy.dsunext.model.WorkspaceInfo;
import com.ditzzy.dsunext.ui.Event;
import com.ditzzy.dsunext.ui.InsetsUtils;
import com.ditzzy.dsunext.ui.ListRow;
import com.ditzzy.dsunext.ui.SegmentedGroup;
import com.ditzzy.dsunext.ui.UiMessage;
import com.ditzzy.dsunext.viewmodel.EditGsiViewModel;
import com.ditzzy.dsunext.viewmodel.EditGsiViewModel.Access;
import com.ditzzy.dsunext.viewmodel.EditGsiViewModel.Operation;
import com.ditzzy.dsunext.workspace.WorkspaceNames;
import com.ditzzy.dsunext.workspace.WorkspaceStore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class EditGsiActivity extends AppCompatActivity {

    /** Folders are opened with this type by most file managers. */
    private static final String FOLDER_MIME_TYPE = "resource/folder";

    private ActivityEditGsiBinding binding;
    private EditGsiViewModel viewModel;

    private ActivityResultLauncher<String[]> pickImage;
    private ActivityResultLauncher<String> saveImage;

    private AlertDialog warningDialog;
    private AlertDialog operationDialog;
    private DialogOperationBinding operationBinding;
    private Operation.Type shownType;
    private Operation.Status shownStatus;
    private AlertDialog currentDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityEditGsiBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        InsetsUtils.padBottomAndSides(binding.scrollView);
        InsetsUtils.marginBottomAndSides(binding.fabImport);

        pickImage = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                showImportDialog(uri);
            }
        });
        saveImage = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                    if (uri != null) {
                        saveRepackedImage(uri);
                    }
                });

        binding.fabImport.setOnClickListener(v -> pickImage.launch(new String[]{"*/*"}));
        binding.messageButton.setOnClickListener(v -> viewModel.checkRoot());

        viewModel = new ViewModelProvider(this).get(EditGsiViewModel.class);
        viewModel.getAccess().observe(this, access -> renderContent());
        viewModel.getWorkspaces().observe(this, list -> renderContent());
        viewModel.getOperation().observe(this, this::renderOperation);
        viewModel.getMessages().observe(this, this::showMessage);
        viewModel.getInfo().observe(this, this::showInfo);

        // Every visit starts with the warning, a rotation does not repeat it
        if (viewModel.isWarningAccepted()) {
            viewModel.start();
        } else {
            showWarningDialog();
        }
    }

    @Override
    protected void onDestroy() {
        dismissQuietly(warningDialog);
        dismissQuietly(operationDialog);
        dismissQuietly(currentDialog);
        warningDialog = null;
        operationDialog = null;
        currentDialog = null;
        super.onDestroy();
    }

    // region Warning and root access

    private void showWarningDialog() {
        warningDialog = new MaterialAlertDialogBuilder(this)
                .setIcon(R.drawable.ic_warning)
                .setTitle(R.string.gsi_warning_title)
                .setMessage(R.string.gsi_warning_message)
                .setCancelable(false)
                .setPositiveButton(R.string.gsi_warning_continue, (dialog, which) -> {
                    viewModel.acceptWarning();
                    viewModel.start();
                })
                .setNegativeButton(R.string.gsi_warning_back, (dialog, which) -> finish())
                .show();
    }

    // endregion

    // region Content

    private void renderContent() {
        Access access = viewModel.getAccess().getValue();
        if (access == null) {
            access = Access.CHECKING;
        }
        List<Workspace> workspaces = viewModel.getWorkspaces().getValue();

        binding.loadingContainer.setVisibility(access == Access.CHECKING ? View.VISIBLE : View.GONE);
        binding.fabImport.setVisibility(access == Access.READY ? View.VISIBLE : View.GONE);

        switch (access) {
            case CHECKING:
                hideMessage();
                binding.listSection.setVisibility(View.GONE);
                break;
            case NO_ROOT:
                showStatus(R.drawable.ic_warning, R.string.gsi_no_root_title,
                        getString(R.string.gsi_no_root_body), true);
                binding.listSection.setVisibility(View.GONE);
                break;
            case SERVICE_ERROR:
                showStatus(R.drawable.ic_warning, R.string.gsi_service_error_title,
                        String.valueOf(viewModel.getAccessDetail().getValue()), true);
                binding.listSection.setVisibility(View.GONE);
                break;
            case READY:
                if (workspaces == null || workspaces.isEmpty()) {
                    showStatus(R.drawable.ic_folder_open, R.string.gsi_empty_title,
                            getString(R.string.gsi_empty_body), false);
                    binding.listSection.setVisibility(View.GONE);
                } else {
                    hideMessage();
                    binding.listSection.setVisibility(View.VISIBLE);
                    renderWorkspaces(workspaces);
                }
                break;
        }
    }

    private void showStatus(@DrawableRes int icon, @StringRes int title, String body, boolean retry) {
        binding.messageIcon.setImageResource(icon);
        binding.messageTitle.setText(title);
        binding.messageBody.setText(body);
        binding.messageButton.setVisibility(retry ? View.VISIBLE : View.GONE);
        binding.messageContainer.setVisibility(View.VISIBLE);
    }

    private void hideMessage() {
        binding.messageContainer.setVisibility(View.GONE);
    }

    private void renderWorkspaces(List<Workspace> workspaces) {
        LinearLayout group = binding.groupWorkspaces;
        group.removeAllViews();

        for (Workspace workspace : workspaces) {
            ListRow row = ListRow.create(group)
                    .icon(workspace.isReady() ? R.drawable.ic_folder_open : R.drawable.ic_warning)
                    .title(workspace.getName())
                    .supporting(describe(workspace))
                    .onClick(v -> onWorkspaceClicked(workspace));
            row.trailingButton(R.drawable.ic_more_vert, getString(R.string.gsi_more_options),
                    v -> showWorkspaceMenu(v, workspace));
        }
        SegmentedGroup.apply(group);
    }

    private String describe(Workspace workspace) {
        if (!workspace.isReady()) {
            return getString(R.string.gsi_incomplete);
        }
        StringBuilder text = new StringBuilder();
        if (!workspace.getFsType().isEmpty()) {
            text.append(workspace.getFsType().toUpperCase(Locale.ROOT));
        }
        if (workspace.getImageSize() > 0L) {
            appendPart(text, Formatter.formatFileSize(this, workspace.getImageSize()));
        }
        if (!workspace.getAndroidRelease().isEmpty()) {
            appendPart(text, "Android " + workspace.getAndroidRelease());
        }
        return text.toString();
    }

    private static void appendPart(StringBuilder text, String part) {
        if (text.length() > 0) {
            text.append(" · ");
        }
        text.append(part);
    }

    private void onWorkspaceClicked(Workspace workspace) {
        if (workspace.isReady()) {
            showEditDialog(workspace);
        } else {
            Toast.makeText(this, R.string.gsi_incomplete, Toast.LENGTH_LONG).show();
        }
    }

    private void showWorkspaceMenu(View anchor, Workspace workspace) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_workspace, popup.getMenu());
        popup.getMenu().findItem(R.id.action_repack).setEnabled(workspace.isReady());
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_repack) {
                showRepackDialog(workspace);
            } else if (id == R.id.action_delete) {
                showDeleteDialog(workspace);
            } else if (id == R.id.action_info) {
                viewModel.loadInfo(workspace.getName());
            } else {
                return false;
            }
            return true;
        });
        popup.show();
    }

    // endregion

    // region Import

    private void showImportDialog(Uri uri) {
        String fileName = queryDisplayName(uri);
        DialogImportBinding dialogBinding = DialogImportBinding.inflate(getLayoutInflater());
        dialogBinding.importSource.setText(getString(R.string.gsi_import_source, fileName));

        String suggestion = viewModel.uniqueName(WorkspaceNames.suggest(fileName));
        dialogBinding.importNameEdit.setText(suggestion);
        dialogBinding.importNameEdit.setEnabled(false);
        dialogBinding.followName.setOnCheckedChangeListener((button, checked) -> {
            dialogBinding.importNameEdit.setEnabled(!checked);
            dialogBinding.importNameLayout.setError(null);
            if (checked) {
                dialogBinding.importNameEdit.setText(suggestion);
            }
        });
        dialogBinding.importNameEdit.addTextChangedListener(clearErrorOnEdit(dialogBinding.importNameLayout));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.gsi_import_title)
                .setView(dialogBinding.getRoot())
                .setPositiveButton(R.string.gsi_import, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        showTransient(dialog);

        // Set after show() so an invalid name keeps the dialog open
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = String.valueOf(dialogBinding.importNameEdit.getText()).trim();
            if (!WorkspaceNames.isValid(name)) {
                dialogBinding.importNameLayout.setError(getString(R.string.gsi_name_invalid));
            } else if (viewModel.isNameTaken(name)) {
                dialogBinding.importNameLayout.setError(getString(R.string.gsi_name_taken));
            } else {
                dialog.dismiss();
                viewModel.importFile(uri, fileName, name);
            }
        });
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (RuntimeException ignored) {
            // Falls back to the last path segment
        }
        String segment = uri.getLastPathSegment();
        return segment == null || segment.isEmpty() ? "gsi.img" : segment;
    }

    // endregion

    // region Edit with a file manager

    private void showEditDialog(Workspace workspace) {
        String path = WorkspaceStore.BASE_DIR + "/" + workspace.getName();
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(workspace.getName())
                .setMessage(getString(R.string.gsi_edit_message, path))
                .setPositiveButton(R.string.gsi_open, (d, which) -> openInFileManager(path))
                .setNegativeButton(R.string.close, null)
                .setNeutralButton(R.string.gsi_copy_path, (d, which) -> copyPath(path))
                .create();
        showTransient(dialog);
    }

    /**
     * Shows the "Open with" list for the workspace folder. MT Manager has to be picked there and
     * needs root access of its own, the folder is not readable without it.
     */
    private void openInFileManager(String path) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.fromFile(new File(path)), FOLDER_MIME_TYPE);

        // A file:// URI is the only way to point a root file manager at /data/local, and Android
        // rejects it unless the check is switched off for this one call
        StrictMode.VmPolicy previous = StrictMode.getVmPolicy();
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        try {
            startActivity(Intent.createChooser(view, getString(R.string.gsi_open_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.gsi_open_failed, Toast.LENGTH_LONG).show();
        } finally {
            StrictMode.setVmPolicy(previous);
        }
    }

    private void copyPath(String path) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("path", path));
            Toast.makeText(this, R.string.gsi_path_copied, Toast.LENGTH_SHORT).show();
        }
    }

    // endregion

    // region Repack

    private void showRepackDialog(Workspace workspace) {
        DialogRepackBinding dialogBinding = DialogRepackBinding.inflate(getLayoutInflater());
        dialogBinding.outputEdit.setText(workspace.getName());

        // EROFS is always built as small as possible, so the choice would do nothing
        boolean sizeApplies = !"erofs".equals(workspace.getFsType());
        if (!sizeApplies) {
            dialogBinding.repackNote.setVisibility(View.VISIBLE);
            for (int i = 0; i < dialogBinding.sizeGroup.getChildCount(); i++) {
                dialogBinding.sizeGroup.getChildAt(i).setEnabled(false);
            }
        }
        dialogBinding.sizeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            dialogBinding.sizeLayout.setVisibility(
                    checkedId == R.id.size_custom ? View.VISIBLE : View.GONE);
            dialogBinding.sizeLayout.setError(null);
        });
        dialogBinding.sizeEdit.addTextChangedListener(clearErrorOnEdit(dialogBinding.sizeLayout));
        dialogBinding.outputEdit.addTextChangedListener(clearErrorOnEdit(dialogBinding.outputLayout));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.gsi_repack_title, workspace.getName()))
                .setView(dialogBinding.getRoot())
                .setPositiveButton(R.string.gsi_repack, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        showTransient(dialog);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String output = String.valueOf(dialogBinding.outputEdit.getText()).trim();
            if (!WorkspaceNames.isValid(output)) {
                dialogBinding.outputLayout.setError(getString(R.string.gsi_name_invalid));
                return;
            }

            String size = null;
            if (sizeApplies) {
                int checked = dialogBinding.sizeGroup.getCheckedRadioButtonId();
                if (checked == R.id.size_auto) {
                    size = "auto";
                } else if (checked == R.id.size_custom) {
                    size = String.valueOf(dialogBinding.sizeEdit.getText()).trim();
                    if (!WorkspaceNames.isValidSize(size)) {
                        dialogBinding.sizeLayout.setError(getString(R.string.gsi_size_invalid));
                        return;
                    }
                }
            }
            dialog.dismiss();
            viewModel.repack(workspace.getName(), size, output);
        });
    }

    private void saveRepackedImage(Uri target) {
        Operation operation = viewModel.getOperation().getValue();
        if (operation == null || operation.type != Operation.Type.REPACK
                || operation.status != Operation.Status.DONE) {
            return;
        }
        viewModel.export(operation.workspace, operation.file, target);
    }

    // endregion

    // region Delete and info

    private void showDeleteDialog(Workspace workspace) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setIcon(R.drawable.ic_delete)
                .setTitle(getString(R.string.gsi_delete_title, workspace.getName()))
                .setMessage(R.string.gsi_delete_message)
                .setPositiveButton(R.string.gsi_delete, (d, which) -> viewModel.delete(workspace.getName()))
                .setNegativeButton(R.string.cancel, null)
                .create();
        showTransient(dialog);
    }

    private void showInfo(@Nullable Event<WorkspaceInfo> event) {
        WorkspaceInfo info = event == null ? null : event.getContentIfNotHandled();
        if (info == null) {
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 3, padding, 0);

        addInfoRow(content, R.drawable.ic_folder_open, R.string.gsi_info_path, info.get("path"));
        if ("incomplete".equals(info.get("state"))) {
            addInfoRow(content, R.drawable.ic_warning, R.string.gsi_info_state,
                    getString(R.string.gsi_info_state_incomplete));
        }
        addInfoRow(content, R.drawable.ic_file, R.string.gsi_info_source, info.get("source"));
        if (info.getLong("imported_at") > 0L) {
            addInfoRow(content, R.drawable.ic_info, R.string.gsi_info_imported,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(new Date(info.getLong("imported_at"))));
        }
        addInfoRow(content, R.drawable.ic_storage, R.string.gsi_info_fs,
                info.get("fs_type").toUpperCase(Locale.ROOT));
        addInfoRow(content, R.drawable.ic_info, R.string.gsi_info_mount, info.get("mount_point"));
        if (info.getLong("image_size") > 0L) {
            addInfoRow(content, R.drawable.ic_storage, R.string.gsi_info_image_size,
                    Formatter.formatFileSize(this, info.getLong("image_size")));
        }
        if (info.getLong("bytes") > 0L) {
            addInfoRow(content, R.drawable.ic_storage, R.string.gsi_info_extracted,
                    Formatter.formatFileSize(this, info.getLong("bytes")));
        }
        if (info.has("files")) {
            addInfoRow(content, R.drawable.ic_description, R.string.gsi_info_contents,
                    getString(R.string.gsi_info_contents_value, info.getLong("files"),
                            info.getLong("dirs"), info.getLong("symlinks")));
        }
        if (info.has("android_release")) {
            addInfoRow(content, R.drawable.ic_phone, R.string.gsi_info_android,
                    getString(R.string.gsi_info_android_value, info.get("android_release"),
                            info.has("android_sdk") ? info.get("android_sdk") : "?"));
        }
        addInfoRow(content, R.drawable.ic_code, R.string.gsi_info_build, info.get("build_id"));
        addInfoRow(content, R.drawable.ic_code, R.string.gsi_info_fingerprint, info.get("fingerprint"));
        addInfoRow(content, R.drawable.ic_info, R.string.gsi_info_block_size, info.get("block_size"));
        addInfoRow(content, R.drawable.ic_info, R.string.gsi_info_uuid, info.get("uuid"));
        if (info.has("repack_file")) {
            String repack = info.get("repack_file");
            if (info.getLong("repack_size") > 0L) {
                repack += " · " + Formatter.formatFileSize(this, info.getLong("repack_size"));
            }
            if (info.getLong("repack_at") > 0L) {
                repack += "\n" + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(new Date(info.getLong("repack_at")));
            }
            addInfoRow(content, R.drawable.ic_save, R.string.gsi_info_repack, repack);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(info.get("name"))
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .create();
        showTransient(dialog);
    }

    /** Rows without a value are left out, an old workspace may not have everything. */
    private void addInfoRow(LinearLayout parent, @DrawableRes int icon, @StringRes int label,
            String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        RowDialogItemBinding item = RowDialogItemBinding.inflate(LayoutInflater.from(this), parent, false);
        item.dialogItemIcon.setImageResource(icon);
        item.dialogItemLabel.setText(label);
        item.dialogItemValue.setText(value);
        item.dialogItemValue.setTextIsSelectable(true);
        parent.addView(item.getRoot());
    }

    // endregion

    // region Running job

    private void renderOperation(@Nullable Operation operation) {
        if (operation == null) {
            dismissQuietly(operationDialog);
            operationDialog = null;
            operationBinding = null;
            shownType = null;
            shownStatus = null;
            return;
        }

        // A new dialog for every step that changes the buttons, the rest updates in place
        if (operationDialog == null || shownType != operation.type || shownStatus != operation.status) {
            showOperationDialog(operation);
        }
        updateOperationDialog(operation);
    }

    private void showOperationDialog(Operation operation) {
        dismissQuietly(operationDialog);
        operationBinding = DialogOperationBinding.inflate(getLayoutInflater());
        shownType = operation.type;
        shownStatus = operation.status;

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setView(operationBinding.getRoot())
                .setCancelable(false);

        switch (operation.status) {
            case DONE:
                builder.setTitle(R.string.gsi_repack_done)
                        .setPositiveButton(R.string.gsi_save, null)
                        .setNegativeButton(R.string.close, null);
                break;
            case FAILED:
                builder.setTitle(failureTitle(operation.type))
                        .setPositiveButton(R.string.close, null);
                break;
            default:
                // Nothing to press while it runs
                break;
        }
        operationDialog = builder.create();
        operationDialog.show();

        // Both buttons keep the dialog open, it is closed through the view model
        switch (operation.status) {
            case DONE:
                operationDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                        v -> saveImage.launch(operation.file));
                operationDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(
                        v -> viewModel.dismissOperation());
                break;
            case FAILED:
                operationDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                        v -> viewModel.dismissOperation());
                break;
            default:
                break;
        }
    }

    private void updateOperationDialog(Operation operation) {
        DialogOperationBinding views = operationBinding;
        if (views == null) {
            return;
        }

        switch (operation.status) {
            case RUNNING:
                views.opMessage.setText(runningMessage(operation));
                views.opProgress.setVisibility(View.VISIBLE);
                if (operation.percent < 0) {
                    views.opProgress.setIndeterminate(true);
                } else {
                    views.opProgress.setIndeterminate(false);
                    views.opProgress.setProgressCompat(operation.percent, true);
                }
                break;
            case DONE:
                views.opProgress.setVisibility(View.GONE);
                views.opMessage.setText(doneMessage(operation));
                break;
            case FAILED:
                views.opProgress.setVisibility(View.GONE);
                views.opMessage.setText(operation.message);
                break;
        }

        boolean hasLog = !operation.log.isEmpty();
        views.opLogCard.setVisibility(hasLog ? View.VISIBLE : View.GONE);
        if (hasLog) {
            views.opLog.setText(operation.log);
            views.opLogScroll.post(() -> views.opLogScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private String runningMessage(Operation operation) {
        switch (operation.type) {
            case IMPORT:
                return getString(R.string.gsi_importing, operation.workspace);
            case REPACK:
                return getString(R.string.gsi_repacking, operation.workspace);
            case EXPORT:
                return getString(R.string.gsi_saving);
            default:
                return getString(R.string.gsi_deleting, operation.workspace);
        }
    }

    private String doneMessage(Operation operation) {
        String message = getString(R.string.gsi_repack_summary, operation.file,
                Formatter.formatFileSize(this, operation.size));
        if (operation.newEntries > 0) {
            message += "\n\n" + getString(R.string.gsi_repack_new_entries, operation.newEntries);
        }
        return message;
    }

    @StringRes
    private static int failureTitle(Operation.Type type) {
        switch (type) {
            case IMPORT:
                return R.string.gsi_failed_import;
            case REPACK:
                return R.string.gsi_failed_repack;
            case EXPORT:
                return R.string.gsi_failed_save;
            default:
                return R.string.gsi_failed_delete;
        }
    }

    // endregion

    // region Helpers

    private void showMessage(@Nullable Event<UiMessage> event) {
        UiMessage message = event == null ? null : event.getContentIfNotHandled();
        if (message != null) {
            Toast.makeText(this, message.resolve(this), Toast.LENGTH_SHORT).show();
        }
    }

    /** Shows a short lived dialog, replacing the previous one. */
    private void showTransient(AlertDialog dialog) {
        dismissQuietly(currentDialog);
        currentDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (currentDialog == dialog) {
                currentDialog = null;
            }
        });
        dialog.show();
    }

    private static void dismissQuietly(@Nullable AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private static TextWatcher clearErrorOnEdit(com.google.android.material.textfield.TextInputLayout layout) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                layout.setError(null);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
    }

    // endregion
}
