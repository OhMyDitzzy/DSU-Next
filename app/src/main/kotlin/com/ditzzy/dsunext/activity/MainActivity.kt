package com.ditzzy.dsunext.activity

import java.util.function.Consumer
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.ViewModelProvider
import com.ditzzy.dsunext.BuildConfig
import com.ditzzy.dsunext.DsuNextApp
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.core.AppPrefs
import com.ditzzy.dsunext.databinding.ActivityMainBinding
import com.ditzzy.dsunext.databinding.RowDialogItemBinding
import com.ditzzy.dsunext.databinding.RowGsiNameBinding
import com.ditzzy.dsunext.databinding.SheetLogsBinding
import com.ditzzy.dsunext.model.DsuConstants
import com.ditzzy.dsunext.model.OperationMode
import com.ditzzy.dsunext.model.Session
import com.ditzzy.dsunext.service.PrivilegedProvider
import com.ditzzy.dsunext.service.PrivilegedRootService
import com.ditzzy.dsunext.service.PrivilegedService
import com.ditzzy.dsunext.service.PrivilegedSystemService
import com.ditzzy.dsunext.ui.InputRow
import com.ditzzy.dsunext.ui.InsetsUtils
import com.ditzzy.dsunext.preparation.InstallationStep
import com.ditzzy.dsunext.ui.InstallationCardRenderer
import com.ditzzy.dsunext.ui.ListRow
import com.ditzzy.dsunext.ui.SegmentedGroup
import com.ditzzy.dsunext.ui.UiMessage
import com.ditzzy.dsunext.util.OperationModeUtils
import com.ditzzy.dsunext.viewmodel.AdditionalCard
import com.ditzzy.dsunext.viewmodel.HomeViewModel
import com.ditzzy.dsunext.viewmodel.ImageSizeCardState
import com.ditzzy.dsunext.viewmodel.InstallationCardState
import com.ditzzy.dsunext.viewmodel.SheetDisplay
import com.ditzzy.dsunext.viewmodel.UserDataCardState
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

open class MainActivity : AppCompatActivity(), Shizuku.OnRequestPermissionResultListener {
    private var binding: ActivityMainBinding? = null

    private var viewModel: HomeViewModel? = null

    private var session: Session? = null

    private var appPrefs: AppPrefs? = null

    private var cardRenderer: InstallationCardRenderer? = null

    private var rowFile: ListRow? = null

    private var rowUserdata: ListRow? = null

    private var rowImageSize: ListRow? = null

    private var inputUserdata: InputRow? = null

    private var inputImageSize: InputRow? = null

    private var currentInstallation: InstallationCardState = InstallationCardState()

    private var checksPassed: Boolean = false

    private var currentDialog: AlertDialog? = null

    // Only present in the confirmation dialog while Multi-GSI is active
    private var confirmNameInput: TextInputEditText? = null

    private var logsSheet: BottomSheetDialog? = null

    private var logsBinding: SheetLogsBinding? = null

    private var suppressDismissCallback: Boolean = false

    private var shouldCheckShizuku: Boolean = false

    private var shizukuListenersAdded: Boolean = false

    private var pickFile: ActivityResultLauncher<Array<String>>? = null

    private var pickFolder: ActivityResultLauncher<Uri?>? = null

    private var createLogFile: ActivityResultLauncher<String>? = null

    private var agreement: ActivityResultLauncher<Intent>? = null

    private val userServiceArgs: Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, PrivilegedService::class.java.getName())).daemon(false).processNameSuffix("service").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)

    private val binderReceivedListener: Shizuku.OnBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (!OperationModeUtils.isShizukuPermissionGranted(this)) {
            askShizukuPermission()
            return@OnBinderReceivedListener
        }
        bindShizuku()
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(getLayoutInflater())
        setContentView(binding!!.getRoot())
        val app: DsuNextApp = DsuNextApp.from(this)
        session = app.getSession()
        appPrefs = app.getAppPrefs()
        viewModel = ViewModelProvider(this).`get`(HomeViewModel::class.java)
        registerLaunchers()
        setupToolbar()
        buildInstallationRows()
        buildToolsRows()
        setupStaticClicks()
        applyInsets()
        cardRenderer = InstallationCardRenderer(this, binding!!, viewModel!!, object : InstallationCardRenderer.Host {
            override fun showLogs() {
                viewModel!!.showSheet(SheetDisplay.VIEW_LOGS)
            }

            override fun showCommands() {
                startActivity(Intent(this@MainActivity, AdbActivity::class.java))
            }
        })
        observeViewModel()
        resolveOperationMode()
        if (((savedInstanceState == null) && !appPrefs!!.isUserAgreementAccepted())) {
            agreement!!.launch(AgreementActivity.createRequiredIntent(this))
        }
    }

    protected override fun onResume() {
        super.onResume()
        viewModel!!.refreshPreferences()
        viewModel!!.verifyInstalledState()
        // Multi-GSI may have been switched in the settings, the card and sections depend on it
        cardRenderer!!.render(currentInstallation)
        renderVisibility()
    }

    private fun registerLaunchers() {
        pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument(), { uri ->
            if ((uri != null)) {
                viewModel!!.onFileSelectionResult(uri)
            }
        })
        pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree(), { uri ->
            if ((uri != null)) {
                viewModel!!.takeUriPermission(uri)
            }
        })
        createLogFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), { uri ->
            if ((uri != null)) {
                viewModel!!.saveLogs(uri)
            }
        })
        agreement = registerForActivityResult(ActivityResultContracts.StartActivityForResult(), { result ->
            if ((result.resultCode == RESULT_OK)) {
                viewModel!!.initialChecks()
            } else {
                finishAffinity()
            }
        })
    }

    private fun setupToolbar() {
        binding!!.toolbar.inflateMenu(R.menu.menu_home)
        binding!!.toolbar.setOnMenuItemClickListener(callback6@ { item ->
            if ((item.getItemId() == R.id.action_settings)) {
                startActivity(Intent(this, SettingsActivity::class.java))
                return@callback6 true
            }
            return@callback6 false
        })
    }

    private fun buildInstallationRows() {
        val group: LinearLayout = binding!!.installationGroup
        rowFile = ListRow.create(group).icon(R.drawable.ic_file).title(R.string.select_file).supporting(R.string.select_gsi_info).trailingIcon(R.drawable.ic_folder_open).onClick({ v ->
            pickFile!!.launch(IMAGE_MIME_TYPES)
        })
        rowUserdata = ListRow.create(group).icon(R.drawable.ic_storage).title(R.string.userdata_size).supporting(R.string.userdata_size_info).toggle(false, Consumer(viewModel!!::onUserdataToggled))
        inputUserdata = InputRow.create(group, R.string.userdata_size, "GB").onTextChanged(Consumer(viewModel!!::updateUserdataSize))
        rowImageSize = ListRow.create(group).icon(R.drawable.ic_description).title(R.string.image_size).supporting(R.string.image_size_info).toggle(false, Consumer(viewModel!!::onImageSizeToggled))
        inputImageSize = InputRow.create(group, R.string.image_size, "b").onTextChanged(Consumer(viewModel!!::onImageSizeInput))
        SegmentedGroup.apply(group)
    }

    private fun buildToolsRows() {
        val group: LinearLayout = binding!!.toolsGroup
        ListRow.create(group).icon(R.drawable.ic_storage).title(R.string.multi_gsi).supporting(R.string.multi_gsi_description).trailingIcon(R.drawable.ic_chevron_right).onClick({ v ->
            startActivity(Intent(this, MultiGsiActivity::class.java))
        })
        ListRow.create(group).icon(R.drawable.ic_phone).title(R.string.treble_check).supporting(R.string.treble_check_description).trailingIcon(R.drawable.ic_chevron_right).onClick({ v ->
            startActivity(Intent(this, TrebleActivity::class.java))
        })
        ListRow.create(group).icon(R.drawable.ic_edit).title(R.string.edit_gsi).supporting(R.string.edit_gsi_description).trailingIcon(R.drawable.ic_chevron_right).onClick({ v ->
            startActivity(Intent(this, EditGsiActivity::class.java))
        })
        SegmentedGroup.apply(group)
    }

    private fun setupStaticClicks() {
        binding!!.fabInstall.setOnClickListener({ v ->
            viewModel!!.onClickInstall()
        })
        binding!!.infoDocs.setOnClickListener({ v ->
            openUrl(DSU_DOCS)
        })
        binding!!.infoLearn.setOnClickListener({ v ->
            openUrl(DSU_LEARN_MORE)
        })
    }

    private fun applyInsets() {
        InsetsUtils.padBottomAndSides(binding!!.scrollView)
        InsetsUtils.marginBottomAndSides(binding!!.fabInstall)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: RuntimeException) {
            Log.w(TAG, ("No app can open " + url), e)
        }
    }

    private fun observeViewModel() {
        viewModel!!.getAdditionalCard().observe(this, { card ->
            renderBanner(card)
            renderVisibility()
        })
        viewModel!!.getPassedInitialChecks().observe(this, { passed ->
            checksPassed = passed
            renderVisibility()
        })
        viewModel!!.getInstallationCard().observe(this, { state ->
            currentInstallation = state
            renderFileRow(state)
            cardRenderer!!.render(state)
            renderVisibility()
        })
        viewModel!!.getUserDataCard().observe(this, this::renderUserData)
        viewModel!!.getImageSizeCard().observe(this, this::renderImageSize)
        viewModel!!.getSheet().observe(this, this::renderSheet)
        viewModel!!.getLogs().observe(this, this::renderLogs)
        viewModel!!.getKeepScreenOn().observe(this, { keepOn ->
            if (keepOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        })
        viewModel!!.getMessages().observe(this, callback18@ { event ->
            if ((event == null)) {
                return@callback18
            }
            val message: UiMessage? = event.getContentIfNotHandled()
            if ((message != null)) {
                Toast.makeText(this, message!!.resolve(this), Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun renderVisibility() {
        val busy: Boolean = currentInstallation.isBusy()
        val settled: Boolean = currentInstallation.isSettled()
        val multiGsi: Boolean = viewModel!!.isMultiGsiActive()
        val showSelection: Boolean = (checksPassed && (!currentInstallation.isInstalling() || (settled && multiGsi)))
        binding!!.installationSection.setVisibility((if (showSelection) View.VISIBLE else View.GONE))
        binding!!.toolsSection.setVisibility((if (busy) View.GONE else View.VISIBLE))
        val finishedInDialog: Boolean = (multiGsi && (currentInstallation.step == InstallationStep.INSTALL_SUCCESS_REBOOT_DYN_OS))
        binding!!.progressCard.setVisibility((if ((currentInstallation.isInstalling() && !finishedInDialog)) View.VISIBLE else View.GONE))
        if ((showSelection && currentInstallation.installable)) {
            binding!!.fabInstall.show()
        } else {
            binding!!.fabInstall.hide()
        }
    }

    private fun renderFileRow(state: InstallationCardState) {
        val hasFile: Boolean = !state.fileName.isEmpty()
        rowFile!!.title((if (hasFile) state.fileName else getString(R.string.select_file)))
        rowFile!!.supporting((if (state.fileError) getString(R.string.file_unsupported) else getString(R.string.select_gsi_info)))
        rowFile!!.enabled(state.fileSelectionEnabled)
        if (hasFile) {
            rowFile!!.hideTrailingIcon().trailingButton(R.drawable.ic_delete, getString(R.string.clear_selected_file), { v ->
                viewModel!!.onClickClear()
            })
        } else {
            rowFile!!.hideTrailingButton().trailingIcon(R.drawable.ic_folder_open)
        }
    }

    private fun renderUserData(state: UserDataCardState) {
        rowUserdata!!.checked(state.selected)
        inputUserdata!!.setVisible(state.selected)
        inputUserdata!!.setText(state.text)
        inputUserdata!!.setError((if (state.error) getString(R.string.allowed_userdata_allocation, state.maximumAllowed) else null))
        SegmentedGroup.apply(binding!!.installationGroup)
    }

    private fun renderImageSize(state: ImageSizeCardState) {
        rowImageSize!!.checked(state.selected)
        inputImageSize!!.setVisible(state.selected)
        inputImageSize!!.setText(state.text)
        SegmentedGroup.apply(binding!!.installationGroup)
    }

    private fun renderBanner(card: AdditionalCard) {
        if ((card == AdditionalCard.NONE)) {
            binding!!.bannerCard.setVisibility(View.GONE)
            return
        }
        binding!!.bannerCard.setVisibility(View.VISIBLE)
        when (card) {
            AdditionalCard.NO_DYNAMIC_PARTITIONS -> {
                bindBanner(R.drawable.ic_warning, R.string.unsupported, getString(R.string.device_unsupported_description), true, R.string.continue_anyway, Runnable(viewModel!!::overrideDynamicPartitionCheck), 0, null, false)
            }
            AdditionalCard.UNAVAILABLE_STORAGE -> {
                bindBanner(R.drawable.ic_warning, R.string.storage_warning, getString(R.string.storage_warning_description, viewModel!!.getAllocPercentageInt()), true, R.string.continue_anyway, Runnable(viewModel!!::overrideUnavailableStorage), 0, null, false)
            }
            AdditionalCard.SETUP_STORAGE -> {
                bindBanner(R.drawable.ic_folder_open, R.string.setup_storage, getString(R.string.setup_storage_description), false, R.string.setup, Runnable {
                    pickFolder!!.launch(null)
                }, 0, null, false)
            }
            AdditionalCard.MISSING_READ_LOGS_PERMISSION -> {
                bindBanner(R.drawable.ic_info, R.string.missing_permission, getString(R.string.missing_permission_description), false, R.string.grant, Runnable(viewModel!!::grantReadLogs), R.string.refuse, Runnable(viewModel!!::refuseReadLogs), false)
            }
            AdditionalCard.GRANTING_READ_LOGS_PERMISSION -> {
                bindBanner(R.drawable.ic_info, R.string.missing_permission, getString(R.string.granting_permission), false, 0, null, 0, null, true)
            }
            else -> {
                binding!!.bannerCard.setVisibility(View.GONE)
            }
        }
    }

    private fun bindBanner(@DrawableRes icon: Int, @StringRes title: Int, text: String, isError: Boolean, @StringRes primaryLabel: Int, primary: Runnable?, @StringRes secondaryLabel: Int, secondary: Runnable?, loading: Boolean) {
        val container: Int = MaterialColors.getColor(binding!!.bannerCard, (if (isError) com.google.android.material.R.attr.colorErrorContainer else com.google.android.material.R.attr.colorSecondaryContainer))
        val onContainer: Int = MaterialColors.getColor(binding!!.bannerCard, (if (isError) com.google.android.material.R.attr.colorOnErrorContainer else com.google.android.material.R.attr.colorOnSecondaryContainer))
        binding!!.bannerCard.setCardBackgroundColor(container)
        binding!!.bannerIcon.setImageResource(icon)
        ImageViewCompat.setImageTintList(binding!!.bannerIcon, ColorStateList.valueOf(onContainer))
        binding!!.bannerTitle.setText(title)
        binding!!.bannerTitle.setTextColor(onContainer)
        binding!!.bannerText.setText(text)
        binding!!.bannerText.setTextColor(onContainer)
        binding!!.bannerLoading.setVisibility((if (loading) View.VISIBLE else View.GONE))
        bindBannerButton(binding!!.bannerPrimary, primaryLabel, primary)
        bindBannerButton(binding!!.bannerSecondary, secondaryLabel, secondary)
        binding!!.bannerButtons.setVisibility((if (((primaryLabel == 0) && (secondaryLabel == 0))) View.GONE else View.VISIBLE))
    }

    private fun renderSheet(display: SheetDisplay) {
        dismissCurrentDialog()
        when (display) {
            SheetDisplay.IMAGE_SIZE_WARNING -> {
                showDialog(MaterialAlertDialogBuilder(this).setIcon(R.drawable.ic_warning).setTitle(R.string.dialog_image_size).setMessage(R.string.dialog_image_size_description).setPositiveButton(R.string.set_anyway, { d, w ->
                    viewModel!!.onImageSizeWarningConfirmed()
                }).setNegativeButton(R.string.cancel, { d, w ->
                    viewModel!!.onImageSizeWarningCancelled()
                }).setOnCancelListener({ d ->
                    viewModel!!.onImageSizeWarningCancelled()
                }).create())
            }
            SheetDisplay.CONFIRM_INSTALLATION -> {
                showDialog(MaterialAlertDialogBuilder(this).setIcon(R.drawable.ic_download).setTitle(R.string.proceed_installation).setView(buildConfirmContent()).setPositiveButton(R.string.proceed, { d, w ->
                    viewModel!!.onConfirmInstallationSheet(readConfirmName())
                }).setNegativeButton(R.string.cancel, { d, w ->
                    viewModel!!.dismissSheet()
                }).setOnCancelListener({ d ->
                    viewModel!!.dismissSheet()
                }).create())
            }
            SheetDisplay.CANCEL_INSTALLATION -> {
                showDialog(MaterialAlertDialogBuilder(this).setIcon(R.drawable.ic_warning).setTitle(R.string.cancel_installation_question).setMessage(R.string.cancel_installation_description).setPositiveButton(R.string.yes, { d, w ->
                    viewModel!!.onClickCancelInstallationButton()
                }).setNegativeButton(R.string.no, { d, w ->
                    viewModel!!.dismissSheet()
                }).setOnCancelListener({ d ->
                    viewModel!!.dismissSheet()
                }).create())
            }
            SheetDisplay.DISCARD_DSU -> {
                showDialog(MaterialAlertDialogBuilder(this).setIcon(R.drawable.ic_delete).setTitle(R.string.discard_dsu_question).setMessage(R.string.dsu_already_installed_warning).setPositiveButton(R.string.discard, { d, w ->
                    viewModel!!.onClickDiscardGsi()
                }).setNegativeButton(R.string.cancel, { d, w ->
                    viewModel!!.dismissSheet()
                }).setOnCancelListener({ d ->
                    viewModel!!.dismissSheet()
                }).create())
            }
            SheetDisplay.INSTALL_FINISHED -> {
                showDialog(MaterialAlertDialogBuilder(this).setIcon(R.drawable.ic_download).setTitle(R.string.installation_finished).setMessage(R.string.multi_gsi_installation_finished_description).setPositiveButton(R.string.reboot_into_dsu, { d, w ->
                    viewModel!!.onClickRebootToDynOs()
                }).setNegativeButton(R.string.discard, { d, w ->
                    viewModel!!.showSheet(SheetDisplay.DISCARD_DSU)
                }).setNeutralButton(R.string.close, { d, w ->
                    viewModel!!.dismissSheet()
                }).setOnCancelListener({ d ->
                    viewModel!!.dismissSheet()
                }).create())
            }
            SheetDisplay.VIEW_LOGS -> {
                showLogsSheet()
            }
            SheetDisplay.NONE -> {

            }
        }
    }

    private fun buildConfirmContent(): View {
        val content: LinearLayout = LinearLayout(this)
        content.setOrientation(LinearLayout.VERTICAL)
        val horizontal: Int = getResources().getDimensionPixelSize(R.dimen.card_padding)
        content.setPadding(horizontal, 0, horizontal, 0)
        addDialogItem(content, R.drawable.ic_file, R.string.selected_file, viewModel!!.getSelectedFilename())
        addDialogItem(content, R.drawable.ic_storage, R.string.userdata_size, (viewModel!!.getConfirmUserdataGb() + "GB"))
        val imageSize: Long = viewModel!!.getConfirmImageSize()
        if ((imageSize != DsuConstants.DEFAULT_IMAGE_SIZE)) {
            addDialogItem(content, R.drawable.ic_description, R.string.image_size, ((imageSize).toString() + "b"))
        }
        confirmNameInput = null
        if (viewModel!!.isMultiGsiActive()) {
            val name: RowGsiNameBinding = RowGsiNameBinding.inflate(LayoutInflater.from(this), content, false)
            name.gsiNameEdit.setText(viewModel!!.getDefaultGsiName())
            confirmNameInput = name.gsiNameEdit
            content.addView(name.getRoot())
        }
        return content
    }

    private fun readConfirmName(): String {
        return (if (((confirmNameInput == null) || (confirmNameInput!!.getText() == null))) "" else confirmNameInput!!.getText()!!.toString())
    }

    private fun addDialogItem(parent: LinearLayout, @DrawableRes icon: Int, @StringRes label: Int, value: String) {
        val item: RowDialogItemBinding = RowDialogItemBinding.inflate(LayoutInflater.from(this), parent, false)
        item.dialogItemIcon.setImageResource(icon)
        item.dialogItemLabel.setText(label)
        item.dialogItemValue.setText(value)
        parent.addView(item.getRoot())
    }

    private fun showDialog(dialog: AlertDialog) {
        currentDialog = dialog
        dialog.setOnDismissListener({ d ->
            if ((currentDialog == dialog)) {
                currentDialog = null
            }
        })
        dialog.show()
    }

    private fun dismissCurrentDialog() {
        suppressDismissCallback = true
        if ((currentDialog != null)) {
            currentDialog!!.dismiss()
            currentDialog = null
        }
        if ((logsSheet != null)) {
            logsSheet!!.dismiss()
            logsSheet = null
            logsBinding = null
        }
        suppressDismissCallback = false
    }

    private fun showLogsSheet() {
        logsBinding = SheetLogsBinding.inflate(getLayoutInflater())
        val sheet: BottomSheetDialog = BottomSheetDialog(this)
        logsSheet = sheet
        sheet.setContentView(logsBinding!!.getRoot())
        sheet.setOnDismissListener({ d ->
            if ((logsSheet == sheet)) {
                logsSheet = null
                logsBinding = null
            }
            if (!suppressDismissCallback) {
                viewModel!!.dismissSheet()
            }
        })
        logsBinding!!.logsSave.setOnClickListener({ v ->
            createLogFile!!.launch("logs")
        })
        val current: String? = viewModel!!.getLogs().getValue()
        renderLogs((if ((current == null)) "" else current!!))
        sheet.show()
    }

    private fun renderLogs(logs: String) {
        if ((logsBinding == null)) {
            return
        }
        logsBinding!!.logsText.setText(logs)
        logsBinding!!.logsScroll.post({
            if ((logsBinding != null)) {
                logsBinding!!.logsScroll.fullScroll(View.FOCUS_DOWN)
            }
        })
    }

    private fun resolveOperationMode() {
        Shell.getShell(callback43@ { shell ->
            if (isDestroyed()) {
                return@callback43
            }
            if (!session!!.isOperationModeResolved()) {
                setupSessionOperationMode()
                setupService()
            } else {
                viewModel!!.onOperationModeChanged()
            }
        })
    }

    private fun setupSessionOperationMode() {
        val mode: OperationMode = OperationModeUtils.getOperationMode(application, shouldCheckShizuku)
        session!!.setOperationMode(mode)
        session!!.setOperationModeResolved(true)
        Log.d(TAG, ("Operation mode is: " + mode))
        viewModel!!.onOperationModeChanged()
    }

    private fun setupService() {
        if (session!!.isRoot()) {
            RootService.bind(Intent(this, PrivilegedRootService::class.java), PrivilegedProvider.getConnection())
            return
        }
        if ((session!!.getOperationMode() == OperationMode.SYSTEM)) {
            getApplicationContext().bindService(Intent(this, PrivilegedSystemService::class.java), PrivilegedProvider.getConnection(), Context.BIND_AUTO_CREATE)
            return
        }
        addShizukuListeners()
    }

    private fun addShizukuListeners() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addRequestPermissionResultListener(this)
        shizukuListenersAdded = true
    }

    private fun removeShizukuListeners() {
        Shizuku.removeRequestPermissionResultListener(this)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        shizukuListenersAdded = false
    }

    private fun askShizukuPermission() {
        if ((Shizuku.isPreV11() || (Shizuku.getVersion() < 11))) {
            requestPermissions(arrayOf(ShizukuProvider.PERMISSION), SHIZUKU_REQUEST_CODE)
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (((grantResult == PackageManager.PERMISSION_GRANTED) && (requestCode == SHIZUKU_REQUEST_CODE))) {
            bindShizuku()
        }
        Shizuku.removeRequestPermissionResultListener(this)
    }

    private fun bindShizuku() {
        Shizuku.bindUserService(userServiceArgs, PrivilegedProvider.getConnection())
        shouldCheckShizuku = true
        setupSessionOperationMode()
    }

    protected override fun onDestroy() {
        super.onDestroy()
        dismissCurrentDialog()
        binding = null
        if (isChangingConfigurations()) {
            return
        }
        if (shizukuListenersAdded) {
            removeShizukuListeners()
        }
        when (session!!.getOperationMode()) {
            OperationMode.ROOT, OperationMode.SYSTEM_AND_ROOT -> {
                RootService.unbind(PrivilegedProvider.getConnection())
            }
            OperationMode.SYSTEM -> {
                getApplicationContext().unbindService(PrivilegedProvider.getConnection())
            }
            OperationMode.SHIZUKU -> {
                try {
                    Shizuku.unbindUserService(userServiceArgs, PrivilegedProvider.getConnection(), true)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Unable to unbind the Shizuku service.", e)
                }
            }
            else -> {

            }
        }
        // A new launch has to bind the services again
        session!!.setOperationModeResolved(false)
    }

    companion object {
        private const val TAG: String = "MainActivity"

        private const val SHIZUKU_REQUEST_CODE: Int = 1000

        private const val DSU_LEARN_MORE: String = "https://developer.android.com/topic/dsu"

        private const val DSU_DOCS: String = "https://source.android.com/devices/tech/ota/dynamic-system-updates"

        private val IMAGE_MIME_TYPES: Array<String> = arrayOf("application/gzip", "application/x-gzip", "application/x-xz", "application/zip", "application/octet-stream")

        private fun bindBannerButton(button: MaterialButton, @StringRes label: Int, action: Runnable?) {
            if (((label == 0) || (action == null))) {
                button.setVisibility(View.GONE)
                return
            }
            button.setText(label)
            button.setVisibility(View.VISIBLE)
            button.setOnClickListener({ v ->
                action!!.run()
            })
        }
    }
}
