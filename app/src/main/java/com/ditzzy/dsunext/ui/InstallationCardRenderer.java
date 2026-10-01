package com.ditzzy.dsunext.ui;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.databinding.ActivityMainBinding;
import com.ditzzy.dsunext.viewmodel.HomeViewModel;
import com.ditzzy.dsunext.viewmodel.InstallationCardState;
import com.google.android.material.button.MaterialButton;

/** Turns an {@link InstallationCardState} into the text, progress and buttons of the card. */
public final class InstallationCardRenderer {

    /** Actions that need an Activity to be carried out. */
    public interface Host {
        void showLogs();

        void showCommands();
    }

    private static final class Spec {
        String text = "";
        @StringRes int firstLabel = 0;
        Runnable first;
        @StringRes int secondLabel = 0;
        Runnable second;
        boolean showProgress = false;
        boolean indeterminate = false;

        Spec text(String text) {
            this.text = text;
            return this;
        }

        Spec first(@StringRes int label, Runnable action) {
            this.firstLabel = label;
            this.first = action;
            return this;
        }

        Spec second(@StringRes int label, Runnable action) {
            this.secondLabel = label;
            this.second = action;
            return this;
        }

        Spec progress(boolean indeterminate) {
            this.showProgress = true;
            this.indeterminate = indeterminate;
            return this;
        }
    }

    private final Context context;
    private final ActivityMainBinding binding;
    private final HomeViewModel viewModel;
    private final Host host;

    public InstallationCardRenderer(
            Context context, ActivityMainBinding binding, HomeViewModel viewModel, Host host) {
        this.context = context;
        this.binding = binding;
        this.viewModel = viewModel;
        this.host = host;
    }

    public void render(InstallationCardState state) {
        Spec spec = specFor(state);
        binding.progressText.setText(spec.text);

        if (spec.showProgress) {
            binding.progressBar.setVisibility(View.VISIBLE);
            if (spec.indeterminate) {
                binding.progressBar.setIndeterminate(true);
            } else {
                binding.progressBar.setIndeterminate(false);
                binding.progressBar.setProgressCompat(Math.round(state.progress * 100F), true);
            }
        } else {
            binding.progressBar.setVisibility(View.GONE);
        }

        bindButton(binding.progressFirst, spec.firstLabel, spec.first);
        bindButton(binding.progressSecond, spec.secondLabel, spec.second);
        binding.progressButtons.setVisibility(
                spec.firstLabel == 0 && spec.secondLabel == 0 ? View.GONE : View.VISIBLE);
    }

    private static void bindButton(MaterialButton button, @StringRes int label, @Nullable Runnable action) {
        if (label == 0 || action == null) {
            button.setVisibility(View.GONE);
            button.setOnClickListener(null);
            return;
        }
        button.setText(label);
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> action.run());
    }

    private Spec specFor(InstallationCardState state) {
        Runnable cancel = viewModel::onClickCancel;
        Runnable ret = viewModel::onClickReturn;
        Runnable logs = host::showLogs;
        Runnable retry = viewModel::onClickRetryInstallation;
        Runnable reboot = viewModel::onClickRebootToDynOs;
        Runnable discardDsu = () -> viewModel.showSheet(com.ditzzy.dsunext.viewmodel.SheetDisplay.DISCARD_DSU);
        Runnable discardAndInstall = viewModel::onClickDiscardGsiAndStartInstallation;

        switch (state.step) {
            case DSU_ALREADY_INSTALLED:
                return new Spec().text(s(R.string.dsu_already_installed))
                        .first(R.string.reboot_into_dsu, reboot)
                        .second(R.string.discard, discardDsu);
            case DSU_ALREADY_RUNNING_DYN_OS:
                return new Spec().text(s(R.string.already_running_dsu));

            case PROCESSING:
                return new Spec().text(s(R.string.processing)).second(R.string.cancel, cancel).progress(true);
            case COPYING_FILE:
                return new Spec().text(s(R.string.copying_file)).second(R.string.cancel, cancel).progress(false);
            case DECOMPRESSING_XZ:
                return new Spec().text(s(R.string.decompressing_xz)).second(R.string.cancel, cancel).progress(false);
            case COMPRESSING_TO_GZ:
                return new Spec().text(s(R.string.compressing_to_gz)).second(R.string.cancel, cancel).progress(false);
            case DECOMPRESSING_GZIP:
            case EXTRACTING_FILE:
                return new Spec().text(s(R.string.extracting_file)).second(R.string.cancel, cancel).progress(false);

            case DISCARD_CURRENT_GSI:
                return new Spec().text(s(R.string.discard_dsu_otg))
                        .first(R.string.discard_dsu, discardAndInstall)
                        .second(R.string.cancel, cancel);
            case WAITING_USER_CONFIRMATION:
                return new Spec().text(s(R.string.installation_prompt))
                        .first(R.string.try_again, retry)
                        .second(R.string.cancel, cancel);
            case PROCESSING_LOG_READABLE:
                return new Spec().text(s(R.string.installing))
                        .first(R.string.cancel, cancel)
                        .second(R.string.view_logs, logs)
                        .progress(true);
            case INSTALLING:
                return new Spec().text(s(R.string.installing_partition, state.partition))
                        .first(R.string.cancel, cancel)
                        .second(R.string.view_logs, logs)
                        .progress(false);
            case INSTALLING_ROOTED:
                return new Spec().text(s(R.string.installing_partition, state.partition))
                        .second(R.string.cancel, cancel)
                        .progress(false);
            case CREATING_PARTITION:
                return new Spec().text(s(R.string.creating_partition, state.partition))
                        .second(R.string.cancel, cancel)
                        .progress(false);

            case ERROR:
                return new Spec().text(withDetail(s(R.string.unknown_error), state.errorText))
                        .first(R.string.view_logs, logs)
                        .second(R.string.return_label, ret);
            case ERROR_CANCELED:
                return new Spec().text(s(R.string.installation_canceled))
                        .first(R.string.view_logs, logs)
                        .second(R.string.return_label, ret);
            case ERROR_REQUIRES_DISCARD_DSU:
                return new Spec().text(s(R.string.discard_dsu_otg))
                        .first(R.string.discard, discardAndInstall)
                        .second(R.string.cancel, cancel);
            case ERROR_ALREADY_RUNNING_DYN_OS:
                return new Spec().text(s(R.string.already_running_dsu)).second(R.string.return_label, ret);
            case ERROR_CREATE_PARTITION:
                return new Spec().text(s(R.string.failed_create_partition, state.partition))
                        .second(R.string.return_label, ret);
            case ERROR_EXTERNAL_SDCARD_ALLOC:
                return new Spec().text(s(R.string.allocation_error_description))
                        .first(R.string.allocation_error_action, viewModel::onClickUnmountSdCardAndRetry)
                        .second(R.string.cancel, cancel);
            case ERROR_NO_AVAIL_STORAGE:
                return new Spec().text(s(R.string.storage_error_description, viewModel.getAllocPercentageInt()))
                        .first(R.string.try_again, retry)
                        .second(R.string.cancel, cancel);
            case ERROR_F2FS_WRONG_PATH:
                return new Spec().text(s(R.string.fs_features_error_description))
                        .first(R.string.view_logs, logs)
                        .second(R.string.return_label, ret);
            case ERROR_EXTENTS:
                return new Spec().text(s(R.string.extents_error_description))
                        .first(R.string.view_logs, logs)
                        .second(R.string.return_label, ret);
            case ERROR_SELINUX:
                return new Spec().text(s(R.string.selinux_error_description))
                        .first(R.string.selinux_error_action, viewModel::onClickSetSeLinuxPermissive)
                        .second(R.string.cancel, cancel);
            case ERROR_SELINUX_ROOTLESS:
                return new Spec().text(s(R.string.selinux_error_description))
                        .first(R.string.view_logs, logs)
                        .second(R.string.return_label, ret);

            case INSTALL_SUCCESS:
                return new Spec().text(s(R.string.installation_finished_rootless)).second(R.string.return_label, ret);
            case INSTALL_SUCCESS_REBOOT_DYN_OS:
                return new Spec().text(s(R.string.installation_finished))
                        .first(R.string.reboot_into_dsu, reboot)
                        .second(R.string.discard, discardDsu);
            case REQUIRES_ADB_CMD_TO_CONTINUE:
                return new Spec().text(s(R.string.require_adb_cmd_to_continue))
                        .first(R.string.see_commands, host::showCommands)
                        .second(R.string.return_label, ret);

            case NOT_INSTALLING:
            default:
                return new Spec();
        }
    }

    private String s(@StringRes int id, Object... args) {
        return args.length == 0 ? context.getString(id) : context.getString(id, args);
    }

    private static String withDetail(String base, String detail) {
        return detail == null || detail.isEmpty() ? base : base + "\n" + detail;
    }
}
