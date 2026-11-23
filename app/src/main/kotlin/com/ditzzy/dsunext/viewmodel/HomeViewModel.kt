package com.ditzzy.dsunext.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ditzzy.dsunext.BuildConfig
import com.ditzzy.dsunext.DsuNextApp
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.activity.MainActivity
import com.ditzzy.dsunext.core.AppPrefs
import com.ditzzy.dsunext.core.SlotRegistry
import com.ditzzy.dsunext.core.StorageManager
import com.ditzzy.dsunext.installer.adb.AdbInstallationHandler
import com.ditzzy.dsunext.installer.privileged.DsuInstallationHandler
import com.ditzzy.dsunext.installer.privileged.LogcatDiagnostic
import com.ditzzy.dsunext.installer.root.DsuInstaller
import com.ditzzy.dsunext.model.DsuConstants
import com.ditzzy.dsunext.model.DsuInstallationSource
import com.ditzzy.dsunext.model.GsiSlotInfo
import com.ditzzy.dsunext.model.OperationMode
import com.ditzzy.dsunext.model.Session
import com.ditzzy.dsunext.preparation.InstallationStep
import com.ditzzy.dsunext.preparation.Preparation
import com.ditzzy.dsunext.service.PrivilegedProvider
import com.ditzzy.dsunext.service.PrivilegedProvider.ServiceAction
import com.ditzzy.dsunext.ui.Event
import com.ditzzy.dsunext.ui.UiMessage
import com.ditzzy.dsunext.util.DevicePropUtils
import com.ditzzy.dsunext.util.FilenameUtils
import com.ditzzy.dsunext.util.GsiNames
import com.ditzzy.dsunext.util.InstallationJob
import com.ditzzy.dsunext.util.MultiGsi
import com.ditzzy.dsunext.util.SlotQueries
import com.ditzzy.dsunext.util.OperationModeUtils
import com.ditzzy.dsunext.util.StorageUtils
import com.topjohnwu.superuser.Shell
import java.io.IOException
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer

class HomeViewModel : AndroidViewModel {
    private val appPrefs: AppPrefs

    private val storageManager: StorageManager

    private val slotRegistry: SlotRegistry

    private val session: Session

    private val io: ExecutorService = Executors.newCachedThreadPool()

    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    private val stateLock: Any = Any()

    private val additionalCard: MutableLiveData<AdditionalCard> = MutableLiveData<AdditionalCard>(AdditionalCard.NONE)

    private val installationLive: MutableLiveData<InstallationCardState> = MutableLiveData<InstallationCardState>(InstallationCardState())

    private val userDataLive: MutableLiveData<UserDataCardState> = MutableLiveData<UserDataCardState>(UserDataCardState())

    private val imageSizeLive: MutableLiveData<ImageSizeCardState> = MutableLiveData<ImageSizeCardState>(ImageSizeCardState())

    private val sheet: MutableLiveData<SheetDisplay> = MutableLiveData<SheetDisplay>(SheetDisplay.NONE)

    private val logs: MutableLiveData<String> = MutableLiveData<String>("")

    private val passedInitialChecks: MutableLiveData<Boolean> = MutableLiveData<Boolean>(false)

    private val keepScreenOn: MutableLiveData<Boolean> = MutableLiveData<Boolean>(false)

    private val messages: MutableLiveData<Event<UiMessage>> = MutableLiveData<Event<UiMessage>>()

    private var installation: InstallationCardState = InstallationCardState()

    private var userData: UserDataCardState = UserDataCardState()

    private var imageSize: ImageSizeCardState = ImageSizeCardState()

    @Volatile
    private var installationJob: InstallationJob = InstallationJob()

    @Volatile
    private var logger: LogcatDiagnostic? = null

    @Volatile
    private var installSlot: String = ""

    @Volatile
    private var checkDynamicPartitions: Boolean = true

    @Volatile
    private var checkUnavailableStorage: Boolean = true

    @Volatile
    private var checkReadLogsPermission: Boolean = true

    @Volatile
    private var disabledStorageCheck: Boolean = false

    private val allocPercentage: Float = DevicePropUtils.getGsidBinaryAllowedPerc()

    private val allocPercentageInt: Int = Math.round((allocPercentage * 100f))

    private val storageStats: StorageUtils.AllocInfo = StorageUtils.getAllocInfo(allocPercentage)

    constructor(application: Application) : super(application) {

        val app: DsuNextApp = DsuNextApp.from(application)
        appPrefs = app.getAppPrefs()
        storageManager = app.getStorageManager()
        slotRegistry = app.getSlotRegistry()
        session = app.getSession()
    }

    fun getAdditionalCard(): LiveData<AdditionalCard> {
        return additionalCard
    }

    fun getInstallationCard(): LiveData<InstallationCardState> {
        return installationLive
    }

    fun getUserDataCard(): LiveData<UserDataCardState> {
        return userDataLive
    }

    fun getImageSizeCard(): LiveData<ImageSizeCardState> {
        return imageSizeLive
    }

    fun getSheet(): LiveData<SheetDisplay> {
        return sheet
    }

    fun getLogs(): LiveData<String> {
        return logs
    }

    fun getPassedInitialChecks(): LiveData<Boolean> {
        return passedInitialChecks
    }

    fun getKeepScreenOn(): LiveData<Boolean> {
        return keepScreenOn
    }

    fun getMessages(): LiveData<Event<UiMessage>> {
        return messages
    }

    fun getSession(): Session {
        return session
    }

    fun getAllocPercentageInt(): Int {
        return allocPercentageInt
    }

    fun getSelectedFilename(): String {
        return session.getUserSelection().getSelectedFileName()
    }

    /** True when a new installation goes to its own slot and leaves the installed GSIs alone. */
    fun isMultiGsiActive(): Boolean {
        return MultiGsi.isActive(appPrefs, session)
    }

    /** What the GSI name field starts with: the selected file name without its extension. */
    fun getDefaultGsiName(): String {
        return GsiNames.labelFromFileName(session.getUserSelection().getSelectedFileName())
    }

    protected override fun onCleared() {
        installationJob.cancel()
        val current: LogcatDiagnostic? = logger
        if ((current != null)) {
            current!!.destroy()
        }
        io.shutdown()
    }

    private fun updateInstallation(change: Consumer<InstallationCardState>) {
        synchronized(stateLock) {
            change.accept(installation)
            installationLive.postValue(installation.copy())
        }
    }

    private fun updateUserData(change: Consumer<UserDataCardState>) {
        synchronized(stateLock) {
            change.accept(userData)
            userDataLive.postValue(userData.copy())
        }
    }

    private fun updateImageSize(change: Consumer<ImageSizeCardState>) {
        synchronized(stateLock) {
            change.accept(imageSize)
            imageSizeLive.postValue(imageSize.copy())
        }
    }

    private fun setStep(step: InstallationStep) {
        updateInstallation(Consumer { s ->
            (step).also { s.step = it }
        })
    }

    private fun postMessage(resId: Int, vararg args: Any?) {
        messages.postValue(Event<UiMessage>(UiMessage(resId, *args)))
    }

    /** Back to the file selection, keeping whatever file the user already picked. */
    private fun resetInstallationCard() {
        updateInstallation(Consumer { s ->
            s.step = InstallationStep.NOT_INSTALLING
            s.progress = 0f
            s.partition = ""
            s.errorText = ""
        })
        sheet.postValue(SheetDisplay.NONE)
    }

    fun dismissSheet() {
        sheet.postValue(SheetDisplay.NONE)
    }

    fun showSheet(display: SheetDisplay) {
        sheet.postValue(display)
    }

    fun refreshPreferences() {
        keepScreenOn.postValue(appPrefs.getBoolean(AppPrefs.KEEP_SCREEN_ON))
        disabledStorageCheck = appPrefs.getBoolean(AppPrefs.DISABLE_STORAGE_CHECK)
        // "A DSU is already installed" blocks the home screen, and Multi-GSI does not want that
        var stale: Boolean = false
        synchronized(stateLock) {
            stale = (installation.step == InstallationStep.DSU_ALREADY_INSTALLED)
        }
        if ((stale && isMultiGsiActive())) {
            resetInstallationCard()
        }
    }

    /**
     * A finished installation (or a DSU found at startup) stays on the card until something
     * changes it. The GSI can be removed elsewhere meanwhile (eg: the Multi-GSI screen), so on
     * return the card is checked against what is really installed and dropped when it is gone.
     */
    fun verifyInstalledState() {
        var step: InstallationStep? = null
        synchronized(stateLock) {
            step = installation.step
        }
        if ((!MultiGsi.hasPrivilegedAccess(session) || ((step != InstallationStep.INSTALL_SUCCESS_REBOOT_DYN_OS) && (step != InstallationStep.DSU_ALREADY_INSTALLED)))) {
            return
        }
        val slot: String = installSlot
        val slotTarget: Boolean = (isMultiGsiActive() && !slot.isEmpty())
        PrivilegedProvider.run(ServiceAction callback4@ { service ->
            var present: Boolean = false
            try {
                val slots: List<String> = SlotQueries.list(service, slotRegistry)
                present = (if (slotTarget) slots.contains(slot) else (!slots.isEmpty() || service.isInstalled()))
            } catch (e: RemoteException) {
                present = service.isInstalled()
            } catch (e: RuntimeException) {
                present = service.isInstalled()
            }
            if (present) {
                return@callback4
            }
            var stale: Boolean = false
            synchronized(stateLock) {
                stale = (installation.step == step)
            }
            if (stale) {
                Log.d(TAG, "The installed GSI is gone, clearing the installation card.")
                resetInstallationCard()
            }
        })
    }

    /**
     * Must run once the operation mode is known, and again whenever it changes
     * (eg: when Shizuku shows up after startup).
     */
    fun onOperationModeChanged() {
        initialChecks()
        // Checking for an existing DSU needs MANAGE_DYNAMIC_SYSTEM, so it's root only, except for
        // Multi-GSI: it has no use for the "already installed" card, and a running DSU is a question
        // the privileged service answers to shell too.
        val multiGsi: Boolean = isMultiGsiActive()
        if (((session.isRoot() || multiGsi) && !isInstalling())) {
            PrivilegedProvider.run(ServiceAction { service ->
                if (service.isInUse()) {
                    setStep(InstallationStep.DSU_ALREADY_RUNNING_DYN_OS)
                } else if ((!multiGsi && service.isInstalled())) {
                    setStep(InstallationStep.DSU_ALREADY_INSTALLED)
                }
            })
        }
    }

    fun initialChecks() {
        if (!session.isOperationModeResolved()) {
            return
        }
        if (!appPrefs.isUserAgreementAccepted()) {
            additionalCard.postValue(AdditionalCard.NONE)
            passedInitialChecks.postValue(false)
            return
        }
        if ((checkDynamicPartitions && !DevicePropUtils.hasDynamicPartitions())) {
            blockWith(AdditionalCard.NO_DYNAMIC_PARTITIONS)
            return
        }
        if ((checkUnavailableStorage && !storageStats.hasAvailableStorage())) {
            blockWith(AdditionalCard.UNAVAILABLE_STORAGE)
            return
        }
        io.execute(callback7@ {
            if (!storageManager.arePermissionsGrantedToFolder(appPrefs.getString(AppPrefs.SAF_PATH))) {
                blockWith(AdditionalCard.SETUP_STORAGE)
                return@callback7
            }
            if ((((session.getOperationMode() == OperationMode.SHIZUKU) && checkReadLogsPermission) && !OperationModeUtils.isReadLogsPermissionGranted(getApplication<Application>()))) {
                blockWith(AdditionalCard.MISSING_READ_LOGS_PERMISSION)
                return@callback7
            }
            additionalCard.postValue(AdditionalCard.NONE)
            passedInitialChecks.postValue(true)
        })
    }

    private fun blockWith(card: AdditionalCard) {
        passedInitialChecks.postValue(false)
        additionalCard.postValue(card)
    }

    fun overrideDynamicPartitionCheck() {
        checkDynamicPartitions = false
        initialChecks()
    }

    fun overrideUnavailableStorage() {
        checkUnavailableStorage = false
        initialChecks()
    }

    fun refuseReadLogs() {
        checkReadLogsPermission = false
        initialChecks()
    }

    fun grantReadLogs() {
        additionalCard.postValue(AdditionalCard.GRANTING_READ_LOGS_PERMISSION)
        val restart: ComponentName = ComponentName(BuildConfig.APPLICATION_ID, MainActivity::class.java.getName())
        PrivilegedProvider.run(ServiceAction callback9@ { service ->
            if (!service.grantPermission(READ_LOGS, restart)) {
                onReadLogsGrantFailed()
                return@callback9
            }
            mainHandler.postDelayed(this::initialChecks, GRANT_RECHECK_MS)
        }, Runnable(this::onReadLogsGrantFailed))
    }

    private fun onReadLogsGrantFailed() {
        postMessage(R.string.grant_permission_failed, BuildConfig.APPLICATION_ID)
        additionalCard.postValue(AdditionalCard.MISSING_READ_LOGS_PERMISSION)
    }

    fun takeUriPermission(uri: Uri) {
        getApplication<Application>().getContentResolver().takePersistableUriPermission(uri, (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        io.execute({
            if (storageManager.arePermissionsGrantedToFolder(uri.toString())) {
                appPrefs.setString(AppPrefs.SAF_PATH, uri.toString())
                initialChecks()
            }
        })
    }

    fun onFileSelectionResult(uri: Uri) {
        io.execute(callback11@ {
            val filename: String = FilenameUtils.queryName(getApplication<Application>().getContentResolver(), uri)
            val dot: Int = filename.lastIndexOf('.')
            val extension: String = (if ((dot >= 0)) filename.substring((dot + 1)).lowercase(Locale.ROOT) else "")
            // DSU packages (zip files) are only supported on Android 11 and newer
            val supported: Boolean = (SUPPORTED_EXTENSIONS.contains(extension) || ((Build.VERSION.SDK_INT > 29) && extension.equals("zip")))
            Log.d(TAG, ((("isFileSupported: " + supported) + ", filename: ") + filename))
            if (!supported) {
                updateInstallation(Consumer { s ->
                    s.fileError = true
                    s.fileSelectionEnabled = false
                })
                mainHandler.postDelayed({
                    updateInstallation(Consumer { s ->
                        s.fileError = false
                        s.fileSelectionEnabled = true
                    })
                }, FILE_ERROR_MS)
                return@callback11
            }
            session.getUserSelection().setSelectedFile(uri, filename)
            updateInstallation(Consumer { s ->
                s.fileName = filename
                s.fileSelectionEnabled = true
                s.fileError = false
                s.installable = true
            })
        })
    }

    fun onClickClear() {
        session.getUserSelection().setSelectedFile(Uri.EMPTY, "")
        session.setDsuInstallation(DsuInstallationSource.none())
        updateInstallation(Consumer { s ->
            s.step = InstallationStep.NOT_INSTALLING
            s.fileName = ""
            s.installable = false
            s.fileError = false
            s.fileSelectionEnabled = true
            s.progress = 0f
            s.partition = ""
            s.errorText = ""
        })
        sheet.postValue(SheetDisplay.NONE)
    }

    fun onClickReturn() {
        session.setDsuInstallation(DsuInstallationSource.none())
        resetInstallationCard()
    }

    fun onUserdataToggled(checked: Boolean) {
        updateUserData(Consumer { s ->
            s.selected = checked
            s.text = ""
            s.error = false
        })
    }

    fun updateUserdataSize(input: String) {
        val digits: String = FilenameUtils.getDigits(input)
        if (((!disabledStorageCheck && !digits.isEmpty()) && exceeds(digits, (storageStats.getMaximumAllowedGb()).toLong()))) {
            val max: Int = storageStats.getMaximumAllowedGb()
            updateUserData(Consumer { s ->
                s.text = java.lang.String.valueOf(max)
                s.error = true
                s.maximumAllowed = max
            })
            mainHandler.postDelayed({
                updateUserData(Consumer { s ->
                    (false).also { s.error = it }
                })
            }, USERDATA_ERROR_MS)
            return
        }
        updateUserData(Consumer { s ->
            (digits).also { s.text = it }
        })
    }

    fun onImageSizeToggled(checked: Boolean) {
        updateImageSize(Consumer { s ->
            s.selected = checked
            s.text = ""
        })
        sheet.postValue((if (checked) SheetDisplay.IMAGE_SIZE_WARNING else SheetDisplay.NONE))
    }

    fun onImageSizeInput(input: String) {
        val digits: String = FilenameUtils.getDigits(input)
        updateImageSize(Consumer { s ->
            (digits).also { s.text = it }
        })
    }

    fun onImageSizeWarningConfirmed() {
        dismissSheet()
    }

    fun onImageSizeWarningCancelled() {
        updateImageSize(Consumer { s ->
            s.selected = false
            s.text = ""
        })
        dismissSheet()
    }

    fun onClickInstall() {
        synchronized(stateLock) {
            session.getUserSelection().setUserDataSize(userData.text)
            session.getUserSelection().setImageSize(imageSize.text)
        }
        sheet.postValue(SheetDisplay.CONFIRM_INSTALLATION)
    }

    private fun isInstalling(): Boolean {
        synchronized(stateLock) {
            return installation.isInstalling()
        }
    }

    fun onClickCancel() {
        if (isInstalling()) {
            sheet.postValue(SheetDisplay.CANCEL_INSTALLATION)
        }
    }

    fun getConfirmUserdataGb(): String {
        return session.getUserSelection().getUserDataSizeAsGb()
    }

    /** @return the custom image size in bytes, or [DsuConstants#DEFAULT_IMAGE_SIZE]. */
    fun getConfirmImageSize(): Long {
        return (if (session.getUserSelection().isCustomImageSize()) session.getUserSelection().getUserSelectedImageSize() else DsuConstants.DEFAULT_IMAGE_SIZE)
    }

    fun onConfirmInstallationSheet() {
        onConfirmInstallationSheet("")
    }

    /** @param gsiName the name typed for the GSI, only used by Multi-GSI. May be empty. */
    fun onConfirmInstallationSheet(gsiName: String) {
        session.getUserSelection().setGsiName(GsiNames.sanitizeLabel(gsiName))
        // A new installation gets a new slot, a retry of this one keeps it
        installSlot = ""
        dismissSheet()
        setStep(InstallationStep.PROCESSING)
        val job: InstallationJob = InstallationJob()
        installationJob = job
        io.execute({
            session.getPreferences().setUnmountSdCard(appPrefs.getBoolean(AppPrefs.UMOUNT_SD))
            session.getPreferences().setUseBuiltinInstaller(appPrefs.getBoolean(AppPrefs.USE_BUILTIN_INSTALLER))
            try {
                Preparation(storageManager, session, job, object : Preparation.Callbacks {
                    override fun onStepUpdate(step: InstallationStep) {
                        setStep(step)
                    }

                    override fun onProgressUpdate(progress: Float) {
                        updateInstallation({ s ->
                            (progress).also { s.progress = it }
                        })
                    }

                    override fun onCanceled() {
                        session.setDsuInstallation(DsuInstallationSource.none())
                        resetInstallationCard()
                    }

                    override fun onFinished(preparedSource: DsuInstallationSource) {
                        Log.d(TAG, ("DSU preparation finished, result: " + preparedSource))
                        session.setDsuInstallation(preparedSource)
                        startInstallation()
                    }
                }).invoke()
            } catch (e: IOException) {
                Log.e(TAG, "Preparation failed.", e)
                // Canceling closes streams on purpose, which isn't an error worth reporting
                if (!job.isCancelled()) {
                    onInstallationError(InstallationStep.ERROR, java.lang.String.valueOf(e))
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "Preparation failed.", e)
                if (!job.isCancelled()) {
                    onInstallationError(InstallationStep.ERROR, java.lang.String.valueOf(e))
                }
            }
        })
    }

    private fun startInstallation() {
        io.execute(this::startInstallationBlocking)
    }

    private fun startInstallationBlocking() {
        Log.d(TAG, ("startInstallation(), session:\n" + session))
        setStep(InstallationStep.PROCESSING)
        try {
            if ((session.getOperationMode() == OperationMode.ADB)) {
                setupAdbInstallation()
            } else if ((session.getPreferences().isUseBuiltinInstaller() && session.isRoot())) {
                startDsuInstallation()
            } else {
                startPrivilegedInstallation()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Unable to start the installation.", e)
            if (!installationJob.isCancelled()) {
                onInstallationError(InstallationStep.ERROR, java.lang.String.valueOf(e))
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Unable to start the installation.", e)
            if (!installationJob.isCancelled()) {
                onInstallationError(InstallationStep.ERROR, java.lang.String.valueOf(e))
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to start the installation.", e)
            if (!installationJob.isCancelled()) {
                onInstallationError(InstallationStep.ERROR, java.lang.String.valueOf(e))
            }
        }
    }

    @Throws(IOException::class)
    private fun setupAdbInstallation() {
        val scriptPath: String = AdbInstallationHandler(storageManager, session).generate()
        Log.d(TAG, ("Installation script generated: " + scriptPath))
        session.setInstallationScriptPath(scriptPath)
        setStep(InstallationStep.REQUIRES_ADB_CMD_TO_CONTINUE)
    }

    /** Picks the slot of the installation, once per confirmed installation. Blocks, so not for the main thread. */
    private fun resolveInstallSlot(multiGsi: Boolean): String {
        if (!multiGsi) {
            return GsiNames.DEFAULT_SLOT
        }
        var slot: String = installSlot
        if (slot.isEmpty()) {
            var existing: List<String> = Collections.emptyList()
            try {
                existing = SlotQueries.list(PrivilegedProvider.getService(), slotRegistry)
            } catch (e: RemoteException) {
                Log.w(TAG, "Unable to list the installed slots, picking an id anyway.", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to list the installed slots, picking an id anyway.", e)
            }
            slot = GsiNames.newSlotId((System.currentTimeMillis() / 1000L), existing)
            installSlot = slot
        }
        return slot
    }

    private fun resolveGsiLabel(slot: String): String {
        val typed: String = session.getUserSelection().getGsiName()
        val label: String = (if (typed.isEmpty()) getDefaultGsiName() else typed)
        return (if (label.isEmpty()) slot else label)
    }

    private fun startDsuInstallation() {
        val multiGsi: Boolean = isMultiGsiActive()
        val slot: String = resolveInstallSlot(multiGsi)
        Log.d(TAG, ((("Built-in installation, slot: " + slot) + ", multiGsi: ") + multiGsi))
        DsuInstaller(getApplication<Application>(), session.getUserSelection().getUserSelectedUserdata(), session.getDsuInstallation(), installationJob, slot, multiGsi, object : DsuInstaller.Callbacks {
            override fun onInstallationError(error: InstallationStep, errorInfo: String) {
                this@HomeViewModel.onInstallationError(error, errorInfo)
            }

            override fun onInstallationProgressUpdate(progress: Float, partition: String) {
                this@HomeViewModel.onInstallationProgressUpdate(progress, partition)
            }

            override fun onCreatePartition(partition: String) {
                updateInstallation({ s ->
                    s.step = InstallationStep.CREATING_PARTITION
                    s.partition = partition
                })
            }

            override fun onInstallationStepUpdate(step: InstallationStep) {
                setStep(step)
            }

            override fun onInstallationSuccess() {
                if (multiGsi) {
                    slotRegistry.put(GsiSlotInfo(slot, resolveGsiLabel(slot), session.getUserSelection().getSelectedFileName(), System.currentTimeMillis()))
                }
                setStep(InstallationStep.INSTALL_SUCCESS_REBOOT_DYN_OS)
                if (multiGsi) {
                    sheet.postValue(SheetDisplay.INSTALL_FINISHED)
                }
            }
        }).invoke()
    }

    @Throws(RemoteException::class)
    private fun startPrivilegedInstallation() {
        setStep(InstallationStep.WAITING_USER_CONFIRMATION)
        // The DSU app installs into the slot it is given, so Multi-GSI works without the built-in installer
        val multiGsi: Boolean = isMultiGsiActive()
        var slot: String = ""
        if (multiGsi) {
            slot = resolveInstallSlot(true)
            // Only a real GSI once the installation is done, see finalizeInstalledSlot()
            slotRegistry.setPending(GsiSlotInfo(slot, resolveGsiLabel(slot), session.getUserSelection().getSelectedFileName(), System.currentTimeMillis()))
        }
        Log.d(TAG, (((("DSU app installation, slot: " + (if (slot.isEmpty()) "(default)" else slot)) + ", multiGsi: ") + multiGsi)))
        DsuInstallationHandler(session, slot).startInstallation()
        if ((session.isRoot() || OperationModeUtils.isReadLogsPermissionGranted(getApplication<Application>()))) {
            startLogging()
        } else {
            setStep(InstallationStep.INSTALL_SUCCESS)
        }
    }

    /**
     * The DSU app finished a Multi-GSI installation: register the GSI under the slot gsid now has as
     * active (installing makes the new slot the active one). If that is not the slot that was asked
     * for, this DSU app ignored KEY_DSU_SLOT and installed to its default slot, and the user is told.
     */
    private fun finalizeInstalledSlot() {
        if (!isMultiGsiActive()) {
            return
        }
        val requested: String = installSlot
        PrivilegedProvider.run(ServiceAction { service ->
            val registered: String = slotRegistry.promotePending(service.getActiveSlot(), true)
            if ((!registered.isEmpty() && !requested.isEmpty() && !registered.equals(requested))) {
                Log.w(TAG, (((("Asked for slot " + requested) + " but the DSU app installed to ") + registered) + "."))
                installSlot = registered
                postMessage(R.string.multi_gsi_slot_ignored)
            }
        })
    }

    // Tracks and diagnoses the installation by reading logcat
    private fun startLogging() {
        if ((logger == null)) {
            logger = LogcatDiagnostic(object : LogcatDiagnostic.Callbacks {
                override fun onInstallationError(error: InstallationStep, errorInfo: String) {
                    this@HomeViewModel.onInstallationError(error, errorInfo)
                }

                override fun onStepUpdate(step: InstallationStep) {
                    setStep(step)
                }

                override fun onInstallationProgressUpdate(progress: Float, partition: String) {
                    this@HomeViewModel.onInstallationProgressUpdate(progress, partition)
                }

                override fun onInstallationSuccess() {
                    setStep(InstallationStep.INSTALL_SUCCESS)
                    finalizeInstalledSlot()
                }

                override fun onLogLineReceived() {
                    val current: LogcatDiagnostic? = logger
                    if ((current != null)) {
                        logs.postValue(current!!.getLogs())
                    }
                }
            })
        }
        val current: LogcatDiagnostic? = logger
        io.execute({
            current!!.setShouldLogEverything(appPrefs.getBoolean(AppPrefs.FULL_LOGCAT_LOGGING))
            current!!.startLogging(generateUsefulLogInfo())
        })
    }

    private fun generateUsefulLogInfo(): String {
        return ((((((((((((((((((((((((((((((((((((("Device: " + Build.MODEL) + "\n") + "SDK: Android ") + Build.VERSION.RELEASE) + " (") + Build.VERSION.SDK_INT) + ")\n") + session) + "\n") + "Package: ") + BuildConfig.APPLICATION_ID) + "\n") + "Version: ") + BuildConfig.VERSION_NAME) + " - ") + BuildConfig.VERSION_CODE) + " (") + BuildConfig.BUILD_TYPE) + ")\n") + "checkDynamicPartitions: ") + checkDynamicPartitions) + "\n") + "checkUnavailableStorage: ") + checkUnavailableStorage) + "\n") + "checkReadLogsPermission: ") + checkReadLogsPermission) + "\n") + "allocPercentage: ") + allocPercentage) + "\n") + "hasAvailableStorage: ") + storageStats.hasAvailableStorage()) + "\n") + "maximumAllowedForAllocation: ") + storageStats.getMaximumAllowedGb()) + "\n")
    }

    fun saveLogs(destination: Uri) {
        val content: String = (if ((logs.getValue() == null)) "" else logs.getValue()!!)
        io.execute({
            try {
                storageManager.writeStringToUri(content, destination)
                postMessage(R.string.saved_logs)
            } catch (e: IOException) {
                Log.e(TAG, "Unable to save logs.", e)
                postMessage(R.string.save_logs_failed)
            }
        })
    }

    fun onClickCancelInstallationButton() {
        val current: LogcatDiagnostic? = logger
        if ((((session.getOperationMode() != OperationMode.ADB) && (current != null)) && current!!.isLogging())) {
            current!!.destroy()
            PrivilegedProvider.run(ServiceAction { service ->
                service.forceStopPackage(DSU_PACKAGE)
            })
        }
        installationJob.cancel()
        slotRegistry.clearPending()
        session.setDsuInstallation(DsuInstallationSource.none())
        resetInstallationCard()
    }

    // With Multi-GSI the slot that was just installed is the one to boot or to discard
    private fun targetsInstalledSlot(): Boolean {
        return (isMultiGsiActive() && !installSlot.isEmpty())
    }

    fun onClickRebootToDynOs() {
        sheet.postValue(SheetDisplay.NONE)
        setStep(InstallationStep.PROCESSING)
        val slotTarget: Boolean = targetsInstalledSlot()
        val slot: String = installSlot
        PrivilegedProvider.run(ServiceAction callback34@ { service ->
            if (slotTarget) {
                if (!service.activateSlot(slot, true)) {
                    postMessage(R.string.multi_gsi_switch_failed)
                    setStep(InstallationStep.INSTALL_SUCCESS_REBOOT_DYN_OS)
                    sheet.postValue(SheetDisplay.INSTALL_FINISHED)
                    return@callback34
                }
                slotRegistry.setSelected(slot)
            } else {
                service.setEnable(true, true)
            }
            Shell.cmd("reboot").exec()
        })
    }

    // Removes the slot of this installation only, or the installed DSU when Multi-GSI is off
    @Throws(RemoteException::class)
    private fun discardTarget(service: com.ditzzy.dsunext.IPrivilegedService, slotTarget: Boolean, slot: String) {
        if (slotTarget) {
            if (service.removeSlot(slot)) {
                slotRegistry.remove(slot)
            } else {
                postMessage(R.string.multi_gsi_remove_failed)
            }
        } else {
            service.remove()
        }
    }

    fun onClickDiscardGsiAndStartInstallation() {
        setStep(InstallationStep.PROCESSING)
        val slotTarget: Boolean = targetsInstalledSlot()
        val slot: String = installSlot
        PrivilegedProvider.run(ServiceAction { service ->
            discardTarget(service, slotTarget, slot)
            service.forceStopPackage(DSU_PACKAGE)
            startDsuInstallation()
        })
    }

    fun onClickDiscardGsi() {
        setStep(InstallationStep.PROCESSING)
        val slotTarget: Boolean = targetsInstalledSlot()
        val slot: String = installSlot
        PrivilegedProvider.run(ServiceAction { service ->
            discardTarget(service, slotTarget, slot)
            service.forceStopPackage(DSU_PACKAGE)
            resetInstallationCard()
        })
    }

    fun onClickRetryInstallation() {
        setStep(InstallationStep.PROCESSING)
        startInstallation()
    }

    fun onClickUnmountSdCardAndRetry() {
        setStep(InstallationStep.PROCESSING)
        session.getPreferences().setUnmountSdCard(true)
        startInstallation()
    }

    fun onClickSetSeLinuxPermissive() {
        setStep(InstallationStep.PROCESSING)
        io.execute({
            Shell.cmd("setenforce 0").exec()
            mainHandler.postDelayed(this::startInstallation, SELINUX_SETTLE_MS)
        })
    }

    private fun onInstallationProgressUpdate(progress: Float, partition: String) {
        updateInstallation(Consumer { s ->
            s.partition = partition
            s.progress = progress
        })
    }

    private fun onInstallationError(error: InstallationStep, errorContent: String?) {
        updateInstallation(Consumer { s ->
            s.step = (if (((error == InstallationStep.ERROR_SELINUX) && !session.isRoot())) InstallationStep.ERROR_SELINUX_ROOTLESS else error)
            s.errorText = (if ((errorContent == null)) "" else errorContent)
        })
    }

    companion object {
        private const val TAG: String = "HomeViewModel"

        private const val DSU_PACKAGE: String = "com.android.dynsystem"

        private const val READ_LOGS: String = "android.permission.READ_LOGS"

        private const val USERDATA_ERROR_MS: Long = 5000L

        private const val FILE_ERROR_MS: Long = 2000L

        private const val SELINUX_SETTLE_MS: Long = 5000L

        private const val GRANT_RECHECK_MS: Long = 4000L

        private val SUPPORTED_EXTENSIONS: List<String> = Arrays.asList("gz", "xz", "img", "gzip")

        // A value too large for a long is certainly above any limit
        private fun exceeds(digits: String, limit: Long): Boolean {
            try {
                return (java.lang.Long.parseLong(digits) > limit)
            } catch (e: NumberFormatException) {
                return true
            }
        }
    }
}
