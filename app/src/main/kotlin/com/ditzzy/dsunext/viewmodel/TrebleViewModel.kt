package com.ditzzy.dsunext.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.ditzzy.dsunext.checker.treble.TrebleChecker
import com.ditzzy.dsunext.checker.treble.TrebleReport
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Runs the Treble check off the main thread and keeps the result across configuration changes. */
class TrebleViewModel : ViewModel() {
    private val report: MutableLiveData<TrebleReport> = MutableLiveData<TrebleReport>()

    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var started: Boolean = false

    fun getReport(): LiveData<TrebleReport> {
        return report
    }

    /** Starts the check once, calling it again (rotation, re-entering the screen) does nothing. */
    fun load() {
        if (started) {
            return
        }
        started = true
        io.execute(callback1@ {
            val start: Long = System.currentTimeMillis()
            val result: TrebleReport = TrebleChecker.check()
            val remaining: Long = (MIN_LOADING_MS - (System.currentTimeMillis() - start))
            if ((remaining > 0L)) {
                try {
                    Thread.sleep(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@callback1
                }
            }
            report.postValue(result)
        })
    }

    protected override fun onCleared() {
        io.shutdownNow()
    }

    companion object {
        /** The check is quick, this keeps the loading indicator from just flashing on screen. */
        private const val MIN_LOADING_MS: Long = 600L
    }
}
