package com.ditzzy.dsunext.ui

import android.content.Context
import android.view.View
import androidx.annotation.StringRes
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.databinding.ActivityMainBinding
import com.ditzzy.dsunext.viewmodel.HomeViewModel
import com.ditzzy.dsunext.viewmodel.InstallationCardState
import com.ditzzy.dsunext.preparation.InstallationStep
import com.google.android.material.button.MaterialButton

/** Turns an [InstallationCardState] into the text, progress and buttons of the card. */
class InstallationCardRenderer {
    /** Actions that need an Activity to be carried out. */
    interface Host {
        fun showLogs()

        fun showCommands()
    }

    private class Spec {
        var text: String = ""

        var firstLabel: Int = 0

        var first: Runnable? = null

        var secondLabel: Int = 0

        var second: Runnable? = null

        var showProgress: Boolean = false

        var indeterminate: Boolean = false

        fun text(text: String): Spec {
            this.text = text
            return this
        }

        fun first(@StringRes label: Int, action: Runnable): Spec {
            this.firstLabel = label
            this.first = action
            return this
        }

        fun second(@StringRes label: Int, action: Runnable): Spec {
            this.secondLabel = label
            this.second = action
            return this
        }

        fun progress(indeterminate: Boolean): Spec {
            this.showProgress = true
            this.indeterminate = indeterminate
            return this
        }
    }

    private val context: Context

    private val binding: ActivityMainBinding

    private val viewModel: HomeViewModel

    private val host: Host

    constructor(context: Context, binding: ActivityMainBinding, viewModel: HomeViewModel, host: Host) {
        this.context = context
        this.binding = binding
        this.viewModel = viewModel
        this.host = host
    }

    fun render(state: InstallationCardState) {
        val spec: Spec = specFor(state)
        binding.progressText.setText(spec.text)
        if (spec.showProgress) {
            binding.progressBar.setVisibility(View.VISIBLE)
            if (spec.indeterminate) {
                binding.progressBar.setIndeterminate(true)
            } else {
                binding.progressBar.setIndeterminate(false)
                binding.progressBar.setProgressCompat(Math.round((state.progress * 100f)), true)
            }
        } else {
            binding.progressBar.setVisibility(View.GONE)
        }
        bindButton(binding.progressFirst, spec.firstLabel, spec.first)
        bindButton(binding.progressSecond, spec.secondLabel, spec.second)
        binding.progressButtons.setVisibility((if (((spec.firstLabel == 0) && (spec.secondLabel == 0))) View.GONE else View.VISIBLE))
    }

    private fun specFor(state: InstallationCardState): Spec {
        val cancel: Runnable = Runnable(viewModel::onClickCancel)
        val ret: Runnable = Runnable(viewModel::onClickReturn)
        val logs: Runnable = Runnable(host::showLogs)
        val retry: Runnable = Runnable(viewModel::onClickRetryInstallation)
        val reboot: Runnable = Runnable(viewModel::onClickRebootToDynOs)
        val discardDsu: Runnable = Runnable {
            viewModel.showSheet(com.ditzzy.dsunext.viewmodel.SheetDisplay.DISCARD_DSU)
        }
        val discardAndInstall: Runnable = Runnable(viewModel::onClickDiscardGsiAndStartInstallation)
        when (state.step) {
            InstallationStep.DSU_ALREADY_INSTALLED -> {
                return Spec().text(s(R.string.dsu_already_installed)).first(R.string.reboot_into_dsu, reboot).second(R.string.discard, discardDsu)
            }
            InstallationStep.DSU_ALREADY_RUNNING_DYN_OS -> {
                return Spec().text(s(R.string.already_running_dsu))
            }
            InstallationStep.PROCESSING -> {
                return Spec().text(s(R.string.processing)).second(R.string.cancel, cancel).progress(true)
            }
            InstallationStep.COPYING_FILE -> {
                return Spec().text(s(R.string.copying_file)).second(R.string.cancel, cancel).progress(false)
            }
            InstallationStep.DECOMPRESSING_XZ -> {
                return Spec().text(s(R.string.decompressing_xz)).second(R.string.cancel, cancel).progress(false)
            }
            InstallationStep.COMPRESSING_TO_GZ -> {
                return Spec().text(s(R.string.compressing_to_gz)).second(R.string.cancel, cancel).progress(false)
            }
            InstallationStep.DECOMPRESSING_GZIP, InstallationStep.EXTRACTING_FILE -> {
                return Spec().text(s(R.string.extracting_file)).second(R.string.cancel, cancel).progress(false)
            }
            InstallationStep.DISCARD_CURRENT_GSI -> {
                return Spec().text(s(R.string.discard_dsu_otg)).first(R.string.discard_dsu, discardAndInstall).second(R.string.cancel, cancel)
            }
            InstallationStep.WAITING_USER_CONFIRMATION -> {
                return Spec().text(s(R.string.installation_prompt)).first(R.string.try_again, retry).second(R.string.cancel, cancel)
            }
            InstallationStep.PROCESSING_LOG_READABLE -> {
                return Spec().text(s(R.string.installing)).first(R.string.cancel, cancel).second(R.string.view_logs, logs).progress(true)
            }
            InstallationStep.INSTALLING -> {
                return Spec().text(s(R.string.installing_partition, state.partition)).first(R.string.cancel, cancel).second(R.string.view_logs, logs).progress(false)
            }
            InstallationStep.INSTALLING_ROOTED -> {
                return Spec().text(s(R.string.installing_partition, state.partition)).second(R.string.cancel, cancel).progress(false)
            }
            InstallationStep.CREATING_PARTITION -> {
                return Spec().text(s(R.string.creating_partition, state.partition)).second(R.string.cancel, cancel).progress(false)
            }
            InstallationStep.ERROR -> {
                return Spec().text(withDetail(s(R.string.unknown_error), state.errorText)).first(R.string.view_logs, logs).second(R.string.return_label, ret)
            }
            InstallationStep.ERROR_CANCELED -> {
                return Spec().text(s(R.string.installation_canceled)).first(R.string.view_logs, logs).second(R.string.return_label, ret)
            }
            InstallationStep.ERROR_REQUIRES_DISCARD_DSU -> {
                return Spec().text(s(R.string.discard_dsu_otg)).first(R.string.discard, discardAndInstall).second(R.string.cancel, cancel)
            }
            InstallationStep.ERROR_ALREADY_RUNNING_DYN_OS -> {
                return Spec().text(s(R.string.already_running_dsu)).second(R.string.return_label, ret)
            }
            InstallationStep.ERROR_CREATE_PARTITION -> {
                return Spec().text(s(R.string.failed_create_partition, state.partition)).second(R.string.return_label, ret)
            }
            InstallationStep.ERROR_EXTERNAL_SDCARD_ALLOC -> {
                return Spec().text(s(R.string.allocation_error_description)).first(R.string.allocation_error_action, Runnable(viewModel::onClickUnmountSdCardAndRetry)).second(R.string.cancel, cancel)
            }
            InstallationStep.ERROR_NO_AVAIL_STORAGE -> {
                return Spec().text(s(R.string.storage_error_description, viewModel.getAllocPercentageInt())).first(R.string.try_again, retry).second(R.string.cancel, cancel)
            }
            InstallationStep.ERROR_F2FS_WRONG_PATH -> {
                return Spec().text(s(R.string.fs_features_error_description)).first(R.string.view_logs, logs).second(R.string.return_label, ret)
            }
            InstallationStep.ERROR_EXTENTS -> {
                return Spec().text(s(R.string.extents_error_description)).first(R.string.view_logs, logs).second(R.string.return_label, ret)
            }
            InstallationStep.ERROR_SELINUX -> {
                return Spec().text(s(R.string.selinux_error_description)).first(R.string.selinux_error_action, Runnable(viewModel::onClickSetSeLinuxPermissive)).second(R.string.cancel, cancel)
            }
            InstallationStep.ERROR_SELINUX_ROOTLESS -> {
                return Spec().text(s(R.string.selinux_error_description)).first(R.string.view_logs, logs).second(R.string.return_label, ret)
            }
            InstallationStep.INSTALL_SUCCESS -> {
                return Spec().text(s(R.string.installation_finished_rootless)).second(R.string.return_label, ret)
            }
            InstallationStep.INSTALL_SUCCESS_REBOOT_DYN_OS -> {
                return Spec().text(s(R.string.installation_finished)).first(R.string.reboot_into_dsu, reboot).second(R.string.discard, discardDsu)
            }
            InstallationStep.REQUIRES_ADB_CMD_TO_CONTINUE -> {
                return Spec().text(s(R.string.require_adb_cmd_to_continue)).first(R.string.see_commands, Runnable(host::showCommands)).second(R.string.return_label, ret)
            }
            InstallationStep.NOT_INSTALLING -> {
                return Spec()
            }
        }
    }

    private fun s(@StringRes id: Int, vararg args: Any?): String {
        return (if ((args.size == 0)) context.getString(id) else context.getString(id, *args))
    }

    companion object {
        private fun bindButton(button: MaterialButton, @StringRes label: Int, action: Runnable?) {
            if (((label == 0) || (action == null))) {
                button.setVisibility(View.GONE)
                button.setOnClickListener(null)
                return
            }
            button.setText(label)
            button.setVisibility(View.VISIBLE)
            button.setOnClickListener({ v ->
                action!!.run()
            })
        }

        private fun withDetail(base: String, detail: String?): String {
            return (if (((detail == null) || detail!!.isEmpty())) base else ((base + "\n") + detail))
        }
    }
}
