package com.ditzzy.dsunext.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ditzzy.dsunext.util.FilenameUtils

/** Holds everything that is shared between the UI and the installers during a run. */
class Session {
    private val userSelection: UserSelection = UserSelection()

    private val preferences: InstallationPreferences = InstallationPreferences()

    private val operationModeLiveData: MutableLiveData<OperationMode> = MutableLiveData<OperationMode>(OperationMode.ADB)

    @Volatile
    private var dsuInstallation: DsuInstallationSource = DsuInstallationSource.none()

    @Volatile
    private var operationMode: OperationMode = OperationMode.ADB

    @Volatile
    private var installationScriptPath: String = ""

    @Volatile
    private var operationModeResolved: Boolean = false

    fun getUserSelection(): UserSelection {
        return userSelection
    }

    fun getPreferences(): InstallationPreferences {
        return preferences
    }

    fun getDsuInstallation(): DsuInstallationSource {
        return dsuInstallation
    }

    fun setDsuInstallation(dsuInstallation: DsuInstallationSource) {
        this.dsuInstallation = dsuInstallation
    }

    fun getInstallationScriptPath(): String {
        return installationScriptPath
    }

    fun setInstallationScriptPath(installationScriptPath: String) {
        this.installationScriptPath = installationScriptPath
    }

    fun getOperationMode(): OperationMode {
        return operationMode
    }

    fun getOperationModeLiveData(): LiveData<OperationMode> {
        return operationModeLiveData
    }

    fun setOperationMode(newMode: OperationMode) {
        if ((operationMode == newMode)) {
            return
        }
        operationMode = newMode
        operationModeLiveData.postValue(newMode)
    }

    fun isOperationModeResolved(): Boolean {
        return operationModeResolved
    }

    fun setOperationModeResolved(operationModeResolved: Boolean) {
        this.operationModeResolved = operationModeResolved
    }

    fun isRoot(): Boolean {
        return operationMode.isRoot()
    }

    fun getInstallationParameters(): InstallationParameters {
        val userdataSize: Long = userSelection.getUserSelectedUserdata()
        val absoluteFilePath: String = FilenameUtils.getFilePath(dsuInstallation.getUri(), true)
        var imageSize: Long = dsuInstallation.getFileSize()
        if (userSelection.isCustomImageSize()) {
            imageSize = userSelection.getUserSelectedImageSize()
        }
        return InstallationParameters(userdataSize, absoluteFilePath, imageSize)
    }

    override fun toString(): String {
        return (((((((userSelection).toString() + "\n") + dsuInstallation) + "\n") + preferences) + "\noperationMode: ") + operationMode)
    }
}
