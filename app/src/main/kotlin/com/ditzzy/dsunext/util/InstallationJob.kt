package com.ditzzy.dsunext.util

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal cancellation token shared between the UI and the worker threads.
 * Workers are expected to poll [#isCancelled()] between units of work.
 */
class InstallationJob {
    private val cancelled: AtomicBoolean = AtomicBoolean(false)

    fun cancel() {
        cancelled.`set`(true)
    }

    fun isCancelled(): Boolean {
        return cancelled.`get`()
    }
}
