package com.ditzzy.dsunext.activity

import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.databinding.ActivityGsiTextEditorBinding
import com.ditzzy.dsunext.ui.editor.EditorDocument
import com.ditzzy.dsunext.viewmodel.GsiTextEditorViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class GsiTextEditorActivity : AppCompatActivity() {
    private var binding: ActivityGsiTextEditorBinding? = null

    private var model: GsiTextEditorViewModel? = null

    private var rendering: Boolean = false

    private var changingText: Boolean = false

    private var closeAfterSave: Boolean = false

    private var initialized: Boolean = false

    private var restoreScroll: Int = 0

    private var discard: AlertDialog? = null

    private val recountSearch: Runnable = Runnable(this::updateMatches)

    protected override fun onCreate(state: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(state)
        val workspace: String? = getIntent().getStringExtra(GsiFileManagerActivity.EXTRA_WORKSPACE)
        val path: String? = getIntent().getStringExtra(EXTRA_PATH)
        if (((workspace == null) || (path == null))) {
            finish()
            return
        }
        binding = ActivityGsiTextEditorBinding.inflate(getLayoutInflater())
        setContentView(binding!!.getRoot())
        ViewCompat.setOnApplyWindowInsetsListener(binding!!.getRoot(), callback1@ { view, insets ->
            val bars: Insets = insets.getInsets((WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()))
            val keyboard: Int = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(bars.left, 0, bars.right, Math.max(bars.bottom, keyboard))
            val typing: Boolean = insets.isVisible(WindowInsetsCompat.Type.ime())
            binding!!.editorHeader.setVisibility((if (typing) View.GONE else View.VISIBLE))
            binding!!.toolbar.getMenu().findItem(R.id.editor_save).setVisible(typing)
            return@callback1 insets
        })
        ViewCompat.requestApplyInsets(binding!!.getRoot())
        val slash: Int = path!!.lastIndexOf('/')
        binding!!.toolbar.setTitle(path!!.substring((slash + 1)))
        binding!!.toolbar.setSubtitle((if ((slash < 0)) getString(R.string.fm_image_root) else path!!.substring(0, slash)))
        model = ViewModelProvider(this).`get`(GsiTextEditorViewModel::class.java)
        binding!!.gutter.attach(binding!!.editor)
        binding!!.editor.setOnSelectionChanged({
            val document: EditorDocument? = model!!.document()
            if ((((document != null) && !rendering) && !changingText)) {
                document!!.selection(binding!!.editor.getSelectionStart(), binding!!.editor.getSelectionEnd())
            }
            updateCursor()
        })
        binding!!.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence, start: Int, count: Int, after: Int) {
                if ((!rendering && (model!!.document() != null))) {
                    model!!.document()!!.selection(binding!!.editor.getSelectionStart(), binding!!.editor.getSelectionEnd())
                    changingText = true
                }
            }

            override fun onTextChanged(text: CharSequence, start: Int, before: Int, count: Int) {

            }

            override fun afterTextChanged(text: Editable) {
                if ((rendering || (model!!.document() == null))) {
                    changingText = false
                    return
                }
                model!!.document()!!.edit(text.toString(), binding!!.editor.getSelectionStart(), binding!!.editor.getSelectionEnd(), SystemClock.uptimeMillis())
                changingText = false
                model!!.changed()
                binding!!.search.removeCallbacks(recountSearch)
                binding!!.search.postDelayed(recountSearch, 200)
            }
        })
        binding!!.save.setOnClickListener({ v ->
            model!!.save()
        })
        binding!!.retry.setOnClickListener({ v ->
            model!!.load()
        })
        binding!!.toolbar.setNavigationOnClickListener({ v ->
            leave()
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if ((binding!!.searchBar.getVisibility() == View.VISIBLE)) {
                    toggleSearch()
                } else {
                    leave()
                }
            }
        })
        binding!!.toolbar.setOnMenuItemClickListener(callback6@ { item ->
            val document: EditorDocument? = model!!.document()
            if (((document == null) || model!!.saving())) {
                return@callback6 true
            }
            val id: Int = item.getItemId()
            if ((id == R.id.editor_undo)) {
                document!!.undo()
                model!!.changed()
            } else if ((id == R.id.editor_redo)) {
                document!!.redo()
                model!!.changed()
            } else if ((id == R.id.editor_find)) {
                toggleSearch()
            } else if ((id == R.id.editor_save)) {
                model!!.save()
            }
            return@callback6 true
        })
        binding!!.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {

            }

            override fun afterTextChanged(text: Editable) {
                updateMatches()
            }
        })
        binding!!.findNext.setOnClickListener({ v ->
            find(false)
        })
        binding!!.findPrevious.setOnClickListener({ v ->
            find(true)
        })
        binding!!.search.setOnEditorActionListener(callback9@ { v, action, event ->
            if ((action == EditorInfo.IME_ACTION_SEARCH)) {
                find(false)
                return@callback9 true
            }
            return@callback9 false
        })
        if ((state != null)) {
            closeAfterSave = state!!.getBoolean("close_after_save")
            restoreScroll = state!!.getInt("editor_scroll")
            if (state!!.getBoolean("search_open")) {
                binding!!.searchBar.setVisibility(View.VISIBLE)
            }
        }
        model!!.state().observe(this, { revision ->
            render()
        })
        model!!.errors().observe(this, callback11@ { event ->
            val error: String? = (if ((event == null)) null else event.getContentIfNotHandled())
            if ((error == null)) {
                return@callback11
            }
            closeAfterSave = false
            if ((model!!.document() == null)) {
                binding!!.loadMessage.setText(error)
            } else {
                Snackbar.make(binding!!.getRoot(), error, Snackbar.LENGTH_LONG).show()
            }
        })
        model!!.saved().observe(this, callback12@ { event ->
            val saved: Boolean? = (if ((event == null)) null else event.getContentIfNotHandled())
            if ((saved == null)) {
                return@callback12
            }
            if ((closeAfterSave && !model!!.document()!!.dirty())) {
                finish()
            } else {
                Snackbar.make(binding!!.getRoot(), R.string.fm_editor_saved, Snackbar.LENGTH_SHORT).show()
            }
        })
        model!!.start(workspace!!, path!!)
    }

    private fun render() {
        val document: EditorDocument? = model!!.document()
        val ready: Boolean = (document != null)
        binding!!.editorContent.setVisibility((if (ready) View.VISIBLE else View.INVISIBLE))
        binding!!.loadState.setVisibility((if (ready) View.GONE else View.VISIBLE))
        binding!!.loading.setVisibility((if (model!!.loading()) View.VISIBLE else View.GONE))
        binding!!.retry.setVisibility((if ((!ready && !model!!.loading())) View.VISIBLE else View.GONE))
        if (model!!.loading()) {
            binding!!.loadMessage.setText(R.string.fm_editor_loading)
        }
        binding!!.editor.setEnabled((ready && !model!!.saving()))
        binding!!.save.setEnabled(((ready && document!!.dirty()) && !model!!.saving()))
        binding!!.toolbar.getMenu().findItem(R.id.editor_save).setEnabled(((ready && document!!.dirty()) && !model!!.saving()))
        binding!!.save.setText((if (model!!.saving()) R.string.fm_editor_saving else R.string.fm_editor_save))
        binding!!.saveStatus.setText((if (model!!.saving()) R.string.fm_editor_saving else (if ((ready && document!!.dirty())) R.string.fm_editor_unsaved else R.string.fm_editor_saved)))
        binding!!.toolbar.getMenu().findItem(R.id.editor_undo).setEnabled(((ready && document!!.canUndo()) && !model!!.saving()))
        binding!!.toolbar.getMenu().findItem(R.id.editor_redo).setEnabled(((ready && document!!.canRedo()) && !model!!.saving()))
        binding!!.toolbar.getMenu().findItem(R.id.editor_find).setEnabled((ready && !model!!.saving()))
        if ((ready && (!initialized || !binding!!.editor.getText()!!.toString().equals(document!!.text())))) {
            rendering = true
            binding!!.editor.setText(document!!.text())
            binding!!.editor.setSelection(document!!.selectionStart(), document!!.selectionEnd())
            rendering = false
            if (!initialized) {
                initialized = true
                binding!!.editor.post({
                    binding!!.editor.scrollTo(0, restoreScroll)
                })
            }
            updateMatches()
        }
        updateCursor()
    }

    private fun updateCursor() {
        if ((binding!!.editor.getText() == null)) {
            return
        }
        val offset: Int = Math.max(0, binding!!.editor.getSelectionStart())
        binding!!.cursor.setText(getString(R.string.fm_editor_cursor, (binding!!.gutter.lineAt(offset) + 1), (binding!!.gutter.columnAt(offset) + 1)))
        binding!!.gutter.invalidate()
    }

    private fun toggleSearch() {
        val opening: Boolean = (binding!!.searchBar.getVisibility() != View.VISIBLE)
        binding!!.searchBar.setVisibility((if (opening) View.VISIBLE else View.GONE))
        if (opening) {
            binding!!.search.requestFocus()
            binding!!.search.post({
                if ((ViewCompat.getWindowInsetsController(binding!!.search) != null)) {
                    ViewCompat.getWindowInsetsController(binding!!.search)?.show(WindowInsetsCompat.Type.ime())
                }
            })
            updateMatches()
        } else {
            binding!!.search.setText("")
            binding!!.matches.setText("")
            binding!!.editor.requestFocus()
        }
    }

    private fun find(backwards: Boolean) {
        val query: String = binding!!.search.getText()!!.toString()
        if ((query.isEmpty() || (model!!.document() == null))) {
            return
        }
        val text: String = model!!.document()!!.text()
        var found: Int = 0
        if (backwards) {
            val before: Int = (binding!!.editor.getSelectionStart() - 1)
            found = text.lastIndexOf(query, before)
            if ((found < 0)) {
                found = text.lastIndexOf(query)
            }
        } else {
            found = text.indexOf(query, Math.max(0, binding!!.editor.getSelectionEnd()))
            if ((found < 0)) {
                found = text.indexOf(query)
            }
        }
        if ((found >= 0)) {
            binding!!.editor.requestFocus()
            binding!!.editor.setSelection(found, (found + query.length))
            binding!!.editor.post({
                binding!!.editor.bringPointIntoView(binding!!.editor.getSelectionStart())
            })
        }
        updateMatches()
    }

    private fun updateMatches() {
        val query: String = binding!!.search.getText()!!.toString()
        if (((query.isEmpty() || (model!!.document() == null)) || (binding!!.searchBar.getVisibility() != View.VISIBLE))) {
            binding!!.matches.setText("")
            binding!!.findPrevious.setEnabled(false)
            binding!!.findNext.setEnabled(false)
            return
        }
        val text: String = model!!.document()!!.text()
        var count: Int = 0
        run {
            var offset: Int = text.indexOf(query)
            while ((offset >= 0)) {
                count++
                (text.indexOf(query, (offset + query.length))).also { offset = it }
            }
        }
        binding!!.matches.setText(getResources().getQuantityString(R.plurals.fm_editor_matches, count, count))
        binding!!.findPrevious.setEnabled((count > 0))
        binding!!.findNext.setEnabled((count > 0))
    }

    private fun leave() {
        if (model!!.saving()) {
            return
        }
        val document: EditorDocument? = model!!.document()
        if (((document == null) || !document!!.dirty())) {
            finish()
            return
        }
        discard = MaterialAlertDialogBuilder(this).setTitle(R.string.fm_editor_leave_title).setMessage(R.string.fm_editor_leave_message).setPositiveButton(R.string.fm_editor_save, { d, which ->
            closeAfterSave = true
            model!!.save()
        }).setNegativeButton(R.string.discard, { d, which ->
            finish()
        }).setNeutralButton(R.string.cancel, null).show()
    }

    protected override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("close_after_save", closeAfterSave)
        state.putBoolean("search_open", (binding!!.searchBar.getVisibility() == View.VISIBLE))
        state.putInt("editor_scroll", binding!!.editor.getScrollY())
        super.onSaveInstanceState(state)
    }

    protected override fun onDestroy() {
        if ((binding != null)) {
            binding!!.search.removeCallbacks(recountSearch)
        }
        if ((discard != null)) {
            discard!!.dismiss()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH: String = "path"
    }
}
