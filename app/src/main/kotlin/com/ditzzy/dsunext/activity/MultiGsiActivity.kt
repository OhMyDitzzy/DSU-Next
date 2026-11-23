package com.ditzzy.dsunext.activity

import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.databinding.ActivityMultiGsiBinding
import com.ditzzy.dsunext.databinding.DialogFileInputBinding
import com.ditzzy.dsunext.model.GsiSlotInfo
import com.ditzzy.dsunext.ui.InsetsUtils
import com.ditzzy.dsunext.ui.ListRow
import com.ditzzy.dsunext.ui.SegmentedGroup
import com.ditzzy.dsunext.ui.UiMessage
import com.ditzzy.dsunext.util.GsiNames
import com.ditzzy.dsunext.viewmodel.MultiGsiState
import com.ditzzy.dsunext.viewmodel.MultiGsiViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.ArrayList
import java.util.Date

/** Shows every installed GSI and lets the user switch to one, rename it or remove it. */
open class MultiGsiActivity : AppCompatActivity() {
    private var binding: ActivityMultiGsiBinding? = null

    private var viewModel: MultiGsiViewModel? = null

    private var currentState: MultiGsiState = MultiGsiState.of(MultiGsiState.Status.LOADING)

    private var busy: Boolean = false

    protected override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMultiGsiBinding.inflate(getLayoutInflater())
        setContentView(binding!!.getRoot())
        binding!!.toolbar.setNavigationOnClickListener({ v ->
            finish()
        })
        InsetsUtils.padBottomAndSides(binding!!.scrollView)
        viewModel = ViewModelProvider(this).`get`(MultiGsiViewModel::class.java)
        viewModel!!.getState().observe(this, { state ->
            currentState = state
            render()
        })
        viewModel!!.getBusy().observe(this, { isBusy ->
            busy = isBusy
            render()
        })
        viewModel!!.getMessages().observe(this, callback4@ { event ->
            if ((event == null)) {
                return@callback4
            }
            val message: UiMessage? = event.getContentIfNotHandled()
            if ((message != null)) {
                Toast.makeText(this, message!!.resolve(this), Toast.LENGTH_LONG).show()
            }
        })
    }

    protected override fun onResume() {
        super.onResume()
        viewModel!!.refresh()
    }

    private fun render() {
        val state: MultiGsiState = currentState
        val loading: Boolean = ((state.status == MultiGsiState.Status.LOADING) || busy)
        binding!!.loading.setVisibility((if (loading) View.VISIBLE else View.GONE))
        var notice: String? = null
        when (state.status) {
            MultiGsiState.Status.UNSUPPORTED -> {
                notice = getString(R.string.multi_gsi_unsupported)
            }
            MultiGsiState.Status.UNAVAILABLE -> {
                notice = getString(R.string.multi_gsi_unavailable)
                if (!state.detail!!.isEmpty()) {
                    notice += ("\n\n" + getString(R.string.multi_gsi_details, state.detail))
                }
            }
            MultiGsiState.Status.READY -> {
                if (state.inUse) {
                    notice = getString(R.string.multi_gsi_in_use)
                } else if (!viewModel!!.isMultiGsiActive()) {
                    notice = getString(R.string.multi_gsi_disabled_hint)
                }
            }
            else -> {

            }
        }
        binding!!.noticeCard.setVisibility((if ((notice == null)) View.GONE else View.VISIBLE))
        if ((notice != null)) {
            binding!!.noticeText.setText(notice)
        }
        val ready: Boolean = (state.status == MultiGsiState.Status.READY)
        binding!!.slotsSection.setVisibility((if (ready) View.VISIBLE else View.GONE))
        if (ready) {
            renderSlots(state)
        }
    }

    private fun renderSlots(state: MultiGsiState) {
        val group: LinearLayout = binding!!.groupSlots
        group.removeAllViews()
        for (item in state.items) {
            val row: ListRow = ListRow.create(group).icon((if (item.active) R.drawable.ic_check else R.drawable.ic_phone)).title(item.info.getLabel()).supporting(describe(item.info)).trailingIcon(R.drawable.ic_chevron_right).onClick({ v ->
                showActions(item.info)
            })
            if (item.active) {
                row.trailingText(getString(R.string.multi_gsi_active))
            }
        }
        SegmentedGroup.apply(group)
        binding!!.emptyText.setVisibility((if (state.items.isEmpty()) View.VISIBLE else View.GONE))
    }

    private fun describe(info: GsiSlotInfo): String {
        val parts: MutableList<String> = ArrayList<String>()
        if (!info.getSourceName().isEmpty()) {
            parts.add(info.getSourceName())
        }
        if ((info.getInstalledAt() > 0L)) {
            parts.add(DateFormat.getMediumDateFormat(this).format(Date(info.getInstalledAt())))
        }
        parts.add(getString(R.string.multi_gsi_slot_id, info.getSlot()))
        return java.lang.String.join(" · ", parts)
    }

    private fun showActions(info: GsiSlotInfo) {
        if (busy) {
            return
        }
        val actions: Array<CharSequence> = arrayOf(getString(R.string.multi_gsi_switch), getString(R.string.multi_gsi_rename), getString(R.string.multi_gsi_remove))
        MaterialAlertDialogBuilder(this).setTitle(info.getLabel()).setItems(actions, { dialog, which ->
            if ((which == 0)) {
                confirmSwitch(info)
            } else if ((which == 1)) {
                promptRename(info)
            } else {
                confirmRemove(info)
            }
        }).show()
    }

    // Both actions change what the device boots or deletes data, neither works while a DSU runs
    private fun refuseWhileRunning(): Boolean {
        if (currentState.inUse) {
            Toast.makeText(this, R.string.multi_gsi_in_use, Toast.LENGTH_LONG).show()
            return true
        }
        return false
    }

    private fun confirmSwitch(info: GsiSlotInfo) {
        if (refuseWhileRunning()) {
            return
        }
        MaterialAlertDialogBuilder(this).setIcon(R.drawable.ic_restart).setTitle(getString(R.string.multi_gsi_switch_title, info.getLabel())).setMessage(R.string.multi_gsi_switch_message).setPositiveButton(R.string.multi_gsi_switch_reboot, { d, w ->
            viewModel!!.switchTo(info.getSlot(), true)
        }).setNeutralButton(R.string.multi_gsi_switch_only, { d, w ->
            viewModel!!.switchTo(info.getSlot(), false)
        }).setNegativeButton(R.string.cancel, null).show()
    }

    private fun confirmRemove(info: GsiSlotInfo) {
        if (refuseWhileRunning()) {
            return
        }
        MaterialAlertDialogBuilder(this).setIcon(R.drawable.ic_delete).setTitle(getString(R.string.multi_gsi_remove_question, info.getLabel())).setMessage(R.string.multi_gsi_remove_message).setPositiveButton(R.string.multi_gsi_remove, { d, w ->
            viewModel!!.remove(info.getSlot())
        }).setNegativeButton(R.string.cancel, null).show()
    }

    private fun promptRename(info: GsiSlotInfo) {
        val content: DialogFileInputBinding = DialogFileInputBinding.inflate(getLayoutInflater())
        content.inputLayout.setHint(getString(R.string.gsi_name))
        content.inputEdit.setText(info.getLabel())
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this).setTitle(R.string.multi_gsi_rename).setView(content.getRoot()).setPositiveButton(R.string.ok, null).setNegativeButton(R.string.cancel, null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(callback10@ { v ->
            val label: String = GsiNames.sanitizeLabel((if ((content.inputEdit.getText() == null)) "" else content.inputEdit.getText()!!.toString()))
            if (label.isEmpty()) {
                content.inputLayout.setError(getString(R.string.multi_gsi_name_empty))
                return@callback10
            }
            dialog.dismiss()
            viewModel!!.rename(info.getSlot(), label)
        })
        content.inputEdit.requestFocus()
        if ((content.inputEdit.getText() != null)) {
            content.inputEdit.setSelection(content.inputEdit.getText()!!.length)
        }
    }

    protected override fun onDestroy() {
        super.onDestroy()
        binding = null
    }
}
