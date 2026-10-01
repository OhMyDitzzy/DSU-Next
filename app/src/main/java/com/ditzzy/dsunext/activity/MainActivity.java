package com.ditzzy.dsunext.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.ImageViewCompat;
import androidx.lifecycle.ViewModelProvider;

import com.ditzzy.dsunext.BuildConfig;
import com.ditzzy.dsunext.DsuNextApp;
import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.core.AppPrefs;
import com.ditzzy.dsunext.databinding.ActivityMainBinding;
import com.ditzzy.dsunext.databinding.RowDialogItemBinding;
import com.ditzzy.dsunext.databinding.SheetLogsBinding;
import com.ditzzy.dsunext.model.DsuConstants;
import com.ditzzy.dsunext.model.OperationMode;
import com.ditzzy.dsunext.model.Session;
import com.ditzzy.dsunext.service.PrivilegedProvider;
import com.ditzzy.dsunext.service.PrivilegedRootService;
import com.ditzzy.dsunext.service.PrivilegedService;
import com.ditzzy.dsunext.service.PrivilegedSystemService;
import com.ditzzy.dsunext.ui.InputRow;
import com.ditzzy.dsunext.ui.InsetsUtils;
import com.ditzzy.dsunext.ui.InstallationCardRenderer;
import com.ditzzy.dsunext.ui.ListRow;
import com.ditzzy.dsunext.ui.SegmentedGroup;
import com.ditzzy.dsunext.ui.UiMessage;
import com.ditzzy.dsunext.util.OperationModeUtils;
import com.ditzzy.dsunext.viewmodel.AdditionalCard;
import com.ditzzy.dsunext.viewmodel.HomeViewModel;
import com.ditzzy.dsunext.viewmodel.ImageSizeCardState;
import com.ditzzy.dsunext.viewmodel.InstallationCardState;
import com.ditzzy.dsunext.viewmodel.SheetDisplay;
import com.ditzzy.dsunext.viewmodel.UserDataCardState;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

public class MainActivity extends AppCompatActivity implements Shizuku.OnRequestPermissionResultListener {

    private static final String TAG = "MainActivity";
    private static final int SHIZUKU_REQUEST_CODE = 1000;
    private static final String DSU_LEARN_MORE = "https://developer.android.com/topic/dsu";
    private static final String DSU_DOCS = "https://source.android.com/devices/tech/ota/dynamic-system-updates";
    private static final String[] IMAGE_MIME_TYPES = {
            "application/gzip",
            "application/x-gzip",
            "application/x-xz",
            "application/zip",
            "application/octet-stream"
    };

    private ActivityMainBinding binding;
    private HomeViewModel viewModel;
    private Session session;
    private AppPrefs appPrefs;
    private InstallationCardRenderer cardRenderer;

    private ListRow rowFile;
    private ListRow rowUserdata;
    private ListRow rowImageSize;
    private InputRow inputUserdata;
    private InputRow inputImageSize;

    private InstallationCardState currentInstallation = new InstallationCardState();
    private boolean checksPassed = false;

    private AlertDialog currentDialog;
    private BottomSheetDialog logsSheet;
    private SheetLogsBinding logsBinding;
    private boolean suppressDismissCallback = false;

    private boolean shouldCheckShizuku = false;
    private boolean shizukuListenersAdded = false;

    private ActivityResultLauncher<String[]> pickFile;
    private ActivityResultLauncher<Uri> pickFolder;
    private ActivityResultLauncher<String> createLogFile;
    private ActivityResultLauncher<Intent> agreement;

    private final Shizuku.UserServiceArgs userServiceArgs = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, PrivilegedService.class.getName()))
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE);

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        if (!OperationModeUtils.isShizukuPermissionGranted(this)) {
            askShizukuPermission();
            return;
        }
        bindShizuku();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        DsuNextApp app = DsuNextApp.from(this);
        session = app.getSession();
        appPrefs = app.getAppPrefs();
        viewModel = new ViewModelProvider(this).get(HomeViewModel.class);

        registerLaunchers();
        setupToolbar();
        buildInstallationRows();
        setupStaticClicks();
        applyInsets();

        cardRenderer = new InstallationCardRenderer(this, binding, viewModel, new InstallationCardRenderer.Host() {
            @Override
            public void showLogs() {
                viewModel.showSheet(SheetDisplay.VIEW_LOGS);
            }

            @Override
            public void showCommands() {
                startActivity(new Intent(MainActivity.this, AdbActivity.class));
            }
        });

        observeViewModel();
        resolveOperationMode();

        if (savedInstanceState == null && !appPrefs.isUserAgreementAccepted()) {
            agreement.launch(AgreementActivity.createRequiredIntent(this));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.refreshPreferences();
    }

    private void registerLaunchers() {
        pickFile = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                viewModel.onFileSelectionResult(uri);
            }
        });
        pickFolder = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri != null) {
                viewModel.takeUriPermission(uri);
            }
        });
        createLogFile = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
            if (uri != null) {
                viewModel.saveLogs(uri);
            }
        });
        agreement = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
                viewModel.initialChecks();
            } else {
                finishAffinity();
            }
        });
    }

    private void setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.menu_home);
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });
    }

    private void buildInstallationRows() {
        LinearLayout group = binding.installationGroup;

        rowFile = ListRow.create(group)
                .icon(R.drawable.ic_file)
                .title(R.string.select_file)
                .supporting(R.string.select_gsi_info)
                .trailingIcon(R.drawable.ic_folder_open)
                .onClick(v -> pickFile.launch(IMAGE_MIME_TYPES));

        rowUserdata = ListRow.create(group)
                .icon(R.drawable.ic_storage)
                .title(R.string.userdata_size)
                .supporting(R.string.userdata_size_info)
                .toggle(false, viewModel::onUserdataToggled);
        inputUserdata = InputRow.create(group, R.string.userdata_size, "GB")
                .onTextChanged(viewModel::updateUserdataSize);

        rowImageSize = ListRow.create(group)
                .icon(R.drawable.ic_description)
                .title(R.string.image_size)
                .supporting(R.string.image_size_info)
                .toggle(false, viewModel::onImageSizeToggled);
        inputImageSize = InputRow.create(group, R.string.image_size, "b")
                .onTextChanged(viewModel::onImageSizeInput);

        SegmentedGroup.apply(group);
    }

    private void setupStaticClicks() {
        binding.fabInstall.setOnClickListener(v -> viewModel.onClickInstall());
        binding.infoDocs.setOnClickListener(v -> openUrl(DSU_DOCS));
        binding.infoLearn.setOnClickListener(v -> openUrl(DSU_LEARN_MORE));
    }

    private void applyInsets() {
        InsetsUtils.padBottomAndSides(binding.scrollView);
        InsetsUtils.marginBottomAndSides(binding.fabInstall);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Log.w(TAG, "No app can open " + url, e);
        }
    }

    private void observeViewModel() {
        viewModel.getAdditionalCard().observe(this, card -> {
            renderBanner(card);
            renderVisibility();
        });
        viewModel.getPassedInitialChecks().observe(this, passed -> {
            checksPassed = passed;
            renderVisibility();
        });
        viewModel.getInstallationCard().observe(this, state -> {
            currentInstallation = state;
            renderFileRow(state);
            cardRenderer.render(state);
            renderVisibility();
        });
        viewModel.getUserDataCard().observe(this, this::renderUserData);
        viewModel.getImageSizeCard().observe(this, this::renderImageSize);
        viewModel.getSheet().observe(this, this::renderSheet);
        viewModel.getLogs().observe(this, this::renderLogs);
        viewModel.getKeepScreenOn().observe(this, keepOn -> {
            if (keepOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
        viewModel.getMessages().observe(this, event -> {
            if (event == null) {
                return;
            }
            UiMessage message = event.getContentIfNotHandled();
            if (message != null) {
                Toast.makeText(this, message.resolve(this), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void renderVisibility() {
        boolean installing = currentInstallation.isInstalling();
        boolean showSelection = checksPassed && !installing;
        binding.installationSection.setVisibility(showSelection ? View.VISIBLE : View.GONE);

        if (showSelection && currentInstallation.installable) {
            binding.fabInstall.show();
        } else {
            binding.fabInstall.hide();
        }
    }

    private void renderFileRow(InstallationCardState state) {
        boolean hasFile = !state.fileName.isEmpty();
        rowFile.title(hasFile ? state.fileName : getString(R.string.select_file));
        rowFile.supporting(state.fileError ? getString(R.string.file_unsupported) : getString(R.string.select_gsi_info));
        rowFile.enabled(state.fileSelectionEnabled);
        if (hasFile) {
            rowFile.hideTrailingIcon().trailingButton(
                    R.drawable.ic_delete, getString(R.string.clear_selected_file), v -> viewModel.onClickClear());
        } else {
            rowFile.hideTrailingButton().trailingIcon(R.drawable.ic_folder_open);
        }
    }

    private void renderUserData(UserDataCardState state) {
        rowUserdata.checked(state.selected);
        inputUserdata.setVisible(state.selected);
        inputUserdata.setText(state.text);
        inputUserdata.setError(state.error
                ? getString(R.string.allowed_userdata_allocation, state.maximumAllowed)
                : null);
        SegmentedGroup.apply(binding.installationGroup);
    }

    private void renderImageSize(ImageSizeCardState state) {
        rowImageSize.checked(state.selected);
        inputImageSize.setVisible(state.selected);
        inputImageSize.setText(state.text);
        SegmentedGroup.apply(binding.installationGroup);
    }

    private void renderBanner(AdditionalCard card) {
        if (card == AdditionalCard.NONE) {
            binding.bannerCard.setVisibility(View.GONE);
            return;
        }
        binding.bannerCard.setVisibility(View.VISIBLE);

        switch (card) {
            case NO_DYNAMIC_PARTITIONS:
                bindBanner(R.drawable.ic_warning, R.string.unsupported,
                        getString(R.string.device_unsupported_description), true,
                        R.string.continue_anyway, viewModel::overrideDynamicPartitionCheck, 0, null, false);
                break;
            case UNAVAILABLE_STORAGE:
                bindBanner(R.drawable.ic_warning, R.string.storage_warning,
                        getString(R.string.storage_warning_description, viewModel.getAllocPercentageInt()), true,
                        R.string.continue_anyway, viewModel::overrideUnavailableStorage, 0, null, false);
                break;
            case SETUP_STORAGE:
                bindBanner(R.drawable.ic_folder_open, R.string.setup_storage,
                        getString(R.string.setup_storage_description), false,
                        R.string.setup, () -> pickFolder.launch(null), 0, null, false);
                break;
            case MISSING_READ_LOGS_PERMISSION:
                bindBanner(R.drawable.ic_info, R.string.missing_permission,
                        getString(R.string.missing_permission_description), false,
                        R.string.grant, viewModel::grantReadLogs, R.string.refuse, viewModel::refuseReadLogs, false);
                break;
            case GRANTING_READ_LOGS_PERMISSION:
                bindBanner(R.drawable.ic_info, R.string.missing_permission,
                        getString(R.string.granting_permission), false, 0, null, 0, null, true);
                break;
            default:
                binding.bannerCard.setVisibility(View.GONE);
        }
    }

    private void bindBanner(
            @DrawableRes int icon,
            @StringRes int title,
            String text,
            boolean isError,
            @StringRes int primaryLabel,
            @Nullable Runnable primary,
            @StringRes int secondaryLabel,
            @Nullable Runnable secondary,
            boolean loading) {
        int container = MaterialColors.getColor(binding.bannerCard, isError
                ? com.google.android.material.R.attr.colorErrorContainer
                : com.google.android.material.R.attr.colorSecondaryContainer);
        int onContainer = MaterialColors.getColor(binding.bannerCard, isError
                ? com.google.android.material.R.attr.colorOnErrorContainer
                : com.google.android.material.R.attr.colorOnSecondaryContainer);

        binding.bannerCard.setCardBackgroundColor(container);
        binding.bannerIcon.setImageResource(icon);
        ImageViewCompat.setImageTintList(binding.bannerIcon, ColorStateList.valueOf(onContainer));
        binding.bannerTitle.setText(title);
        binding.bannerTitle.setTextColor(onContainer);
        binding.bannerText.setText(text);
        binding.bannerText.setTextColor(onContainer);
        binding.bannerLoading.setVisibility(loading ? View.VISIBLE : View.GONE);

        bindBannerButton(binding.bannerPrimary, primaryLabel, primary);
        bindBannerButton(binding.bannerSecondary, secondaryLabel, secondary);
        binding.bannerButtons.setVisibility(primaryLabel == 0 && secondaryLabel == 0 ? View.GONE : View.VISIBLE);
    }

    private static void bindBannerButton(MaterialButton button, @StringRes int label, @Nullable Runnable action) {
        if (label == 0 || action == null) {
            button.setVisibility(View.GONE);
            return;
        }
        button.setText(label);
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> action.run());
    }

    private void renderSheet(SheetDisplay display) {
        dismissCurrentDialog();
        switch (display) {
            case IMAGE_SIZE_WARNING:
                showDialog(new MaterialAlertDialogBuilder(this)
                        .setIcon(R.drawable.ic_warning)
                        .setTitle(R.string.dialog_image_size)
                        .setMessage(R.string.dialog_image_size_description)
                        .setPositiveButton(R.string.set_anyway, (d, w) -> viewModel.onImageSizeWarningConfirmed())
                        .setNegativeButton(R.string.cancel, (d, w) -> viewModel.onImageSizeWarningCancelled())
                        .setOnCancelListener(d -> viewModel.onImageSizeWarningCancelled())
                        .create());
                break;
            case CONFIRM_INSTALLATION:
                showDialog(new MaterialAlertDialogBuilder(this)
                        .setIcon(R.drawable.ic_download)
                        .setTitle(R.string.proceed_installation)
                        .setView(buildConfirmContent())
                        .setPositiveButton(R.string.proceed, (d, w) -> viewModel.onConfirmInstallationSheet())
                        .setNegativeButton(R.string.cancel, (d, w) -> viewModel.dismissSheet())
                        .setOnCancelListener(d -> viewModel.dismissSheet())
                        .create());
                break;
            case CANCEL_INSTALLATION:
                showDialog(new MaterialAlertDialogBuilder(this)
                        .setIcon(R.drawable.ic_warning)
                        .setTitle(R.string.cancel_installation_question)
                        .setMessage(R.string.cancel_installation_description)
                        .setPositiveButton(R.string.yes, (d, w) -> viewModel.onClickCancelInstallationButton())
                        .setNegativeButton(R.string.no, (d, w) -> viewModel.dismissSheet())
                        .setOnCancelListener(d -> viewModel.dismissSheet())
                        .create());
                break;
            case DISCARD_DSU:
                showDialog(new MaterialAlertDialogBuilder(this)
                        .setIcon(R.drawable.ic_delete)
                        .setTitle(R.string.discard_dsu_question)
                        .setMessage(R.string.dsu_already_installed_warning)
                        .setPositiveButton(R.string.discard, (d, w) -> viewModel.onClickDiscardGsi())
                        .setNegativeButton(R.string.cancel, (d, w) -> viewModel.dismissSheet())
                        .setOnCancelListener(d -> viewModel.dismissSheet())
                        .create());
                break;
            case VIEW_LOGS:
                showLogsSheet();
                break;
            case NONE:
            default:
                break;
        }
    }

    private View buildConfirmContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontal = getResources().getDimensionPixelSize(R.dimen.card_padding);
        content.setPadding(horizontal, 0, horizontal, 0);

        addDialogItem(content, R.drawable.ic_file, R.string.selected_file, viewModel.getSelectedFilename());
        addDialogItem(content, R.drawable.ic_storage, R.string.userdata_size,
                viewModel.getConfirmUserdataGb() + "GB");
        long imageSize = viewModel.getConfirmImageSize();
        if (imageSize != DsuConstants.DEFAULT_IMAGE_SIZE) {
            addDialogItem(content, R.drawable.ic_description, R.string.image_size, imageSize + "b");
        }
        return content;
    }

    private void addDialogItem(LinearLayout parent, @DrawableRes int icon, @StringRes int label, String value) {
        RowDialogItemBinding item = RowDialogItemBinding.inflate(LayoutInflater.from(this), parent, false);
        item.dialogItemIcon.setImageResource(icon);
        item.dialogItemLabel.setText(label);
        item.dialogItemValue.setText(value);
        parent.addView(item.getRoot());
    }

    private void showDialog(AlertDialog dialog) {
        currentDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (currentDialog == dialog) {
                currentDialog = null;
            }
        });
        dialog.show();
    }

    private void dismissCurrentDialog() {
        suppressDismissCallback = true;
        if (currentDialog != null) {
            currentDialog.dismiss();
            currentDialog = null;
        }
        if (logsSheet != null) {
            logsSheet.dismiss();
            logsSheet = null;
            logsBinding = null;
        }
        suppressDismissCallback = false;
    }

    private void showLogsSheet() {
        logsBinding = SheetLogsBinding.inflate(getLayoutInflater());
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        logsSheet = sheet;
        sheet.setContentView(logsBinding.getRoot());
        sheet.setOnDismissListener(d -> {
            if (logsSheet == sheet) {
                logsSheet = null;
                logsBinding = null;
            }
            if (!suppressDismissCallback) {
                viewModel.dismissSheet();
            }
        });
        logsBinding.logsSave.setOnClickListener(v -> createLogFile.launch("logs"));
        String current = viewModel.getLogs().getValue();
        renderLogs(current == null ? "" : current);
        sheet.show();
    }

    private void renderLogs(String logs) {
        if (logsBinding == null) {
            return;
        }
        logsBinding.logsText.setText(logs);
        logsBinding.logsScroll.post(() -> {
            if (logsBinding != null) {
                logsBinding.logsScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void resolveOperationMode() {
        // The first call can take a while when a root prompt is shown, so it must not block
        Shell.getShell(shell -> {
            if (isDestroyed()) {
                return;
            }
            if (!session.isOperationModeResolved()) {
                setupSessionOperationMode();
                setupService();
            } else {
                viewModel.onOperationModeChanged();
            }
        });
    }

    private void setupSessionOperationMode() {
        OperationMode mode = OperationModeUtils.getOperationMode(getApplication(), shouldCheckShizuku);
        session.setOperationMode(mode);
        session.setOperationModeResolved(true);
        Log.d(TAG, "Operation mode is: " + mode);
        viewModel.onOperationModeChanged();
    }

    private void setupService() {
        if (session.isRoot()) {
            RootService.bind(new Intent(this, PrivilegedRootService.class), PrivilegedProvider.getConnection());
            return;
        }
        if (session.getOperationMode() == OperationMode.SYSTEM) {
            getApplicationContext().bindService(
                    new Intent(this, PrivilegedSystemService.class),
                    PrivilegedProvider.getConnection(),
                    Context.BIND_AUTO_CREATE);
            return;
        }
        addShizukuListeners();
    }

    private void addShizukuListeners() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addRequestPermissionResultListener(this);
        shizukuListenersAdded = true;
    }

    private void removeShizukuListeners() {
        Shizuku.removeRequestPermissionResultListener(this);
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        shizukuListenersAdded = false;
    }

    private void askShizukuPermission() {
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            requestPermissions(new String[]{ShizukuProvider.PERMISSION}, SHIZUKU_REQUEST_CODE);
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, int grantResult) {
        if (grantResult == PackageManager.PERMISSION_GRANTED && requestCode == SHIZUKU_REQUEST_CODE) {
            bindShizuku();
        }
        Shizuku.removeRequestPermissionResultListener(this);
    }

    private void bindShizuku() {
        Shizuku.bindUserService(userServiceArgs, PrivilegedProvider.getConnection());
        shouldCheckShizuku = true;
        setupSessionOperationMode();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissCurrentDialog();
        binding = null;

        if (isChangingConfigurations()) {
            return;
        }

        if (shizukuListenersAdded) {
            removeShizukuListeners();
        }
        switch (session.getOperationMode()) {
            case ROOT:
            case SYSTEM_AND_ROOT:
                RootService.unbind(PrivilegedProvider.getConnection());
                break;
            case SYSTEM:
                getApplicationContext().unbindService(PrivilegedProvider.getConnection());
                break;
            case SHIZUKU:
                try {
                    Shizuku.unbindUserService(userServiceArgs, PrivilegedProvider.getConnection(), true);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Unable to unbind the Shizuku service.", e);
                }
                break;
            default:
                break;
        }
        // A new launch has to bind the services again
        session.setOperationModeResolved(false);
    }
}