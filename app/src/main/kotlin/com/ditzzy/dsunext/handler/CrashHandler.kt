package com.ditzzy.dsunext.handler

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.ditzzy.dsunext.BuildConfig
import com.ditzzy.dsunext.activity.CrashActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

open class CrashHandler : Thread.UncaughtExceptionHandler {
    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    private val context: Context

    private constructor(context: Context) {
        this.context = context
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val intent: Intent = Intent(context, CrashActivity::class.java)
            intent.addFlags((Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            intent.putExtra(CrashActivity.EXTRA_CRASH_INFO, generateLog(thread, throwable))
            context.startActivity(intent)
        } catch (handlerFailure: Throwable) {
            if ((defaultHandler != null)) {
                defaultHandler!!.uncaughtException(thread, throwable)
            }
            return
        }
        Process.killProcess(Process.myPid())
        System.exit(10)
    }

    private fun generateLog(thread: Thread, t: Throwable): String {
        val sb: StringBuilder = StringBuilder()
        sb.append("Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())).append("\n\n")
        sb.append("========== APP INFORMATION =========\n")
        sb.append("Package: ").append(BuildConfig.APPLICATION_ID).append("\n")
        sb.append("Version: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        sb.append("Build type: ").append(BuildConfig.BUILD_TYPE).append("\n")
        sb.append("========== END OF APP INFORMATION =========\n\n")
        sb.append("========== DEVICE INFORMATION =========\n")
        sb.append("Brand: ").append(Build.BRAND).append("\n")
        sb.append("Device: ").append(Build.DEVICE).append("\n")
        sb.append("Model: ").append(Build.MODEL).append("\n")
        sb.append("Android Version: ").append(Build.VERSION.RELEASE).append("\n")
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n")
        sb.append("========== END OF DEVICE INFORMATION =========\n\n")
        sb.append("Thread: ").append(thread.getName()).append("\n\n")
        sb.append("========== START STACK TRACE =========\n\n")
        sb.append(Log.getStackTraceString(t))
        sb.append("\n========== END OF STACK TRACE =========\n")
        if ((sb.length > MAX_LOG_LENGTH)) {
            sb.setLength(MAX_LOG_LENGTH)
            sb.append("\n... log truncated ...\n")
        }
        return sb.toString()
    }

    companion object {
        // An intent travels through binder, so the log has to stay well below the 1MB limit
        private const val MAX_LOG_LENGTH: Int = 200000

        @Volatile
        private var instance: CrashHandler? = null

        @JvmStatic
        fun initialize(application: Application) {
            if ((instance == null)) {
                synchronized(CrashHandler::class.java) {
                    if ((instance == null)) {
                        instance = CrashHandler(application)
                        Thread.setDefaultUncaughtExceptionHandler(instance)
                    }
                }
            }
        }
    }
}
