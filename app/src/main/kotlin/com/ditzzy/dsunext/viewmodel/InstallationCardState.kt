package com.ditzzy.dsunext.viewmodel

import com.ditzzy.dsunext.preparation.InstallationStep

/**
 * Snapshot of the installation card. The view model keeps a private working copy and only ever
 * publishes copies, so whatever the UI receives must be treated as read-only.
 */
class InstallationCardState {
    @JvmField
    var step: InstallationStep = InstallationStep.NOT_INSTALLING

    @JvmField
    var fileSelectionEnabled: Boolean = true

    @JvmField
    var installable: Boolean = false

    @JvmField
    var fileError: Boolean = false

    @JvmField
    var fileName: String = ""

    @JvmField
    var errorText: String = ""

    @JvmField
    var progress: Float = 0f

    @JvmField
    var partition: String = ""

    fun copy(): InstallationCardState {
        val copy: InstallationCardState = InstallationCardState()
        copy.step = step
        copy.fileSelectionEnabled = fileSelectionEnabled
        copy.installable = installable
        copy.fileError = fileError
        copy.fileName = fileName
        copy.errorText = errorText
        copy.progress = progress
        copy.partition = partition
        return copy
    }

    fun isInstalling(): Boolean {
        return (step != InstallationStep.NOT_INSTALLING)
    }

    /**
     * The installation is over (or an installed DSU was found) and nothing is running. The card
     * still has a message and buttons, but the rest of the home screen stays usable.
     */
    fun isSettled(): Boolean {
        when (step) {
            InstallationStep.INSTALL_SUCCESS, InstallationStep.INSTALL_SUCCESS_REBOOT_DYN_OS, InstallationStep.DSU_ALREADY_INSTALLED, InstallationStep.DSU_ALREADY_RUNNING_DYN_OS -> {
                return true
            }
            else -> {
                return false
            }
        }
    }

    /** An installation or one of its error screens is taking over the home screen. */
    fun isBusy(): Boolean {
        return (isInstalling() && !isSettled())
    }
}
