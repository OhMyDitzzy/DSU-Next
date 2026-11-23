package com.ditzzy.dsunext.installer.privileged

import java.util.function.Consumer
import android.util.Log
import com.ditzzy.dsunext.preparation.InstallationStep
import com.ditzzy.dsunext.util.CmdRunner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Follows the logcat output of gsid and the DSU app to figure out how the installation is going,
 * since the DSU app doesn't offer any other way to observe it.
 */
class LogcatDiagnostic {
    interface Callbacks {
        fun onInstallationError(error: InstallationStep, errorInfo: String)

        fun onStepUpdate(step: InstallationStep)

        fun onInstallationProgressUpdate(progress: Float, partition: String)

        fun onInstallationSuccess()

        fun onLogLineReceived()
    }

    private val callbacks: Callbacks

    private val logs: StringBuilder = StringBuilder()

    private val isLogging: AtomicBoolean = AtomicBoolean(false)

    @Volatile
    private var shouldLogEverything: Boolean = false

    @Volatile
    private var prependString: String = ""

    constructor(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun getLogs(): String {
        synchronized(logs) {
            return logs.toString()
        }
    }

    fun isLogging(): Boolean {
        return isLogging.`get`()
    }

    fun setShouldLogEverything(shouldLogEverything: Boolean) {
        this.shouldLogEverything = shouldLogEverything
    }

    /**
     * Blocks until logging stops when not running as root, so it must not be called from the
     * main thread.
     *
     * @param prependString text placed at the top of the collected logs (eg: device info).
     */
    fun startLogging(prependString: String) {
        if (isLogging.`get`()) {
            destroy()
        }
        synchronized(logs) {
            logs.setLength(0)
        }
        this.prependString = prependString
        isLogging.`set`(true)
        Log.d(TAG, ("startLogging(), logEverything: " + shouldLogEverything))
        CmdRunner.run("logcat -c")
        val logCmd: String = (if (shouldLogEverything) "logcat" else FILTERED_LOGCAT_CMD)
        CmdRunner.runReadEachLine(logCmd, Consumer(this::handleLine))
    }

    fun destroy() {
        CmdRunner.destroy()
        isLogging.`set`(false)
        Log.d(TAG, ("destroy(), isLogging: " + isLogging.`get`()))
    }

    private fun handleLine(line: String) {
        synchronized(logs) {
            if ((logs.length == 0)) {
                logs.append(prependString).append('\n')
            }
        }
        if ((line.contains("DynamicSystemService") && line.contains("startInstallation"))) {
            callbacks.onStepUpdate(InstallationStep.INSTALLING)
            callbacks.onInstallationProgressUpdate(0f, "userdata")
        }
        if (!isLogging.`get`()) {
            return
        }
        synchronized(logs) {
            logs.append(line).append('\n')
        }
        callbacks.onLogLineReceived()
        if (line.contains("We are already running in DynamicSystem")) {
            fail(InstallationStep.ERROR_ALREADY_RUNNING_DYN_OS, line)
        }
        if ((line.contains("realpath failed") && line.contains("Permission denied"))) {
            fail(InstallationStep.ERROR_EXTERNAL_SDCARD_ALLOC, line)
            return
        }
        if (line.contains("is below the minimum threshold of")) {
            fail(InstallationStep.ERROR_NO_AVAIL_STORAGE, line)
            return
        }
        if (((line.contains("read failed") && line.contains("No such file or directory")) && line.contains("f2fs"))) {
            fail(InstallationStep.ERROR_F2FS_WRONG_PATH, line)
            return
        }
        if ((line.contains("Failed to get stat for block device") && line.contains("Permission denied"))) {
            fail(InstallationStep.ERROR_SELINUX, line)
            return
        }
        if ((line.contains("File is too fragmented") && line.contains("512"))) {
            fail(InstallationStep.ERROR_EXTENTS, line)
            return
        }
        if (line.contains("NOT_STARTED")) {
            fail((if (line.contains("INSTALL_CANCELLED")) InstallationStep.ERROR_CANCELED else InstallationStep.ERROR), line)
            return
        }
        if (line.contains("IN_PROGRESS")) {
            if ((line.contains("progress:") && line.contains("partition name:"))) {
                try {
                    val progressMatcher: Matcher = PROGRESS_PATTERN.matcher(line)
                    val partitionMatcher: Matcher = PARTITION_PATTERN.matcher(line)
                    if ((!progressMatcher.find() || !partitionMatcher.find())) {
                        throw IllegalStateException("Progress not found")
                    }
                    val progressText: Array<String> = java.util.regex.Pattern.compile("/").split(progressMatcher.group(2)!!)
                    val progress: Float = (java.lang.Float.parseFloat(progressText[0]) / java.lang.Float.parseFloat(progressText[1]))
                    callbacks.onInstallationProgressUpdate(progress, partitionMatcher.group(2)!!)
                } catch (e: RuntimeException) {
                    callbacks.onStepUpdate(InstallationStep.PROCESSING_LOG_READABLE)
                }
            } else {
                callbacks.onStepUpdate(InstallationStep.PROCESSING_LOG_READABLE)
            }
        }
        if ((line.contains("READY") && line.contains("INSTALL_COMPLETED"))) {
            succeed()
            return
        }
        if (line.contains("ACTION_CANCEL_INSTALL")) {
            fail(InstallationStep.ERROR_CANCELED, line)
            return
        }
        if (line.contains("postStatus(): statusCode=2")) {
            callbacks.onStepUpdate(InstallationStep.PROCESSING_LOG_READABLE)
        }
        if (line.contains("postStatus(): statusCode=3")) {
            succeed()
            return
        }
        if (line.contains("postStatus(): statusCode=1")) {
            fail(InstallationStep.ERROR, line)
        }
    }

    private fun fail(error: InstallationStep, line: String) {
        callbacks.onInstallationError(error, line)
        destroy()
    }

    private fun succeed() {
        callbacks.onInstallationSuccess()
        destroy()
    }

    companion object {
        private const val TAG: String = "LogcatDiagnostic"

        private const val FILTERED_LOGCAT_CMD: String = (((("logcat -v tag" + " gsid:* *:S") + " DynamicSystemService:* *:S") + " DynamicSystemInstallationService:* *:S") + " DynSystemInstallationService:* *:S")

        private val PROGRESS_PATTERN: Pattern = Pattern.compile("(progress: )([\\d+/]+)")

        private val PARTITION_PATTERN: Pattern = Pattern.compile("(partition name: ([a-z+_]+))")
    }
}
