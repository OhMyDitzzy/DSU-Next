package com.ditzzy.dsunext.activity;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.databinding.ActivityMultiGsiBinding;
import com.ditzzy.dsunext.databinding.DialogFileInputBinding;
import com.ditzzy.dsunext.model.GsiSlotInfo;
import com.ditzzy.dsunext.ui.InsetsUtils;
import com.ditzzy.dsunext.ui.ListRow;
import com.ditzzy.dsunext.ui.SegmentedGroup;
import com.ditzzy.dsunext.ui.UiMessage;
import com.ditzzy.dsunext.util.GsiNames;
import com.ditzzy.dsunext.viewmodel.MultiGsiState;
import com.ditzzy.dsunext.viewmodel.MultiGsiViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Shows every installed GSI and lets the user switch to one, rename it or remove it. */
public class MultiGsiActivity extends AppCompatActivity {

    private ActivityMultiGsiBinding binding;
    private MultiGsiViewModel viewModel;
    private MultiGsiState currentState = MultiGsiState.of(MultiGsiState.Status.LOADING);
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityMultiGsiBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        InsetsUtils.padBottomAndSides(binding.scrollView);

        viewModel = new ViewModelProvider(this).get(MultiGsiViewModel.class);
        viewModel.getState().observe(this, state -> {
            currentState = state;
            render();
        });
        viewModel.getBusy().observe(this, isBusy -> {
            busy = isBusy;
            render();
        });
        viewModel.getMessages().observe(this, event -> {
            if (event == null) {
                return;
            }
            UiMessage message = event.getContentIfNotHandled();
            if (message != null) {
                Toast.makeText(this, message.resolve(this), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.refresh();
    }

    private void render() {
        MultiGsiState state = currentState;
        boolean loading = state.status == MultiGsiState.Status.LOADING || busy;
        binding.loading.setVisibility(loading ? View.VISIBLE : View.GONE);

        String notice = null;
        switch (state.status) {
            case UNSUPPORTED:
                notice = getString(R.string.multi_gsi_unsupported);
                break;
            case UNAVAILABLE:
                notice = getString(R.string.multi_gsi_unavailable);
                if (!state.detail.isEmpty()) {
                    notice += "\n\n" + getString(R.string.multi_gsi_details, state.detail);
                }
                break;
            case READY:
                if (state.inUse) {
                    notice = getString(R.string.multi_gsi_in_use);
                } else if (!viewModel.isMultiGsiActive()) {
                    notice = getString(R.string.multi_gsi_disabled_hint);
                }
                break;
            default:
                break;
        }
        binding.noticeCard.setVisibility(notice == null ? View.GONE : View.VISIBLE);
        if (notice != null) {
            binding.noticeText.setText(notice);
        }

        boolean ready = state.status == MultiGsiState.Status.READY;
        binding.slotsSection.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (ready) {
            renderSlots(state);
        }
    }

    private void renderSlots(MultiGsiState state) {
        LinearLayout group = binding.groupSlots;
        group.removeAllViews();

        for (MultiGsiState.Item item : state.items) {
            ListRow row = ListRow.create(group)
                    .icon(item.active ? R.drawable.ic_check : R.drawable.ic_phone)
                    .title(item.info.getLabel())
                    .supporting(describe(item.info))
                    .trailingIcon(R.drawable.ic_chevron_right)
                    .onClick(v -> showActions(item.info));
            if (item.active) {
                row.trailingText(getString(R.string.multi_gsi_active));
            }
        }
        SegmentedGroup.apply(group);
        binding.emptyText.setVisibility(state.items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String describe(GsiSlotInfo info) {
        List<String> parts = new ArrayList<>();
        if (!info.getSourceName().isEmpty()) {
            parts.add(info.getSourceName());
        }
        if (info.getInstalledAt() > 0L) {
            parts.add(DateFormat.getMediumDateFormat(this).format(new Date(info.getInstalledAt())));
        }
        parts.add(getString(R.string.multi_gsi_slot_id, info.getSlot()));
        return String.join(" \u00b7 ", parts);
    }

    private void showActions(GsiSlotInfo info) {
        if (busy) {
            return;
        }
        CharSequence[] actions = {
                getString(R.string.multi_gsi_switch),
                getString(R.string.multi_gsi_rename),
                getString(R.string.multi_gsi_remove)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(info.getLabel())
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        confirmSwitch(info);
                    } else if (which == 1) {
                        promptRename(info);
                    } else {
                        confirmRemove(info);
                    }
                })
                .show();
    }

    // Both actions change what the device boots or deletes data, neither works while a DSU runs
    private boolean refuseWhileRunning() {
        if (currentState.inUse) {
            Toast.makeText(this, R.string.multi_gsi_in_use, Toast.LENGTH_LONG).show();
            return true;
        }
        return false;
    }

    private void confirmSwitch(GsiSlotInfo info) {
        if (refuseWhileRunning()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setIcon(R.drawable.ic_restart)
                .setTitle(getString(R.string.multi_gsi_switch_title, info.getLabel()))
                .setMessage(R.string.multi_gsi_switch_message)
                .setPositiveButton(R.string.multi_gsi_switch_reboot, (d, w) -> viewModel.switchTo(info.getSlot(), true))
                .setNeutralButton(R.string.multi_gsi_switch_only, (d, w) -> viewModel.switchTo(info.getSlot(), false))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmRemove(GsiSlotInfo info) {
        if (refuseWhileRunning()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setIcon(R.drawable.ic_delete)
                .setTitle(getString(R.string.multi_gsi_remove_question, info.getLabel()))
                .setMessage(R.string.multi_gsi_remove_message)
                .setPositiveButton(R.string.multi_gsi_remove, (d, w) -> viewModel.remove(info.getSlot()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void promptRename(GsiSlotInfo info) {
        DialogFileInputBinding content = DialogFileInputBinding.inflate(getLayoutInflater());
        content.inputLayout.setHint(getString(R.string.gsi_name));
        content.inputEdit.setText(info.getLabel());
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.multi_gsi_rename)
                .setView(content.getRoot())
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = GsiNames.sanitizeLabel(
                    content.inputEdit.getText() == null ? "" : content.inputEdit.getText().toString());
            if (label.isEmpty()) {
                content.inputLayout.setError(getString(R.string.multi_gsi_name_empty));
                return;
            }
            dialog.dismiss();
            viewModel.rename(info.getSlot(), label);
        });
        content.inputEdit.requestFocus();
        if (content.inputEdit.getText() != null) {
            content.inputEdit.setSelection(content.inputEdit.getText().length());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
