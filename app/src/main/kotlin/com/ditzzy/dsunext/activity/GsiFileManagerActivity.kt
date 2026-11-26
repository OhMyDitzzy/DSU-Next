package com.ditzzy.dsunext.activity

import android.content.Intent
import android.content.res.ColorStateList
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.databinding.ActivityGsiFileManagerBinding
import com.ditzzy.dsunext.databinding.DialogFileInputBinding
import com.ditzzy.dsunext.databinding.DialogPermissionsBinding
import com.ditzzy.dsunext.databinding.RowFileEntryBinding
import com.ditzzy.dsunext.databinding.SheetFileActionsBinding
import com.ditzzy.dsunext.filemanager.api.*
import com.ditzzy.dsunext.ui.Event
import com.ditzzy.dsunext.ui.InsetsUtils
import com.ditzzy.dsunext.ui.ListRow
import com.ditzzy.dsunext.ui.SegmentedGroup
import com.ditzzy.dsunext.util.ModeBits
import com.ditzzy.dsunext.viewmodel.GsiFileManagerViewModel
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.ArrayList
import java.util.Locale
import java.util.function.Consumer

class GsiFileManagerActivity : AppCompatActivity() {
    private var binding: ActivityGsiFileManagerBinding? = null

    private var model: GsiFileManagerViewModel? = null

    private var adapter: FileAdapter? = null

    private var dialog: AlertDialog? = null

    private var sheet: BottomSheetDialog? = null

    private var workspace: String? = null

    private var pickFile: ActivityResultLauncher<Array<String>>? = null

    private var saveFile: ActivityResultLauncher<String>? = null

    /** The folder the breadcrumbs currently show, used to scroll to the top only when it changes. */
    private var renderedPath: String? = null

    // A folder that opens fast should not flash a spinner, so the loading state only appears after a short wait.
    private val showLoading: Runnable = Runnable {
        setLoadingVisible(true)
    }

    // Very slow listings get the full progress card, with its Cancel button, as before.
    private val showSlowProgress: Runnable = Runnable {
        if (((model != null) && model!!.isBusy() && model!!.isListing())) {
            binding!!.operation.setVisibility(View.VISIBLE)
        }
    }

    override fun onCreate(state: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(state)
        workspace = getIntent().getStringExtra(EXTRA_WORKSPACE)
        if ((workspace == null)) {
            finish()
            return
        }
        binding = ActivityGsiFileManagerBinding.inflate(getLayoutInflater())
        setContentView(binding!!.getRoot())
        InsetsUtils.padBottomAndSides(binding!!.content)
        InsetsUtils.padSides(binding!!.header)
        if ((getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)) {
            binding!!.folderTitle.setVisibility(View.GONE)
        }
        binding!!.toolbar.setSubtitle(workspace)
        model = ViewModelProvider(this).`get`(GsiFileManagerViewModel::class.java)
        adapter = FileAdapter()
        binding!!.files.setAdapter(adapter)
        binding!!.toolbar.setNavigationOnClickListener({ v ->
            up()
        })
        binding!!.cancel.setOnClickListener({ v ->
            model!!.cancel()
        })
        binding!!.create.setOnClickListener({ v ->
            creation()
        })
        binding!!.paste.setOnClickListener({ v ->
            model!!.paste()
        })
        binding!!.clearClipboard.setOnClickListener({ v ->
            model!!.clearClipboard()
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                up()
            }
        })
        pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument(), { uri ->
            if ((uri != null)) {
                prompt(R.string.fm_import, R.string.fm_name, displayName(uri), true, Consumer { value ->
                    model!!.importFile(uri, value)
                })
            }
        })
        saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), { uri ->
            if ((uri != null)) {
                model!!.export(uri)
            }
        })
        binding!!.toolbar.setOnMenuItemClickListener(callback9@ { item ->
            if ((!model!!.isBusy() && (item.getItemId() == R.id.fm_refresh))) {
                model!!.refresh()
            }
            return@callback9 true
        })
        model!!.getEntries().observe(this, { entries ->
            adapter!!.setNotifyOnChange(false)
            adapter!!.clear()
            adapter!!.addAll(entries)
            adapter!!.notifyDataSetChanged()
            var folders: Int = 0
            for (entry in entries) {
                if ((entry.kind == FileKind.DIRECTORY)) {
                    folders++
                }
            }
            val folderCount: String = getResources().getQuantityString(R.plurals.fm_folder_count, folders, folders)
            val files: Int = ((entries.size - (folders).toLong())).toInt()
            val fileCount: String = getResources().getQuantityString(R.plurals.fm_file_count, files, files)
            binding!!.summary.setText(getString(R.string.fm_folder_summary, folderCount, fileCount))
            renderControls()
        })
        model!!.getDirectory().observe(this, this::breadcrumbs)
        model!!.getLoading().observe(this, { loading ->
            renderLoading(loading)
        })
        model!!.getBusy().observe(this, { busy ->
            // Opening a folder is not a file operation: it must not push the list down with a progress card
            // or grey out the controls, taps are already ignored while busy.
            val working: Boolean = (busy && !model!!.isListing())
            binding!!.operation.setVisibility((if (working) View.VISIBLE else View.GONE))
            binding!!.progress.setText("")
            binding!!.paste.setEnabled(!busy)
            binding!!.clearClipboard.setEnabled(!busy)
            binding!!.toolbar.getMenu().findItem(R.id.fm_refresh).setEnabled(!working)
            run {
                var i: Int = 0
                while ((i < binding!!.breadcrumbs.getChildCount())) {
                    binding!!.breadcrumbs.getChildAt(i)!!.setEnabled(!working)
                    i++
                }
            }
            renderControls()
        })
        model!!.getClipboardLabel().observe(this, { label ->
            binding!!.clipboardName.setText(label)
            renderControls()
        })
        model!!.getProgress().observe(this, { progress ->
            if ((progress != null)) {
                binding!!.progress.setText(getString(R.string.fm_progress, (if ((progress.path == null)) "" else progress.path!!), android.text.format.Formatter.formatFileSize(this, progress.bytesProcessed), progress.changeCount))
            }
        })
        model!!.getOutcomes().observe(this, this::outcome)
        model!!.start(workspace!!)
    }

    protected override fun onResume() {
        super.onResume()
        if (((model != null) && !model!!.isBusy())) {
            model!!.refresh()
        }
    }

    protected override fun onDestroy() {
        if ((binding != null)) {
            binding!!.folderLoading.removeCallbacks(showLoading)
            binding!!.folderLoading.removeCallbacks(showSlowProgress)
        }
        if ((dialog != null)) {
            dialog!!.dismiss()
        }
        if ((sheet != null)) {
            sheet!!.dismiss()
        }
        super.onDestroy()
    }

    private fun renderControls() {
        val label: String? = model!!.getClipboardLabel().getValue()
        val clipboard: Boolean = ((label != null) && !label!!.isEmpty())
        binding!!.clipboard.setVisibility((if (clipboard) View.VISIBLE else View.GONE))
        val working: Boolean = (model!!.isBusy() && !model!!.isListing())
        binding!!.create.setVisibility((if ((!clipboard && !working)) View.VISIBLE else View.GONE))
        binding!!.empty.setVisibility((if ((adapter!!.isEmpty() && !model!!.isBusy())) View.VISIBLE else View.GONE))
    }

    private fun renderLoading(loading: Boolean) {
        val overlay: View = binding!!.folderLoading
        overlay.removeCallbacks(showLoading)
        overlay.removeCallbacks(showSlowProgress)
        if (loading) {
            overlay.postDelayed(showLoading, LOADING_DELAY_MS)
            overlay.postDelayed(showSlowProgress, SLOW_PROGRESS_DELAY_MS)
        } else {
            setLoadingVisible(false)
        }
    }

    private fun setLoadingVisible(visible: Boolean) {
        val overlay: View = binding!!.folderLoading
        val list: View = binding!!.files
        overlay.animate().cancel()
        list.animate().cancel()
        if (visible) {
            overlay.setVisibility(View.VISIBLE)
            overlay.animate().alpha(1f).setDuration(FADE_MS).start()
            list.animate().alpha(DIMMED_LIST_ALPHA).setDuration(FADE_MS).start()
        } else {
            list.animate().alpha(1f).setDuration(FADE_MS).start()
            if ((overlay.getVisibility() == View.VISIBLE)) {
                overlay.animate().alpha(0f).setDuration(FADE_MS).withEndAction({
                    overlay.setVisibility(View.GONE)
                }).start()
            }
        }
    }

    private fun breadcrumbs(path: String) {
        val changed: Boolean = !path.equals(renderedPath)
        renderedPath = path
        binding!!.folderTitle.setText((if (path.isEmpty()) getString(R.string.fm_image_root) else path.substring((path.lastIndexOf('/') + 1))))
        binding!!.breadcrumbs.removeAllViews()
        crumb(getString(R.string.fm_image_root), "", path.isEmpty())
        var relative: String = ""
        if (!path.isEmpty()) {
            for (segment in java.util.regex.Pattern.compile("/").split(path)) {
                val separator: ImageView = ImageView(this)
                separator.setImageResource(R.drawable.ic_chevron_right)
                separator.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(separator, com.google.android.material.R.attr.colorOnSurfaceVariant)))
                separator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
                binding!!.breadcrumbs.addView(separator, LinearLayout.LayoutParams(dp(16), dp(16)))
                relative = (if (relative.isEmpty()) segment else ((relative + "/") + segment))
                crumb(segment, relative, relative.equals(path))
            }
        }
        binding!!.breadcrumbScroll.post({
            binding!!.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT)
        })
        if (changed) {
            // Refreshing the same folder, for example after editing a file, keeps the scroll position.
            binding!!.files.setSelection(0)
        }
    }

    private fun crumb(title: String, path: String, current: Boolean) {
        val button: MaterialButton = (getLayoutInflater().inflate(R.layout.button_file_breadcrumb, binding!!.breadcrumbs, false) as MaterialButton)
        button.setText(title)
        button.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(button, (if (current) com.google.android.material.R.attr.colorSecondaryContainer else com.google.android.material.R.attr.colorSurface))))
        button.setTextColor(MaterialColors.getColor(button, (if (current) com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorOnSurfaceVariant)))
        button.setContentDescription(getString(R.string.fm_open_folder, title))
        androidx.appcompat.widget.TooltipCompat.setTooltipText(button, title)
        button.setEnabled(!model!!.isBusy())
        button.setOnClickListener({ v ->
            if ((!model!!.isBusy() && !current)) {
                model!!.navigate(path)
            }
        })
        binding!!.breadcrumbs.addView(button)
    }

    private fun up() {
        if (model!!.isBusy()) {
            return
        }
        val path: String = model!!.path()
        if (path.isEmpty()) {
            finish()
        } else {
            model!!.navigate((if (path.contains("/")) path.substring(0, path.lastIndexOf('/')) else ""))
        }
    }

    private fun `open`(entry: FileEntry) {
        if (model!!.isBusy()) {
            return
        }
        if ((entry.kind == FileKind.DIRECTORY)) {
            model!!.navigate(entry.path)
        } else {
            actions(entry)
        }
    }

    private fun showSheet(content: SheetFileActionsBinding) {
        if ((sheet != null)) {
            sheet!!.dismiss()
        }
        sheet = BottomSheetDialog(this)
        sheet!!.setContentView(content.getRoot())
        sheet!!.getBehavior().setMaxHeight(((getResources().getDisplayMetrics().heightPixels * 0.85f)).toInt())
        sheet!!.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED)
        sheet!!.show()
    }

    private fun sheetAction(group: LinearLayout, icon: Int, label: Int, action: Runnable) {
        ListRow.create(group).icon(icon).title(label).onClick({ v ->
            if ((sheet != null)) {
                sheet!!.dismiss()
            }
            if (!model!!.isBusy()) {
                action.run()
            }
        })
    }

    private fun creation() {
        if (model!!.isBusy()) {
            return
        }
        val content: SheetFileActionsBinding = SheetFileActionsBinding.inflate(getLayoutInflater())
        content.title.setText(R.string.fm_create)
        content.subtitle.setText(getString(R.string.fm_create_in, (if (model!!.path().isEmpty()) getString(R.string.fm_image_root) else model!!.path())))
        sheetAction(content.actions, R.drawable.ic_description, R.string.fm_new_file, Runnable {
            prompt(R.string.fm_new_file, R.string.fm_name, "", true, Consumer { name ->
                model!!.execute(FileRequest.CreateFile(model!!.child(name)))
            })
        })
        sheetAction(content.actions, R.drawable.ic_folder_open, R.string.fm_new_folder, Runnable {
            prompt(R.string.fm_new_folder, R.string.fm_name, "", true, Consumer { name ->
                model!!.execute(FileRequest.CreateDirectory(model!!.child(name)))
            })
        })
        sheetAction(content.actions, R.drawable.ic_download, R.string.fm_import, Runnable {
            pickFile!!.launch(arrayOf("*/*"))
        })
        sheetAction(content.actions, R.drawable.ic_link, R.string.fm_symlink, Runnable {
            prompt(R.string.fm_symlink, R.string.fm_name, "", true, Consumer { name ->
                prompt(R.string.fm_symlink, R.string.fm_target, "", false, Consumer { target ->
                    model!!.execute(FileRequest.CreateSymlink(model!!.child(name), target))
                })
            })
        })
        SegmentedGroup.apply(content.actions)
        showSheet(content)
    }

    private fun actions(entry: FileEntry) {
        if (model!!.isBusy()) {
            return
        }
        val content: SheetFileActionsBinding = SheetFileActionsBinding.inflate(getLayoutInflater())
        content.title.setText(entry.name)
        content.subtitle.setText(((kind(entry) + " · ") + mode(entry)))
        if ((entry.kind == FileKind.FILE)) {
            sheetAction(content.actions, R.drawable.ic_edit, R.string.fm_edit, Runnable {
                if ((entry.size > ((256 * 1024)).toLong())) {
                    message(getString(R.string.fm_text_limit))
                } else {
                    startActivity(Intent(this, GsiTextEditorActivity::class.java).putExtra(EXTRA_WORKSPACE, workspace).putExtra(GsiTextEditorActivity.EXTRA_PATH, entry.path))
                }
            })
        }
        sheetAction(content.actions, R.drawable.ic_content_copy, R.string.fm_copy, Runnable {
            model!!.copy(entry, false)
        })
        sheetAction(content.actions, R.drawable.ic_content_cut, R.string.fm_cut, Runnable {
            model!!.copy(entry, true)
        })
        sheetAction(content.actions, R.drawable.ic_edit, R.string.fm_rename, Runnable {
            prompt(R.string.fm_rename, R.string.fm_name, entry.name, true, Consumer { name ->
                model!!.execute(FileRequest.Rename(entry.path, name))
            })
        })
        if ((entry.kind == FileKind.FILE)) {
            sheetAction(content.actions, R.drawable.ic_download, R.string.fm_export, Runnable {
                model!!.setExportPath(entry.path)
                saveFile!!.launch(entry.name)
            })
        }
        if ((entry.kind != FileKind.OTHER)) {
            sheetAction(content.actions, R.drawable.ic_settings, R.string.fm_permissions, Runnable {
                permissions(entry)
            })
        }
        sheetAction(content.actions, R.drawable.ic_info, R.string.fm_properties, Runnable {
            properties(entry)
        })
        val delete: ListRow = ListRow.create(content.actions).icon(R.drawable.ic_delete).title(R.string.gsi_delete).onClick(callback33@ { v ->
            sheet!!.dismiss()
            if (model!!.isBusy()) {
                return@callback33
            }
            dialog = MaterialAlertDialogBuilder(this).setIcon(R.drawable.ic_delete).setTitle(R.string.gsi_delete).setMessage(getString(R.string.fm_delete_message, entry.name)).setPositiveButton(R.string.gsi_delete, { d, which ->
                model!!.execute(FileRequest.Delete(entry.path, true))
            }).setNegativeButton(R.string.cancel, null).show()
        })
        delete.root().setContentDescription(getString(R.string.gsi_delete_title, entry.name))
        SegmentedGroup.apply(content.actions)
        showSheet(content)
    }

    private fun add(titles: MutableList<String>, actions: MutableList<Runnable>, title: Int, action: Runnable) {
        titles.add(getString(title))
        actions.add(action)
    }

    private fun properties(entry: FileEntry) {
        val content: SheetFileActionsBinding = SheetFileActionsBinding.inflate(getLayoutInflater())
        content.title.setText(entry.name)
        content.subtitle.setText(R.string.fm_properties)
        ListRow.create(content.actions).icon(R.drawable.ic_folder_open).title(R.string.gsi_info_path).supporting(("/" + entry.path)).notClickable()
        ListRow.create(content.actions).icon(R.drawable.ic_file).title(kind(entry)).supporting(android.text.format.Formatter.formatFileSize(this, entry.size)).notClickable()
        ListRow.create(content.actions).icon(R.drawable.ic_settings).title(R.string.fm_permissions).supporting(mode(entry)).notClickable()
        if ((entry.symlinkTarget != null)) {
            ListRow.create(content.actions).icon(R.drawable.ic_link).title(R.string.fm_target).supporting(entry.symlinkTarget!!).notClickable()
        }
        SegmentedGroup.apply(content.actions)
        showSheet(content)
    }

    private fun prompt(title: Int, hint: Int, initial: String, fileName: Boolean, action: Consumer<String>) {
        val content: DialogFileInputBinding = DialogFileInputBinding.inflate(getLayoutInflater())
        content.inputLayout.setHint(getString(hint))
        content.inputEdit.setText(initial)
        dialog = MaterialAlertDialogBuilder(this).setTitle(title).setView(content.getRoot()).setPositiveButton(R.string.ok, null).setNegativeButton(R.string.cancel, null).create()
        dialog!!.show()
        val current: AlertDialog = dialog!!
        current.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(callback35@ { v ->
            val value: String = content.inputEdit.getText()!!.toString()
            if ((value.isEmpty() || (fileName && !validName(value)))) {
                content.inputLayout.setError(getString(R.string.fm_invalid_name))
                return@callback35
            }
            current.dismiss()
            action.accept(value)
        })
        content.inputEdit.requestFocus()
        content.inputEdit.setSelection(initial.length)
    }

    private fun permissions(entry: FileEntry) {
        val content: DialogPermissionsBinding = DialogPermissionsBinding.inflate(getLayoutInflater())
        var mode: IntArray = intArrayOf((if ((entry.mode == null)) 420 else (entry.mode!! and ModeBits.MAX)))
        var syncing: BooleanArray = booleanArrayOf(false)
        val directory: Boolean = (entry.kind == FileKind.DIRECTORY)
        content.folderOptions.setVisibility((if (directory) View.VISIBLE else View.GONE))
        val grid: Array<Array<MaterialCheckBox>> = arrayOf(arrayOf(content.ownerRead, content.ownerWrite, content.ownerExecute), arrayOf(content.groupRead, content.groupWrite, content.groupExecute), arrayOf(content.othersRead, content.othersWrite, content.othersExecute))
        val shifts: IntArray = intArrayOf(ModeBits.OWNER, ModeBits.GROUP, ModeBits.OTHERS)
        val bits: IntArray = intArrayOf(ModeBits.READ, ModeBits.WRITE, ModeBits.EXECUTE)
        val specials: Array<MaterialCheckBox> = arrayOf(content.specialSetuid, content.specialSetgid, content.specialSticky)
        val flags: IntArray = intArrayOf(ModeBits.SETUID, ModeBits.SETGID, ModeBits.STICKY)
        val render: Runnable = Runnable {
            syncing[0] = true
            run {
                var row: Int = 0
                while ((row < 3)) {
                    run {
                        var col: Int = 0
                        while ((col < 3)) {
                            grid[row][col].setChecked(ModeBits.has(mode[0], shifts[row], bits[col]))
                            col++
                        }
                    }
                    row++
                }
            }
            run {
                var i: Int = 0
                while ((i < 3)) {
                    specials[i].setChecked(((mode[0] and flags[i]) != 0))
                    i++
                }
            }
            content.modeValue.setText(ModeBits.octal(mode[0]))
            content.modeSymbolic.setText(ModeBits.symbolic(mode[0]))
            syncing[0] = false
        }
        run {
            var row: Int = 0
            while ((row < 3)) {
                run {
                    var col: Int = 0
                    while ((col < 3)) {
                        val shift: Int = shifts[row]
                        val bit: Int = bits[col]
                        grid[row][col].setOnCheckedChangeListener(callback38@ { button, checked ->
                            if (syncing[0]) {
                                return@callback38
                            }
                            mode[0] = ModeBits.with(mode[0], shift, bit, checked)
                            render.run()
                            content.inputEdit.setText(ModeBits.octal(mode[0]))
                            content.inputLayout.setError(null)
                        })
                        col++
                    }
                }
                row++
            }
        }
        run {
            var i: Int = 0
            while ((i < 3)) {
                val flag: Int = flags[i]
                specials[i].setOnCheckedChangeListener(callback39@ { button, checked ->
                    if (syncing[0]) {
                        return@callback39
                    }
                    mode[0] = ModeBits.withSpecial(mode[0], flag, checked)
                    render.run()
                    content.inputEdit.setText(ModeBits.octal(mode[0]))
                    content.inputLayout.setError(null)
                })
                i++
            }
        }
        content.inputEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(text: CharSequence, start: Int, before: Int, count: Int) {

            }

            override fun afterTextChanged(text: android.text.Editable) {
                if (syncing[0]) {
                    return
                }
                val parsed: Int = ModeBits.parse(text)
                if ((parsed < 0)) {
                    content.inputLayout.setError((if ((text.length >= 3)) getString(R.string.fm_perm_invalid) else null))
                    return
                }
                content.inputLayout.setError(null)
                mode[0] = parsed
                render.run()
            }
        })
        render.run()
        content.inputEdit.setText(ModeBits.octal(mode[0]))
        dialog = MaterialAlertDialogBuilder(this).setIcon(R.drawable.ic_settings).setTitle(R.string.fm_permissions).setView(content.getRoot()).setPositiveButton(R.string.ok, null).setNegativeButton(R.string.cancel, null).create()
        dialog!!.show()
        val current: AlertDialog = dialog!!
        current.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(callback40@ { v ->
            val value: Int = ModeBits.parse(content.inputEdit.getText())
            if ((value < 0)) {
                content.inputLayout.setError(getString(R.string.fm_perm_invalid))
                return@callback40
            }
            val files: Boolean = (directory && content.applyFiles.isChecked())
            val folders: Boolean = (directory && content.applyFolders.isChecked())
            current.dismiss()
            model!!.execute(FileRequest.SetPermissions(entry.path, value, (files || folders), files, folders))
        })
    }

    private fun dp(value: Int): Int {
        return Math.round((value * getResources().getDisplayMetrics().density))
    }

    private fun outcome(event: Event<GsiFileManagerViewModel.Outcome>?) {
        val outcome: GsiFileManagerViewModel.Outcome? = (if ((event == null)) null else event!!.getContentIfNotHandled())
        if ((outcome == null)) {
            return
        }
        if ((outcome!!.error != null)) {
            message(outcome!!.error!!)
            return
        }
        val result: FileResult = outcome!!.result!!
        if ((result is FileResult.Failure)) {
            val error: FileError? = (result as FileResult.Failure).error
            if (((error!!.code == FileErrorCode.ALREADY_EXISTS) && canRetry(outcome!!.request))) {
                conflict(outcome!!.request)
            } else {
                message(describe(error!!))
            }
        } else if ((result is FileResult.Partial)) {
            message(getString(R.string.fm_partial, result.report.changeCount, result.report.skippedCount, describe((result as FileResult.Partial).error)))
        } else if ((result is FileResult.Cancelled)) {
            message(getString(R.string.fm_cancelled, result.report.changeCount))
        } else {
            Snackbar.make(binding!!.getRoot(), (if ((result.report.skippedCount > 0L)) getString(R.string.fm_skipped, result.report.skippedCount) else getString(R.string.fm_done)), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun canRetry(request: FileRequest): Boolean {
        return ((((((request is FileRequest.Copy) || (request is FileRequest.Move)) || (request is FileRequest.Rename)) || (request is FileRequest.CreateFile)) || (request is FileRequest.CreateDirectory)) || (request is FileRequest.CreateSymlink))
    }

    private fun conflict(request: FileRequest) {
        val labels: ArrayList<String> = ArrayList<String>()
        val choices: ArrayList<Runnable> = ArrayList<Runnable>()
        add(labels, choices, R.string.fm_replace, Runnable {
            retry(request, ConflictPolicy.REPLACE, DirectoryPolicy.FAIL)
        })
        add(labels, choices, R.string.fm_keep_both, Runnable {
            retry(request, ConflictPolicy.KEEP_BOTH, DirectoryPolicy.FAIL)
        })
        add(labels, choices, R.string.fm_skip, Runnable {
            retry(request, ConflictPolicy.SKIP, DirectoryPolicy.FAIL)
        })
        if (((request is FileRequest.Copy) || (request is FileRequest.Move))) {
            add(labels, choices, R.string.fm_merge, Runnable {
                mergeConflict(request)
            })
        }
        dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.fm_conflict).setItems(labels.toTypedArray(), { d, which ->
            choices.`get`(which).run()
        }).setNegativeButton(R.string.cancel, null).show()
    }

    private fun mergeConflict(request: FileRequest) {
        val labels: Array<String> = arrayOf(getString(R.string.fm_fail_on_conflict), getString(R.string.fm_replace), getString(R.string.fm_keep_both), getString(R.string.fm_skip))
        val policies: Array<ConflictPolicy> = arrayOf(ConflictPolicy.FAIL, ConflictPolicy.REPLACE, ConflictPolicy.KEEP_BOTH, ConflictPolicy.SKIP)
        dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.fm_child_conflicts).setItems(labels, { d, which ->
            retry(request, policies[which], DirectoryPolicy.MERGE)
        }).setNegativeButton(R.string.cancel, null).show()
    }

    private fun retry(request: FileRequest, policy: ConflictPolicy, directories: DirectoryPolicy) {
        if ((request is FileRequest.Copy)) {
            val r: FileRequest.Copy = (request as FileRequest.Copy)
            model!!.execute(FileRequest.Copy(r.source, r.destination, policy, directories, r.createParents, r.preserveAttributes))
        } else if ((request is FileRequest.Move)) {
            val r: FileRequest.Move = (request as FileRequest.Move)
            model!!.execute(FileRequest.Move(r.source, r.destination, policy, directories, r.createParents))
        } else if ((request is FileRequest.Rename)) {
            val r: FileRequest.Rename = (request as FileRequest.Rename)
            model!!.execute(FileRequest.Rename(r.path, r.newName, policy))
        } else if ((request is FileRequest.CreateFile)) {
            val r: FileRequest.CreateFile = (request as FileRequest.CreateFile)
            model!!.execute(FileRequest.CreateFile(r.path, r.content, r.createParents, policy))
        } else if ((request is FileRequest.CreateDirectory)) {
            val r: FileRequest.CreateDirectory = (request as FileRequest.CreateDirectory)
            model!!.execute(FileRequest.CreateDirectory(r.path, r.createParents, policy))
        } else if ((request is FileRequest.CreateSymlink)) {
            val r: FileRequest.CreateSymlink = (request as FileRequest.CreateSymlink)
            model!!.execute(FileRequest.CreateSymlink(r.path, r.target, r.createParents, policy))
        }
    }

    private fun describe(error: FileError): String {
        when (error.code) {
            FileErrorCode.ALREADY_EXISTS -> {
                return getString(R.string.fm_conflict)
            }
            FileErrorCode.ACCESS_DENIED -> {
                return getString(R.string.fm_access_denied)
            }
            FileErrorCode.NO_SPACE -> {
                return getString(R.string.fm_no_space)
            }
            FileErrorCode.NOT_FOUND -> {
                return getString(R.string.fm_not_found)
            }
            FileErrorCode.SYMLINK_NOT_ALLOWED -> {
                return getString(R.string.fm_link_blocked)
            }
            FileErrorCode.SOURCE_CHANGED -> {
                return getString(R.string.fm_changed)
            }
            FileErrorCode.UNSUPPORTED -> {
                return getString(R.string.fm_unsupported)
            }
            FileErrorCode.TYPE_MISMATCH -> {
                return getString(R.string.fm_type_mismatch)
            }
            FileErrorCode.DIRECTORY_NOT_EMPTY -> {
                return getString(R.string.fm_not_empty)
            }
            FileErrorCode.INVALID_PATH, FileErrorCode.INVALID_MODE, FileErrorCode.OUTSIDE_WORKSPACE, FileErrorCode.SAME_FILE, FileErrorCode.DESCENDANT_TARGET -> {
                return getString(R.string.fm_bad_path)
            }
            else -> {
                return error.message!!
            }
        }
    }

    private fun message(message: String) {
        dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.fm_title).setMessage(message).setPositiveButton(R.string.close, null).show()
    }

    private fun displayName(uri: Uri): String {
        try {
            getContentResolver().query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor ->
                if ((((cursor != null) && cursor.moveToFirst()) && (cursor.getString(0) != null))) {
                    return cursor.getString(0)
                }
            }
        } catch (ignored: RuntimeException) {

        }
        // Some document providers do not expose a display name.
        return "file"
    }

    private fun kind(entry: FileEntry): String {
        when (entry.kind) {
            FileKind.DIRECTORY -> {
                return getString(R.string.fm_directory)
            }
            FileKind.SYMLINK -> {
                return getString(R.string.fm_link)
            }
            FileKind.OTHER -> {
                return getString(R.string.fm_special)
            }
            else -> {
                return getString(R.string.fm_file)
            }
        }
    }

    private fun mode(entry: FileEntry): String {
        return (if ((entry.mode == null)) "—" else java.lang.String.format(Locale.ROOT, "%04o", entry.mode))
    }

    private inner class FileAdapter : ArrayAdapter<FileEntry> {
        constructor() : super(this@GsiFileManagerActivity, 0, ArrayList()) {

        }

        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            var convert = convert
            var row: RowFileEntryBinding? = null
            if ((convert == null)) {
                row = RowFileEntryBinding.inflate(getLayoutInflater(), parent, false)
                convert = row!!.getRoot()
                convert!!.setTag(row)
            } else {
                row = (convert!!.getTag() as RowFileEntryBinding)
            }
            row!!.getRoot().updateAppearance(position, getCount())
            val entry: FileEntry = getItem(position)!!
            row!!.itemTitle.setText(entry.name)
            row!!.itemSupporting.setText((if ((entry.kind == FileKind.FILE)) (((kind(entry)).toString() + " · ") + android.text.format.Formatter.formatFileSize(this@GsiFileManagerActivity, entry.size)) else (if ((entry.kind == FileKind.SYMLINK)) entry.symlinkTarget else kind(entry))!!))
            val folder: Boolean = (entry.kind == FileKind.DIRECTORY)
            val link: Boolean = (entry.kind == FileKind.SYMLINK)
            row!!.itemIcon.setImageResource((if (folder) R.drawable.ic_folder_open else (if (link) R.drawable.ic_link else R.drawable.ic_description)))
            row!!.iconContainer.setCardBackgroundColor(MaterialColors.getColor(row!!.iconContainer, (if (folder) com.google.android.material.R.attr.colorPrimaryContainer else (if (link) com.google.android.material.R.attr.colorTertiaryContainer else com.google.android.material.R.attr.colorSecondaryContainer))))
            row!!.itemIcon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(row!!.itemIcon, (if (folder) com.google.android.material.R.attr.colorOnPrimaryContainer else (if (link) com.google.android.material.R.attr.colorOnTertiaryContainer else com.google.android.material.R.attr.colorOnSecondaryContainer)))))
            row!!.itemCard.setOnClickListener({ v ->
                `open`(entry)
            })
            row!!.itemCard.setOnLongClickListener(callback48@ { v ->
                actions(entry)
                return@callback48 true
            })
            row!!.itemMore.setOnClickListener({ v ->
                actions(entry)
            })
            return convert!!
        }
    }

    companion object {
        const val EXTRA_WORKSPACE: String = "workspace"

        private const val LOADING_DELAY_MS: Long = 150L

        private const val SLOW_PROGRESS_DELAY_MS: Long = 1500L

        private const val FADE_MS: Long = 120L

        private const val DIMMED_LIST_ALPHA: Float = 0.35f

        private fun validName(name: String): Boolean {
            return (((!name.equals(".") && !name.equals("..")) && !name.contains("/")) && name.codePoints().noneMatch({ c ->
                (java.lang.Character.isWhitespace(c) || java.lang.Character.isISOControl(c))
            }))
        }
    }
}
