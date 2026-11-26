package com.ditzzy.dsunext.activity

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.ViewModelProvider
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.databinding.ActivityEditGsiBinding
import com.ditzzy.dsunext.databinding.DialogImportBinding
import com.ditzzy.dsunext.databinding.DialogOperationBinding
import com.ditzzy.dsunext.databinding.DialogRepackBinding
import com.ditzzy.dsunext.databinding.RowDialogItemBinding
import com.ditzzy.dsunext.model.Workspace
import com.ditzzy.dsunext.model.WorkspaceInfo
import com.ditzzy.dsunext.ui.Event
import com.ditzzy.dsunext.ui.InsetsUtils
import com.ditzzy.dsunext.ui.ListRow
import com.ditzzy.dsunext.ui.SegmentedGroup
import com.ditzzy.dsunext.ui.UiMessage
import com.ditzzy.dsunext.viewmodel.EditGsiViewModel
import com.ditzzy.dsunext.viewmodel.EditGsiViewModel.Access
import com.ditzzy.dsunext.viewmodel.EditGsiViewModel.Operation
import com.ditzzy.dsunext.workspace.WorkspaceNames
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import java.util.Locale

open class EditGsiActivity : AppCompatActivity() {
    private var binding: ActivityEditGsiBinding? = null

    private var viewModel: EditGsiViewModel? = null

    private var pickImage: ActivityResultLauncher<Array<String>>? = null

    private var saveImage: ActivityResultLauncher<String>? = null

    private var warningDialog: AlertDialog? = null

    private var operationDialog: AlertDialog? = null

    private var operationBinding: DialogOperationBinding? = null

    private var shownType: Operation.Type? = null

    private var shownStatus: Operation.Status? = null

    private var currentDialog: AlertDialog? = null

    protected override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityEditGsiBinding.inflate(getLayoutInflater())
        setContentView(binding!!.getRoot())
        binding!!.toolbar.setNavigationOnClickListener({ v ->
            finish()
        })
        InsetsUtils.padBottomAndSides(binding!!.scrollView)
        InsetsUtils.marginBottomAndSides(binding!!.fabImport)
        pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument(), { uri ->
            if ((uri != null)) {
                showImportDialog(uri)
            }
        })
        saveImage = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), { uri ->
            if ((uri != null)) {
                saveRepackedImage(uri)
            }
        })
        binding!!.fabImport.setOnClickListener({ v ->
            pickImage!!.launch(arrayOf("*/*"))
        })
        binding!!.messageButton.setOnClickListener({ v ->
            viewModel!!.checkAccess()
        })
        viewModel = ViewModelProvider(this).`get`(EditGsiViewModel::class.java)
        viewModel!!.getAccess().observe(this, { access ->
            renderContent()
        })
        viewModel!!.getWorkspaces().observe(this, { list ->
            renderContent()
        })
        viewModel!!.getOperation().observe(this, this::renderOperation)
        viewModel!!.getMessages().observe(this, this::showMessage)
        viewModel!!.getInfo().observe(this, this::showInfo)
        if (viewModel!!.isWarningAccepted()) {
            viewModel!!.start()
        } else {
            showWarningDialog()
        }
    }

    protected override fun onDestroy() {
        dismissQuietly(warningDialog)
        dismissQuietly(operationDialog)
        dismissQuietly(currentDialog)
        warningDialog = null
        operationDialog = null
        currentDialog = null
        super.onDestroy()
    }

    private fun showWarningDialog() {
        warningDialog = MaterialAlertDialogBuilder(this).setIcon(R.drawable.ic_warning).setTitle(R.string.gsi_warning_title).setMessage(R.string.gsi_warning_message).setCancelable(false).setPositiveButton(R.string.gsi_warning_continue, { dialog, which ->
            viewModel!!.acceptWarning()
            viewModel!!.start()
        }).setNegativeButton(R.string.gsi_warning_back, { dialog, which ->
            finish()
        }).show()
    }

    private fun renderContent() {
        var access: Access? = viewModel!!.getAccess().getValue()
        if ((access == null)) {
            access = Access.CHECKING
        }
        val workspaces: List<Workspace>? = viewModel!!.getWorkspaces().getValue()
        binding!!.loadingContainer.setVisibility((if ((access == Access.CHECKING)) View.VISIBLE else View.GONE))
        binding!!.fabImport.setVisibility((if ((access == Access.READY)) View.VISIBLE else View.GONE))
        when (access) {
            EditGsiViewModel.Access.CHECKING -> {
                hideMessage()
                binding!!.listSection.setVisibility(View.GONE)
            }
            EditGsiViewModel.Access.SERVICE_ERROR -> {
                showStatus(R.drawable.ic_warning, R.string.gsi_service_error_title, java.lang.String.valueOf(viewModel!!.getAccessDetail().getValue()), true)
                binding!!.listSection.setVisibility(View.GONE)
            }
            EditGsiViewModel.Access.READY -> {
                if (((workspaces == null) || workspaces!!.isEmpty())) {
                    showStatus(R.drawable.ic_folder_open, R.string.gsi_empty_title, getString(R.string.gsi_empty_body), false)
                    binding!!.listSection.setVisibility(View.GONE)
                } else {
                    hideMessage()
                    binding!!.listSection.setVisibility(View.VISIBLE)
                    renderWorkspaces(workspaces!!)
                }
            }
        }
    }

    private fun showStatus(@DrawableRes icon: Int, @StringRes title: Int, body: String, retry: Boolean) {
        binding!!.messageIcon.setImageResource(icon)
        binding!!.messageTitle.setText(title)
        binding!!.messageBody.setText(body)
        binding!!.messageButton.setVisibility((if (retry) View.VISIBLE else View.GONE))
        binding!!.messageContainer.setVisibility(View.VISIBLE)
    }

    private fun hideMessage() {
        binding!!.messageContainer.setVisibility(View.GONE)
    }

    private fun renderWorkspaces(workspaces: List<Workspace>) {
        val group: LinearLayout = binding!!.groupWorkspaces
        group.removeAllViews()
        for (workspace in workspaces) {
            val row: ListRow = ListRow.create(group).icon((if (workspace.isReady()) R.drawable.ic_folder_open else R.drawable.ic_warning)).title(workspace.getName()).supporting(describe(workspace)).onClick({ v ->
                onWorkspaceClicked(workspace)
            })
            row.trailingButton(R.drawable.ic_more_vert, getString(R.string.gsi_more_options), { v ->
                showWorkspaceMenu(v, workspace)
            })
        }
        SegmentedGroup.apply(group)
    }

    private fun describe(workspace: Workspace): String {
        if (!workspace.isReady()) {
            return getString(R.string.gsi_incomplete)
        }
        val text: StringBuilder = StringBuilder()
        if (!workspace.getFsType().isEmpty()) {
            text.append(workspace.getFsType().uppercase(Locale.ROOT))
        }
        if ((workspace.getImageSize() > 0L)) {
            appendPart(text, Formatter.formatFileSize(this, workspace.getImageSize()))
        }
        if (!workspace.getAndroidRelease().isEmpty()) {
            appendPart(text, ("Android " + workspace.getAndroidRelease()))
        }
        return text.toString()
    }

    private fun onWorkspaceClicked(workspace: Workspace) {
        if (workspace.isReady()) {
            showEditDialog(workspace)
        } else {
            Toast.makeText(this, R.string.gsi_incomplete, Toast.LENGTH_LONG).show()
        }
    }

    private fun showWorkspaceMenu(anchor: View, workspace: Workspace) {
        val popup: PopupMenu = PopupMenu(this, anchor)
        popup.getMenuInflater().inflate(R.menu.menu_workspace, popup.getMenu())
        popup.getMenu().findItem(R.id.action_repack).setEnabled(workspace.isReady())
        popup.setOnMenuItemClickListener(callback13@ { item ->
            val id: Int = item.getItemId()
            if ((id == R.id.action_repack)) {
                showRepackDialog(workspace)
            } else if ((id == R.id.action_delete)) {
                showDeleteDialog(workspace)
            } else if ((id == R.id.action_info)) {
                viewModel!!.loadInfo(workspace.getName())
            } else {
                return@callback13 false
            }
            return@callback13 true
        })
        popup.show()
    }

    private fun showImportDialog(uri: Uri) {
        val fileName: String = queryDisplayName(uri)
        val dialogBinding: DialogImportBinding = DialogImportBinding.inflate(getLayoutInflater())
        dialogBinding.importSource.setText(getString(R.string.gsi_import_source, fileName))
        val suggestion: String = viewModel!!.uniqueName(WorkspaceNames.suggest(fileName))
        dialogBinding.importNameEdit.setText(suggestion)
        dialogBinding.importNameEdit.setEnabled(false)
        dialogBinding.followName.setOnCheckedChangeListener({ button, checked ->
            dialogBinding.importNameEdit.setEnabled(!checked)
            dialogBinding.importNameLayout.setError(null)
            if (checked) {
                dialogBinding.importNameEdit.setText(suggestion)
            }
        })
        dialogBinding.importNameEdit.addTextChangedListener(clearErrorOnEdit(dialogBinding.importNameLayout))
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this).setTitle(R.string.gsi_import_title).setView(dialogBinding.getRoot()).setPositiveButton(R.string.gsi_import, null).setNegativeButton(R.string.cancel, null).create()
        showTransient(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener({ v ->
            val name: String = java.lang.String.valueOf(dialogBinding.importNameEdit.getText()).trim()
            if (!WorkspaceNames.isValid(name)) {
                dialogBinding.importNameLayout.setError(getString(R.string.gsi_name_invalid))
            } else if (viewModel!!.isNameTaken(name)) {
                dialogBinding.importNameLayout.setError(getString(R.string.gsi_name_taken))
            } else {
                dialog.dismiss()
                viewModel!!.importFile(uri, fileName, name)
            }
        })
    }

    private fun queryDisplayName(uri: Uri): String {
        try {
            getContentResolver().query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor ->
                if (((cursor != null) && cursor.moveToFirst())) {
                    val name: String? = cursor.getString(0)
                    if (((name != null) && !name!!.isEmpty())) {
                        return name!!
                    }
                }
            }
        } catch (ignored: RuntimeException) {

        }
        // Falls back to the last path segment
        val segment: String? = uri.getLastPathSegment()
        return (if (((segment == null) || segment!!.isEmpty())) "gsi.img" else segment!!)
    }

    private fun showEditDialog(workspace: Workspace) {
        startActivity(Intent(this, GsiFileManagerActivity::class.java).putExtra(GsiFileManagerActivity.EXTRA_WORKSPACE, workspace.getName()))
    }

    private fun showRepackDialog(workspace: Workspace) {
        val dialogBinding: DialogRepackBinding = DialogRepackBinding.inflate(getLayoutInflater())
        dialogBinding.outputEdit.setText(workspace.getName())
        // EROFS is always built as small as possible, so the choice would do nothing
        val sizeApplies: Boolean = !"erofs".equals(workspace.getFsType())
        if (!sizeApplies) {
            dialogBinding.repackNote.setVisibility(View.VISIBLE)
            run {
                var i: Int = 0
                while ((i < dialogBinding.sizeGroup.getChildCount())) {
                    dialogBinding.sizeGroup.getChildAt(i)!!.setEnabled(false)
                    i++
                }
            }
        }
        dialogBinding.sizeGroup.setOnCheckedChangeListener({ group, checkedId ->
            dialogBinding.sizeLayout.setVisibility((if ((checkedId == R.id.size_custom)) View.VISIBLE else View.GONE))
            dialogBinding.sizeLayout.setError(null)
        })
        dialogBinding.sizeEdit.addTextChangedListener(clearErrorOnEdit(dialogBinding.sizeLayout))
        dialogBinding.outputEdit.addTextChangedListener(clearErrorOnEdit(dialogBinding.outputLayout))
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this).setTitle(getString(R.string.gsi_repack_title, workspace.getName())).setView(dialogBinding.getRoot()).setPositiveButton(R.string.gsi_repack, null).setNegativeButton(R.string.cancel, null).create()
        showTransient(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(callback17@ { v ->
            val output: String = java.lang.String.valueOf(dialogBinding.outputEdit.getText()).trim()
            if (!WorkspaceNames.isValid(output)) {
                dialogBinding.outputLayout.setError(getString(R.string.gsi_name_invalid))
                return@callback17
            }
            var size: String? = null
            if (sizeApplies) {
                val checked: Int = dialogBinding.sizeGroup.getCheckedRadioButtonId()
                if ((checked == R.id.size_auto)) {
                    size = "auto"
                } else if ((checked == R.id.size_custom)) {
                    size = java.lang.String.valueOf(dialogBinding.sizeEdit.getText()).trim()
                    if (!WorkspaceNames.isValidSize(size)) {
                        dialogBinding.sizeLayout.setError(getString(R.string.gsi_size_invalid))
                        return@callback17
                    }
                }
            }
            dialog.dismiss()
            viewModel!!.repack(workspace.getName(), size, output)
        })
    }

    private fun saveRepackedImage(target: Uri) {
        val operation: Operation? = viewModel!!.getOperation().getValue()
        if ((((operation == null) || (operation!!.type != Operation.Type.REPACK)) || (operation!!.status != Operation.Status.DONE))) {
            return
        }
        viewModel!!.export(operation!!.workspace, operation!!.file, target)
    }

    private fun showDeleteDialog(workspace: Workspace) {
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this).setIcon(R.drawable.ic_delete).setTitle(getString(R.string.gsi_delete_title, workspace.getName())).setMessage(R.string.gsi_delete_message).setPositiveButton(R.string.gsi_delete, { d, which ->
            viewModel!!.delete(workspace.getName())
        }).setNegativeButton(R.string.cancel, null).create()
        showTransient(dialog)
    }

    private fun showInfo(event: Event<WorkspaceInfo>?) {
        val info: WorkspaceInfo? = (if ((event == null)) null else event!!.getContentIfNotHandled())
        if ((info == null)) {
            return
        }
        val content: LinearLayout = LinearLayout(this)
        content.setOrientation(LinearLayout.VERTICAL)
        val padding: Int = ((24 * getResources().getDisplayMetrics().density)).toInt()
        content.setPadding(padding, (padding / 3), padding, getResources().getDimensionPixelSize(R.dimen.dialog_bottom_with_actions))
        addInfoRow(content, R.drawable.ic_folder_open, R.string.gsi_info_path, info!!.`get`("path"))
        if ("recovery".equals(info!!.`get`("state"))) {
            addInfoRow(content, R.drawable.ic_warning, R.string.gsi_info_state, getString(R.string.fm_recovery))
        } else if ("incomplete".equals(info!!.`get`("state"))) {
            addInfoRow(content, R.drawable.ic_warning, R.string.gsi_info_state, getString(R.string.gsi_info_state_incomplete))
        }
        addInfoRow(content, R.drawable.ic_file, R.string.gsi_info_source, info!!.`get`("source"))
        if ((info!!.getLong("imported_at") > 0L)) {
            addInfoRow(content, R.drawable.ic_info, R.string.gsi_info_imported, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(info!!.getLong("imported_at"))))
        }
        addInfoRow(content, R.drawable.ic_storage, R.string.gsi_info_fs, info!!.`get`("fs_type").uppercase(Locale.ROOT))
        addInfoRow(content, R.drawable.ic_info, R.string.gsi_info_mount, info!!.`get`("mount_point"))
        if ((info!!.getLong("image_size") > 0L)) {
            addInfoRow(content, R.drawable.ic_storage, R.string.gsi_info_image_size, Formatter.formatFileSize(this, info!!.getLong("image_size")))
        }
        if ((info!!.getLong("bytes") > 0L)) {
            addInfoRow(content, R.drawable.ic_storage, R.string.gsi_info_extracted, Formatter.formatFileSize(this, info!!.getLong("bytes")))
        }
        if (info!!.has("files")) {
            addInfoRow(content, R.drawable.ic_description, R.string.gsi_info_contents, getString(R.string.gsi_info_contents_value, info!!.getLong("files"), info!!.getLong("dirs"), info!!.getLong("symlinks")))
        }
        if (info!!.has("android_release")) {
            addInfoRow(content, R.drawable.ic_phone, R.string.gsi_info_android, getString(R.string.gsi_info_android_value, info!!.`get`("android_release"), (if (info!!.has("android_sdk")) info!!.`get`("android_sdk") else "?")))
        }
        addInfoRow(content, R.drawable.ic_code, R.string.gsi_info_build, info!!.`get`("build_id"))
        addInfoRow(content, R.drawable.ic_code, R.string.gsi_info_fingerprint, info!!.`get`("fingerprint"))
        addInfoRow(content, R.drawable.ic_info, R.string.gsi_info_block_size, info!!.`get`("block_size"))
        addInfoRow(content, R.drawable.ic_info, R.string.gsi_info_uuid, info!!.`get`("uuid"))
        if (info!!.has("repack_file")) {
            var repack: String = info!!.`get`("repack_file")
            if ((info!!.getLong("repack_size") > 0L)) {
                repack += (" · " + Formatter.formatFileSize(this, info!!.getLong("repack_size")))
            }
            if ((info!!.getLong("repack_at") > 0L)) {
                repack += ("\n" + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(info!!.getLong("repack_at"))))
            }
            addInfoRow(content, R.drawable.ic_save, R.string.gsi_info_repack, repack)
        }
        val scroll: ScrollView = ScrollView(this)
        scroll.addView(content)
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this).setTitle(info!!.`get`("name")).setView(scroll).setPositiveButton(R.string.close, null).create()
        showTransient(dialog)
    }

    /** Rows without a value are left out, an old workspace may not have everything. */
    private fun addInfoRow(parent: LinearLayout, @DrawableRes icon: Int, @StringRes label: Int, value: String?) {
        if (((value == null) || value!!.isEmpty())) {
            return
        }
        val item: RowDialogItemBinding = RowDialogItemBinding.inflate(LayoutInflater.from(this), parent, false)
        item.dialogItemIcon.setImageResource(icon)
        item.dialogItemLabel.setText(label)
        item.dialogItemValue.setText(value)
        item.dialogItemValue.setTextIsSelectable(true)
        parent.addView(item.getRoot())
    }

    private fun renderOperation(operation: Operation?) {
        if ((operation == null)) {
            dismissQuietly(operationDialog)
            operationDialog = null
            operationBinding = null
            shownType = null
            shownStatus = null
            return
        }
        // A new dialog for every step that changes the buttons, the rest updates in place
        if ((((operationDialog == null) || (shownType != operation!!.type)) || (shownStatus != operation!!.status))) {
            showOperationDialog(operation!!)
        }
        updateOperationDialog(operation!!)
    }

    private fun showOperationDialog(operation: Operation) {
        dismissQuietly(operationDialog)
        operationBinding = DialogOperationBinding.inflate(getLayoutInflater())
        shownType = operation.type
        shownStatus = operation.status
        // A running job has no buttons, so the content has to supply the bottom space of the dialog itself.
        val root: View = operationBinding!!.getRoot()
        root.setPadding(root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), getResources().getDimensionPixelSize((if ((operation.status == Operation.Status.RUNNING)) R.dimen.dialog_bottom_no_actions else R.dimen.dialog_bottom_with_actions)))
        val builder: MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(this).setView(operationBinding!!.getRoot()).setCancelable(false)
        when (operation.status) {
            EditGsiViewModel.Operation.Status.DONE -> {
                builder.setTitle(R.string.gsi_repack_done).setPositiveButton(R.string.gsi_save, null).setNegativeButton(R.string.close, null)
            }
            EditGsiViewModel.Operation.Status.FAILED -> {
                builder.setTitle(failureTitle(operation.type)).setPositiveButton(R.string.close, null)
            }
            else -> {

            }
        }
        operationDialog = builder.create()
        operationDialog!!.show()
        when (operation.status) {
            EditGsiViewModel.Operation.Status.DONE -> {
                operationDialog!!.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener({ v ->
                    saveImage!!.launch(operation.file)
                })
                operationDialog!!.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener({ v ->
                    viewModel!!.dismissOperation()
                })
            }
            EditGsiViewModel.Operation.Status.FAILED -> {
                operationDialog!!.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener({ v ->
                    viewModel!!.dismissOperation()
                })
            }
            else -> {

            }
        }
    }

    private fun updateOperationDialog(operation: Operation) {
        val views: DialogOperationBinding? = operationBinding
        if ((views == null)) {
            return
        }
        when (operation.status) {
            EditGsiViewModel.Operation.Status.RUNNING -> {
                views!!.opMessage.setText(runningMessage(operation))
                views!!.opProgress.setVisibility(View.VISIBLE)
                if ((operation.percent < 0)) {
                    views!!.opProgress.setIndeterminate(true)
                } else {
                    views!!.opProgress.setIndeterminate(false)
                    views!!.opProgress.setProgressCompat(operation.percent, true)
                }
            }
            EditGsiViewModel.Operation.Status.DONE -> {
                views!!.opProgress.setVisibility(View.GONE)
                views!!.opMessage.setText(doneMessage(operation))
            }
            EditGsiViewModel.Operation.Status.FAILED -> {
                views!!.opProgress.setVisibility(View.GONE)
                views!!.opMessage.setText(operation.message)
            }
        }
        val hasLog: Boolean = !operation.log.isEmpty()
        views!!.opLogCard.setVisibility((if (hasLog) View.VISIBLE else View.GONE))
        if (hasLog) {
            views!!.opLog.setText(operation.log)
            views!!.opLogScroll.post({
                views!!.opLogScroll.fullScroll(View.FOCUS_DOWN)
            })
        }
    }

    private fun runningMessage(operation: Operation): String {
        when (operation.type) {
            Operation.Type.IMPORT -> {
                return getString(R.string.gsi_importing, operation.workspace)
            }
            Operation.Type.REPACK -> {
                return getString(R.string.gsi_repacking, operation.workspace)
            }
            Operation.Type.EXPORT -> {
                return getString(R.string.gsi_saving)
            }
            else -> {
                return getString(R.string.gsi_deleting, operation.workspace)
            }
        }
    }

    private fun doneMessage(operation: Operation): String {
        var message: String = getString(R.string.gsi_repack_summary, operation.file, Formatter.formatFileSize(this, operation.size))
        if ((operation.newEntries > 0)) {
            message += ("\n\n" + getString(R.string.gsi_repack_new_entries, operation.newEntries))
        }
        return message
    }

    private fun showMessage(event: Event<UiMessage>?) {
        val message: UiMessage? = (if ((event == null)) null else event!!.getContentIfNotHandled())
        if ((message != null)) {
            Toast.makeText(this, message!!.resolve(this), Toast.LENGTH_SHORT).show()
        }
    }

    /** Shows a short lived dialog, replacing the previous one. */
    private fun showTransient(dialog: AlertDialog) {
        dismissQuietly(currentDialog)
        currentDialog = dialog
        dialog.setOnDismissListener({ d ->
            if ((currentDialog == dialog)) {
                currentDialog = null
            }
        })
        dialog.show()
    }

    companion object {
        private fun appendPart(text: StringBuilder, part: String) {
            if ((text.length > 0)) {
                text.append(" · ")
            }
            text.append(part)
        }

        @StringRes
        private fun failureTitle(type: Operation.Type): Int {
            when (type) {
                EditGsiViewModel.Operation.Type.IMPORT -> {
                    return R.string.gsi_failed_import
                }
                EditGsiViewModel.Operation.Type.REPACK -> {
                    return R.string.gsi_failed_repack
                }
                EditGsiViewModel.Operation.Type.EXPORT -> {
                    return R.string.gsi_failed_save
                }
                else -> {
                    return R.string.gsi_failed_delete
                }
            }
        }

        private fun dismissQuietly(dialog: AlertDialog?) {
            if (((dialog != null) && dialog!!.isShowing())) {
                dialog!!.dismiss()
            }
        }

        private fun clearErrorOnEdit(layout: com.google.android.material.textfield.TextInputLayout): TextWatcher {
            return object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {

                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                    layout.setError(null)
                }

                override fun afterTextChanged(s: Editable) {

                }
            }
        }
    }
}
