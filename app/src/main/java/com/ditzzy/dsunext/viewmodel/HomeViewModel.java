package com.ditzzy.dsunext.viewmodel;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ditzzy.dsunext.BuildConfig;
import com.ditzzy.dsunext.DsuNextApp;
import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.activity.MainActivity;
import com.ditzzy.dsunext.core.AppPrefs;
import com.ditzzy.dsunext.core.StorageManager;
import com.ditzzy.dsunext.installer.adb.AdbInstallationHandler;
import com.ditzzy.dsunext.installer.privileged.DsuInstallationHandler;
import com.ditzzy.dsunext.installer.privileged.LogcatDiagnostic;
import com.ditzzy.dsunext.installer.root.DsuInstaller;
import com.ditzzy.dsunext.model.DsuConstants;
import com.ditzzy.dsunext.model.DsuInstallationSource;
import com.ditzzy.dsunext.model.OperationMode;
import com.ditzzy.dsunext.model.Session;
import com.ditzzy.dsunext.preparation.InstallationStep;
import com.ditzzy.dsunext.preparation.Preparation;
import com.ditzzy.dsunext.service.PrivilegedProvider;
import com.ditzzy.dsunext.ui.Event;
import com.ditzzy.dsunext.ui.UiMessage;
import com.ditzzy.dsunext.util.DevicePropUtils;
import com.ditzzy.dsunext.util.FilenameUtils;
import com.ditzzy.dsunext.util.InstallationJob;
import com.ditzzy.dsunext.util.OperationModeUtils;
import com.ditzzy.dsunext.util.StorageUtils;
import com.topjohnwu.superuser.Shell;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class HomeViewModel extends AndroidViewModel {

    private static final String TAG = "HomeViewModel";
    private static final String DSU_PACKAGE = "com.android.dynsystem";
    private static final String READ_LOGS = "android.permission.READ_LOGS";
    private static final long USERDATA_ERROR_MS = 5000L;
    private static final long FILE_ERROR_MS = 2000L;
    private static final long SELINUX_SETTLE_MS = 5000L;
    private static final long GRANT_RECHECK_MS = 4000L;

    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList("gz", "xz", "img", "gzip");

    private final AppPrefs appPrefs;
    private final StorageManager storageManager;
    private final Session session;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();

    private final MutableLiveData<AdditionalCard> additionalCard =
            new MutableLiveData<>(AdditionalCard.NONE);
    private final MutableLiveData<InstallationCardState> installationLive =
            new MutableLiveData<>(new InstallationCardState());
    private final MutableLiveData<UserDataCardState> userDataLive =
            new MutableLiveData<>(new UserDataCardState());
    private final MutableLiveData<ImageSizeCardState> imageSizeLive =
            new MutableLiveData<>(new ImageSizeCardState());
    private final MutableLiveData<SheetDisplay> sheet = new MutableLiveData<>(SheetDisplay.NONE);
    private final MutableLiveData<String> logs = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> passedInitialChecks = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> keepScreenOn = new MutableLiveData<>(false);
    private final MutableLiveData<Event<UiMessage>> messages = new MutableLiveData<>();

    private InstallationCardState installation = new InstallationCardState();
    private UserDataCardState userData = new UserDataCardState();
    private ImageSizeCardState imageSize = new ImageSizeCardState();

    private volatile InstallationJob installationJob = new InstallationJob();
    private volatile LogcatDiagnostic logger;

    private volatile boolean checkDynamicPartitions = true;
    private volatile boolean checkUnavailableStorage = true;
    private volatile boolean checkReadLogsPermission = true;
    private volatile boolean disabledStorageCheck = false;

    private final float allocPercentage = DevicePropUtils.getGsidBinaryAllowedPerc();
    private final int allocPercentageInt = Math.round(allocPercentage * 100F);
    private final StorageUtils.AllocInfo storageStats = StorageUtils.getAllocInfo(allocPercentage);

    public HomeViewModel(@NonNull Application application) {
        super(application);
        DsuNextApp app = DsuNextApp.from(application);
        appPrefs = app.getAppPrefs();
        storageManager = app.getStorageManager();
        session = app.getSession();
    }

    public LiveData<AdditionalCard> getAdditionalCard() {
        return additionalCard;
    }

    public LiveData<InstallationCardState> getInstallationCard() {
        return installationLive;
    }

    public LiveData<UserDataCardState> getUserDataCard() {
        return userDataLive;
    }

    public LiveData<ImageSizeCardState> getImageSizeCard() {
        return imageSizeLive;
    }

    public LiveData<SheetDisplay> getSheet() {
        return sheet;
    }

    public LiveData<String> getLogs() {
        return logs;
    }

    public LiveData<Boolean> getPassedInitialChecks() {
        return passedInitialChecks;
    }

    public LiveData<Boolean> getKeepScreenOn() {
        return keepScreenOn;
    }

    public LiveData<Event<UiMessage>> getMessages() {
        return messages;
    }

    public Session getSession() {
        return session;
    }

    public int getAllocPercentageInt() {
        return allocPercentageInt;
    }

    public String getSelectedFilename() {
        return session.getUserSelection().getSelectedFileName();
    }

    @Override
    protected void onCleared() {
        installationJob.cancel();
        LogcatDiagnostic current = logger;
        if (current != null) {
            current.destroy();
        }
        io.shutdown();
    }

    private void updateInstallation(Consumer<InstallationCardState> change) {
        synchronized (stateLock) {
            change.accept(installation);
            installationLive.postValue(installation.copy());
        }
    }

    private void updateUserData(Consumer<UserDataCardState> change) {
        synchronized (stateLock) {
            change.accept(userData);
            userDataLive.postValue(userData.copy());
        }
    }

    private void updateImageSize(Consumer<ImageSizeCardState> change) {
        synchronized (stateLock) {
            change.accept(imageSize);
            imageSizeLive.postValue(imageSize.copy());
        }
    }

    private void setStep(InstallationStep step) {
        updateInstallation(s -> s.step = step);
    }

    private void postMessage(int resId, Object... args) {
        messages.postValue(new Event<>(new UiMessage(resId, args)));
    }

    /** Back to the file selection, keeping whatever file the user already picked. */
    private void resetInstallationCard() {
        updateInstallation(s -> {
            s.step = InstallationStep.NOT_INSTALLING;
            s.progress = 0F;
            s.partition = "";
            s.errorText = "";
        });
        sheet.postValue(SheetDisplay.NONE);
    }

    public void dismissSheet() {
        sheet.postValue(SheetDisplay.NONE);
    }

    public void showSheet(SheetDisplay display) {
        sheet.postValue(display);
    }

    public void refreshPreferences() {
        keepScreenOn.postValue(appPrefs.getBoolean(AppPrefs.KEEP_SCREEN_ON));
        disabledStorageCheck = appPrefs.getBoolean(AppPrefs.DISABLE_STORAGE_CHECK);
    }

    /**
     * Must run once the operation mode is known, and again whenever it changes
     * (eg: when Shizuku shows up after startup).
     */
    public void onOperationModeChanged() {
        initialChecks();

        // Checking for an existing DSU needs MANAGE_DYNAMIC_SYSTEM, so it's root only
        if (session.isRoot() && !isInstalling()) {
            PrivilegedProvider.run(service -> {
                if (service.isInUse()) {
                    setStep(InstallationStep.DSU_ALREADY_RUNNING_DYN_OS);
                } else if (service.isInstalled()) {
                    setStep(InstallationStep.DSU_ALREADY_INSTALLED);
                }
            });
        }
    }

    public void initialChecks() {
        if (!session.isOperationModeResolved()) {
            return;
        }

        if (!appPrefs.isUserAgreementAccepted()) {
            additionalCard.postValue(AdditionalCard.NONE);
            passedInitialChecks.postValue(false);
            return;
        }

        if (checkDynamicPartitions && !DevicePropUtils.hasDynamicPartitions()) {
            blockWith(AdditionalCard.NO_DYNAMIC_PARTITIONS);
            return;
        }

        if (checkUnavailableStorage && !storageStats.hasAvailableStorage()) {
            blockWith(AdditionalCard.UNAVAILABLE_STORAGE);
            return;
        }

        io.execute(() -> {
            if (!storageManager.arePermissionsGrantedToFolder(appPrefs.getString(AppPrefs.SAF_PATH))) {
                blockWith(AdditionalCard.SETUP_STORAGE);
                return;
            }

            if (session.getOperationMode() == OperationMode.SHIZUKU
                    && checkReadLogsPermission
                    && !OperationModeUtils.isReadLogsPermissionGranted(getApplication())) {
                blockWith(AdditionalCard.MISSING_READ_LOGS_PERMISSION);
                return;
            }

            additionalCard.postValue(AdditionalCard.NONE);
            passedInitialChecks.postValue(true);
        });
    }

    private void blockWith(AdditionalCard card) {
        passedInitialChecks.postValue(false);
        additionalCard.postValue(card);
    }

    public void overrideDynamicPartitionCheck() {
        checkDynamicPartitions = false;
        initialChecks();
    }

    public void overrideUnavailableStorage() {
        checkUnavailableStorage = false;
        initialChecks();
    }

    public void refuseReadLogs() {
        checkReadLogsPermission = false;
        initialChecks();
    }

    public void grantReadLogs() {
        additionalCard.postValue(AdditionalCard.GRANTING_READ_LOGS_PERMISSION);
        ComponentName restart = new ComponentName(BuildConfig.APPLICATION_ID, MainActivity.class.getName());
        PrivilegedProvider.run(service -> {
            // The service restarts the app once the grant went through, and the system usually
            // kills it as well since READ_LOGS changes its groups. Either way this process
            // normally ends here.
            if (!service.grantPermission(READ_LOGS, restart)) {
                onReadLogsGrantFailed();
                return;
            }
            // Only reached when the app is still alive. If the restart did not happen, don't
            // leave the progress card up forever: the permission is granted, so the checks pass.
            mainHandler.postDelayed(this::initialChecks, GRANT_RECHECK_MS);
        }, this::onReadLogsGrantFailed);
    }

    private void onReadLogsGrantFailed() {
        postMessage(R.string.grant_permission_failed, BuildConfig.APPLICATION_ID);
        additionalCard.postValue(AdditionalCard.MISSING_READ_LOGS_PERMISSION);
    }

    public void takeUriPermission(Uri uri) {
        getApplication().getContentResolver().takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        io.execute(() -> {
            if (storageManager.arePermissionsGrantedToFolder(uri.toString())) {
                appPrefs.setString(AppPrefs.SAF_PATH, uri.toString());
                initialChecks();
            }
        });
    }

    public void onFileSelectionResult(Uri uri) {
        io.execute(() -> {
            String filename = FilenameUtils.queryName(getApplication().getContentResolver(), uri);
            int dot = filename.lastIndexOf('.');
            String extension = dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";

            // DSU packages (zip files) are only supported on Android 11 and newer
            boolean supported = SUPPORTED_EXTENSIONS.contains(extension)
                    || (Build.VERSION.SDK_INT > 29 && extension.equals("zip"));
            Log.d(TAG, "isFileSupported: " + supported + ", filename: " + filename);

            if (!supported) {
                updateInstallation(s -> {
                    s.fileError = true;
                    s.fileSelectionEnabled = false;
                });
                mainHandler.postDelayed(() -> updateInstallation(s -> {
                    s.fileError = false;
                    s.fileSelectionEnabled = true;
                }), FILE_ERROR_MS);
                return;
            }

            session.getUserSelection().setSelectedFile(uri, filename);
            updateInstallation(s -> {
                s.fileName = filename;
                s.fileSelectionEnabled = true;
                s.fileError = false;
                s.installable = true;
            });
        });
    }

    public void onClickClear() {
        session.getUserSelection().setSelectedFile(Uri.EMPTY, "");
        session.setDsuInstallation(DsuInstallationSource.none());
        updateInstallation(s -> {
            s.step = InstallationStep.NOT_INSTALLING;
            s.fileName = "";
            s.installable = false;
            s.fileError = false;
            s.fileSelectionEnabled = true;
            s.progress = 0F;
            s.partition = "";
            s.errorText = "";
        });
        sheet.postValue(SheetDisplay.NONE);
    }

    public void onClickReturn() {
        session.setDsuInstallation(DsuInstallationSource.none());
        resetInstallationCard();
    }

    public void onUserdataToggled(boolean checked) {
        updateUserData(s -> {
            s.selected = checked;
            s.text = "";
            s.error = false;
        });
    }

    public void updateUserdataSize(String input) {
        String digits = FilenameUtils.getDigits(input);
        if (!disabledStorageCheck && !digits.isEmpty() && exceeds(digits, storageStats.getMaximumAllowedGb())) {
            int max = storageStats.getMaximumAllowedGb();
            updateUserData(s -> {
                s.text = String.valueOf(max);
                s.error = true;
                s.maximumAllowed = max;
            });
            mainHandler.postDelayed(() -> updateUserData(s -> s.error = false), USERDATA_ERROR_MS);
            return;
        }
        updateUserData(s -> s.text = digits);
    }

    // A value too large for a long is certainly above any limit
    private static boolean exceeds(String digits, long limit) {
        try {
            return Long.parseLong(digits) > limit;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    public void onImageSizeToggled(boolean checked) {
        updateImageSize(s -> {
            s.selected = checked;
            s.text = "";
        });
        sheet.postValue(checked ? SheetDisplay.IMAGE_SIZE_WARNING : SheetDisplay.NONE);
    }

    public void onImageSizeInput(String input) {
        String digits = FilenameUtils.getDigits(input);
        updateImageSize(s -> s.text = digits);
    }

    public void onImageSizeWarningConfirmed() {
        dismissSheet();
    }

    public void onImageSizeWarningCancelled() {
        updateImageSize(s -> {
            s.selected = false;
            s.text = "";
        });
        dismissSheet();
    }

    public void onClickInstall() {
        synchronized (stateLock) {
            session.getUserSelection().setUserDataSize(userData.text);
            session.getUserSelection().setImageSize(imageSize.text);
        }
        sheet.postValue(SheetDisplay.CONFIRM_INSTALLATION);
    }

    private boolean isInstalling() {
        synchronized (stateLock) {
            return installation.isInstalling();
        }
    }

    public void onClickCancel() {
        if (isInstalling()) {
            sheet.postValue(SheetDisplay.CANCEL_INSTALLATION);
        }
    }

    public String getConfirmUserdataGb() {
        return session.getUserSelection().getUserDataSizeAsGb();
    }

    /** @return the custom image size in bytes, or {@link DsuConstants#DEFAULT_IMAGE_SIZE}. */
    public long getConfirmImageSize() {
        return session.getUserSelection().isCustomImageSize()
                ? session.getUserSelection().getUserSelectedImageSize()
                : DsuConstants.DEFAULT_IMAGE_SIZE;
    }

    public void onConfirmInstallationSheet() {
        dismissSheet();
        setStep(InstallationStep.PROCESSING);
        final InstallationJob job = new InstallationJob();
        installationJob = job;

        io.execute(() -> {
            session.getPreferences().setUnmountSdCard(appPrefs.getBoolean(AppPrefs.UMOUNT_SD));
            session.getPreferences().setUseBuiltinInstaller(appPrefs.getBoolean(AppPrefs.USE_BUILTIN_INSTALLER));
            try {
                new Preparation(storageManager, session, job, new Preparation.Callbacks() {
                    @Override
                    public void onStepUpdate(InstallationStep step) {
                        setStep(step);
                    }

                    @Override
                    public void onProgressUpdate(float progress) {
                        updateInstallation(s -> s.progress = progress);
                    }

                    @Override
                    public void onCanceled() {
                        session.setDsuInstallation(DsuInstallationSource.none());
                        resetInstallationCard();
                    }

                    @Override
                    public void onFinished(DsuInstallationSource preparedSource) {
                        Log.d(TAG, "DSU preparation finished, result: " + preparedSource);
                        session.setDsuInstallation(preparedSource);
                        startInstallation();
                    }
                }).invoke();
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Preparation failed.", e);
                // Canceling closes streams on purpose, which isn't an error worth reporting
                if (!job.isCancelled()) {
                    onInstallationError(InstallationStep.ERROR, String.valueOf(e));
                }
            }
        });
    }

    private void startInstallation() {
        io.execute(this::startInstallationBlocking);
    }

    private void startInstallationBlocking() {
        Log.d(TAG, "startInstallation(), session:\n" + session);
        setStep(InstallationStep.PROCESSING);

        try {
            if (session.getOperationMode() == OperationMode.ADB) {
                setupAdbInstallation();
            } else if (session.getPreferences().isUseBuiltinInstaller() && session.isRoot()) {
                startDsuInstallation();
            } else {
                startPrivilegedInstallation();
            }
        } catch (IOException | RemoteException | RuntimeException e) {
            Log.e(TAG, "Unable to start the installation.", e);
            if (!installationJob.isCancelled()) {
                onInstallationError(InstallationStep.ERROR, String.valueOf(e));
            }
        }
    }

    private void setupAdbInstallation() throws IOException {
        String scriptPath = new AdbInstallationHandler(storageManager, session).generate();
        Log.d(TAG, "Installation script generated: " + scriptPath);
        session.setInstallationScriptPath(scriptPath);
        setStep(InstallationStep.REQUIRES_ADB_CMD_TO_CONTINUE);
    }

    private void startDsuInstallation() {
        new DsuInstaller(
                getApplication(),
                session.getUserSelection().getUserSelectedUserdata(),
                session.getDsuInstallation(),
                installationJob,
                new DsuInstaller.Callbacks() {
                    @Override
                    public void onInstallationError(InstallationStep error, String errorInfo) {
                        HomeViewModel.this.onInstallationError(error, errorInfo);
                    }

                    @Override
                    public void onInstallationProgressUpdate(float progress, String partition) {
                        HomeViewModel.this.onInstallationProgressUpdate(progress, partition);
                    }

                    @Override
                    public void onCreatePartition(String partition) {
                        updateInstallation(s -> {
                            s.step = InstallationStep.CREATING_PARTITION;
                            s.partition = partition;
                        });
                    }

                    @Override
                    public void onInstallationStepUpdate(InstallationStep step) {
                        setStep(step);
                    }

                    @Override
                    public void onInstallationSuccess() {
                        setStep(InstallationStep.INSTALL_SUCCESS_REBOOT_DYN_OS);
                    }
                }).invoke();
    }

    private void startPrivilegedInstallation() throws RemoteException {
        setStep(InstallationStep.WAITING_USER_CONFIRMATION);
        new DsuInstallationHandler(session).startInstallation();

        if (session.isRoot() || OperationModeUtils.isReadLogsPermissionGranted(getApplication())) {
            startLogging();
        } else {
            setStep(InstallationStep.INSTALL_SUCCESS);
        }
    }

    // Tracks and diagnoses the installation by reading logcat
    private void startLogging() {
        if (logger == null) {
            logger = new LogcatDiagnostic(new LogcatDiagnostic.Callbacks() {
                @Override
                public void onInstallationError(InstallationStep error, String errorInfo) {
                    HomeViewModel.this.onInstallationError(error, errorInfo);
                }

                @Override
                public void onStepUpdate(InstallationStep step) {
                    setStep(step);
                }

                @Override
                public void onInstallationProgressUpdate(float progress, String partition) {
                    HomeViewModel.this.onInstallationProgressUpdate(progress, partition);
                }

                @Override
                public void onInstallationSuccess() {
                    setStep(InstallationStep.INSTALL_SUCCESS);
                }

                @Override
                public void onLogLineReceived() {
                    LogcatDiagnostic current = logger;
                    if (current != null) {
                        logs.postValue(current.getLogs());
                    }
                }
            });
        }

        final LogcatDiagnostic current = logger;
        io.execute(() -> {
            current.setShouldLogEverything(appPrefs.getBoolean(AppPrefs.FULL_LOGCAT_LOGGING));
            current.startLogging(generateUsefulLogInfo());
        });
    }

    private String generateUsefulLogInfo() {
        return "Device: " + Build.MODEL + "\n"
                + "SDK: Android " + Build.VERSION.RELEASE + " (" + Build.VERSION.SDK_INT + ")\n"
                + session + "\n"
                + "Package: " + BuildConfig.APPLICATION_ID + "\n"
                + "Version: " + BuildConfig.VERSION_NAME + " - " + BuildConfig.VERSION_CODE
                + " (" + BuildConfig.BUILD_TYPE + ")\n"
                + "checkDynamicPartitions: " + checkDynamicPartitions + "\n"
                + "checkUnavailableStorage: " + checkUnavailableStorage + "\n"
                + "checkReadLogsPermission: " + checkReadLogsPermission + "\n"
                + "allocPercentage: " + allocPercentage + "\n"
                + "hasAvailableStorage: " + storageStats.hasAvailableStorage() + "\n"
                + "maximumAllowedForAllocation: " + storageStats.getMaximumAllowedGb() + "\n";
    }

    public void saveLogs(Uri destination) {
        final String content = logs.getValue() == null ? "" : logs.getValue();
        io.execute(() -> {
            try {
                storageManager.writeStringToUri(content, destination);
                postMessage(R.string.saved_logs);
            } catch (IOException e) {
                Log.e(TAG, "Unable to save logs.", e);
                postMessage(R.string.save_logs_failed);
            }
        });
    }

    public void onClickCancelInstallationButton() {
        LogcatDiagnostic current = logger;
        if (session.getOperationMode() != OperationMode.ADB && current != null && current.isLogging()) {
            current.destroy();
            // Stopping the installation properly requires MANAGE_DYNAMIC_SYSTEM, so the DSU app is
            // stopped the blunt way instead
            PrivilegedProvider.run(service -> service.forceStopPackage(DSU_PACKAGE));
        }
        installationJob.cancel();
        session.setDsuInstallation(DsuInstallationSource.none());
        resetInstallationCard();
    }

    public void onClickRebootToDynOs() {
        setStep(InstallationStep.PROCESSING);
        PrivilegedProvider.run(service -> {
            service.setEnable(true, true);
            Shell.cmd("reboot").exec();
        });
    }

    public void onClickDiscardGsiAndStartInstallation() {
        setStep(InstallationStep.PROCESSING);
        PrivilegedProvider.run(service -> {
            service.remove();
            service.forceStopPackage(DSU_PACKAGE);
            startDsuInstallation();
        });
    }

    public void onClickDiscardGsi() {
        setStep(InstallationStep.PROCESSING);
        PrivilegedProvider.run(service -> {
            service.remove();
            service.forceStopPackage(DSU_PACKAGE);
            resetInstallationCard();
        });
    }

    public void onClickRetryInstallation() {
        setStep(InstallationStep.PROCESSING);
        startInstallation();
    }

    public void onClickUnmountSdCardAndRetry() {
        setStep(InstallationStep.PROCESSING);
        session.getPreferences().setUnmountSdCard(true);
        startInstallation();
    }

    public void onClickSetSeLinuxPermissive() {
        setStep(InstallationStep.PROCESSING);
        io.execute(() -> {
            Shell.cmd("setenforce 0").exec();
            mainHandler.postDelayed(this::startInstallation, SELINUX_SETTLE_MS);
        });
    }

    private void onInstallationProgressUpdate(float progress, String partition) {
        updateInstallation(s -> {
            s.partition = partition;
            s.progress = progress;
        });
    }

    private void onInstallationError(InstallationStep error, String errorContent) {
        updateInstallation(s -> {
            s.step = (error == InstallationStep.ERROR_SELINUX && !session.isRoot())
                    ? InstallationStep.ERROR_SELINUX_ROOTLESS
                    : error;
            s.errorText = errorContent == null ? "" : errorContent;
        });
    }
}
