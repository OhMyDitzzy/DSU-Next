package com.ditzzy.dsunext.installer.privileged;

import android.util.Log;

import com.ditzzy.dsunext.preparation.InstallationStep;
import com.ditzzy.dsunext.util.CmdRunner;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follows the logcat output of gsid and the DSU app to figure out how the installation is going,
 * since the DSU app doesn't offer any other way to observe it.
 */
public final class LogcatDiagnostic {

    public interface Callbacks {
        void onInstallationError(InstallationStep error, String errorInfo);

        void onStepUpdate(InstallationStep step);

        void onInstallationProgressUpdate(float progress, String partition);

        void onInstallationSuccess();

        void onLogLineReceived();
    }

    private static final String TAG = "LogcatDiagnostic";

    private static final String FILTERED_LOGCAT_CMD = "logcat -v tag"
            + " gsid:* *:S"
            + " DynamicSystemService:* *:S"
            + " DynamicSystemInstallationService:* *:S"
            + " DynSystemInstallationService:* *:S";

    private static final Pattern PROGRESS_PATTERN = Pattern.compile("(progress: )([\\d+/]+)");
    private static final Pattern PARTITION_PATTERN = Pattern.compile("(partition name: ([a-z+_]+))");

    private final Callbacks callbacks;
    private final StringBuilder logs = new StringBuilder();
    private final AtomicBoolean isLogging = new AtomicBoolean(false);

    private volatile boolean shouldLogEverything = false;
    private volatile String prependString = "";

    public LogcatDiagnostic(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public String getLogs() {
        synchronized (logs) {
            return logs.toString();
        }
    }

    public boolean isLogging() {
        return isLogging.get();
    }

    public void setShouldLogEverything(boolean shouldLogEverything) {
        this.shouldLogEverything = shouldLogEverything;
    }

    /**
     * Blocks until logging stops when not running as root, so it must not be called from the
     * main thread.
     *
     * @param prependString text placed at the top of the collected logs (eg: device info).
     */
    public void startLogging(String prependString) {
        if (isLogging.get()) {
            destroy();
        }
        synchronized (logs) {
            logs.setLength(0);
        }
        this.prependString = prependString;
        isLogging.set(true);
        Log.d(TAG, "startLogging(), logEverything: " + shouldLogEverything);

        CmdRunner.run("logcat -c");
        String logCmd = shouldLogEverything ? "logcat" : FILTERED_LOGCAT_CMD;
        CmdRunner.runReadEachLine(logCmd, this::handleLine);
    }

    public void destroy() {
        CmdRunner.destroy();
        isLogging.set(false);
        Log.d(TAG, "destroy(), isLogging: " + isLogging.get());
    }

    private void handleLine(String line) {
        synchronized (logs) {
            if (logs.length() == 0) {
                logs.append(prependString).append('\n');
            }
        }

        if (line.contains("DynamicSystemService") && line.contains("startInstallation")) {
            callbacks.onStepUpdate(InstallationStep.INSTALLING);
            callbacks.onInstallationProgressUpdate(0F, "userdata");
        }

        if (!isLogging.get()) {
            return;
        }

        synchronized (logs) {
            logs.append(line).append('\n');
        }
        callbacks.onLogLineReceived();

        // Cannot install DSU when running an installed DSU
        if (line.contains("We are already running in DynamicSystem")) {
            fail(InstallationStep.ERROR_ALREADY_RUNNING_DYN_OS, line);
        }

        // When realpath fails with "permission denied", gsid is probably trying to allocate into
        // the external sdcard and fails due to a selinux denial:
        // E gsid    : realpath failed: /mnt/media_rw/AE5C-6D79/dsu: Permission denied
        // Solution: use a module with sepolicy fixes, or temporarily unmount the sdcard
        if (line.contains("realpath failed") && line.contains("Permission denied")) {
            fail(InstallationStep.ERROR_EXTERNAL_SDCARD_ALLOC, line);
            return;
        }

        // gsid requires at least 40% of free storage:
        // E gsid    : free space 24% is below the minimum threshold of 40%
        if (line.contains("is below the minimum threshold of")) {
            fail(InstallationStep.ERROR_NO_AVAIL_STORAGE, line);
            return;
        }

        // Some kernels register f2fs as f2fs_dev, which makes gsid fail:
        // E gsid    : read failed: /sys/fs/f2fs/dm-4/features: No such file or directory
        // Solution: use a gsid binary that checks f2fs_dev before failing, or fix the kernel
        if (line.contains("read failed")
                && line.contains("No such file or directory")
                && line.contains("f2fs")) {
            fail(InstallationStep.ERROR_F2FS_WRONG_PATH, line);
            return;
        }

        // Some ROMs don't ship the sepolicy rules gsid needs:
        // E gsid    : Failed to get stat for block device: /dev/block/mmcblk0p42: Permission denied
        // Solution: use a module with sepolicy fixes, or setenforce 0
        if (line.contains("Failed to get stat for block device") && line.contains("Permission denied")) {
            fail(InstallationStep.ERROR_SELINUX, line);
            return;
        }

        // Android 10 seems to require a high extents value:
        // E gsid : File is too fragmented, needs more than 512 extents.
        if (line.contains("File is too fragmented") && line.contains("512")) {
            fail(InstallationStep.ERROR_EXTENTS, line);
            return;
        }

        // DynamicSystemInstallationService: status: NOT_STARTED, cause: INSTALL_CANCELLED
        if (line.contains("NOT_STARTED")) {
            fail(line.contains("INSTALL_CANCELLED")
                    ? InstallationStep.ERROR_CANCELED
                    : InstallationStep.ERROR, line);
            return;
        }

        // DynamicSystemInstallationService: status: IN_PROGRESS, cause: CAUSE_NOT_SPECIFIED,
        // partition name: system, progress: 1879162880/1891233792
        if (line.contains("IN_PROGRESS")) {
            if (line.contains("progress:") && line.contains("partition name:")) {
                try {
                    Matcher progressMatcher = PROGRESS_PATTERN.matcher(line);
                    Matcher partitionMatcher = PARTITION_PATTERN.matcher(line);
                    if (!progressMatcher.find() || !partitionMatcher.find()) {
                        throw new IllegalStateException("Progress not found");
                    }

                    String[] progressText = progressMatcher.group(2).split("/");
                    float progress = Float.parseFloat(progressText[0]) / Float.parseFloat(progressText[1]);
                    callbacks.onInstallationProgressUpdate(progress, partitionMatcher.group(2));
                } catch (RuntimeException e) {
                    callbacks.onStepUpdate(InstallationStep.PROCESSING_LOG_READABLE);
                }
            } else {
                callbacks.onStepUpdate(InstallationStep.PROCESSING_LOG_READABLE);
            }
        }

        // DynamicSystemInstallationService: status: READY, cause: INSTALL_COMPLETED
        if (line.contains("READY") && line.contains("INSTALL_COMPLETED")) {
            succeed();
            return;
        }

        // Android 10 only, when cancelling:
        // D/DynSystemInstallationService: onStartCommand(): action=com.android.dynsystem.ACTION_CANCEL_INSTALL
        if (line.contains("ACTION_CANCEL_INSTALL")) {
            fail(InstallationStep.ERROR_CANCELED, line);
            return;
        }

        // Android 10 only, installing (STATUS_IN_PROGRESS):
        // D/DynSystemInstallationService: postStatus(): statusCode=2, causeCode=0
        if (line.contains("postStatus(): statusCode=2")) {
            callbacks.onStepUpdate(InstallationStep.PROCESSING_LOG_READABLE);
        }

        // Android 10 only, installation succeeded (STATUS_READY):
        // D/DynSystemInstallationService: postStatus(): statusCode=3, causeCode=1
        if (line.contains("postStatus(): statusCode=3")) {
            succeed();
            return;
        }

        // Android 10 only, STATUS_NOT_STARTED
        if (line.contains("postStatus(): statusCode=1")) {
            fail(InstallationStep.ERROR, line);
        }
    }

    private void fail(InstallationStep error, String line) {
        callbacks.onInstallationError(error, line);
        destroy();
    }

    private void succeed() {
        callbacks.onInstallationSuccess();
        destroy();
    }
}
