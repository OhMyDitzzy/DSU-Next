package com.ditzzy.dsunext.checker.treble

import android.util.Log

/**
 * Everything the Treble check found. A null member means the feature is
 * missing (or, for [systemAsRoot], that it couldn't be determined).
 */
data class TrebleReport(
    val treble: TrebleResult?,
    val architecture: ArchitectureResult?,
    val ab: ABResult?,
    val systemAsRoot: Boolean?
) {
    val isTrebleSupported: Boolean
        get() = treble != null
}

/**
 * Single entry point of the module, also friendly to Java callers.
 */
object TrebleChecker {

    private const val TAG = "TrebleChecker"

    /**
     * Runs all checks. It reads system properties and files, so it must not be
     * called on the main thread.
     */
    @JvmStatic
    fun check(): TrebleReport {
        reloadProperties()
        return TrebleReport(
            treble = safely("Treble") { Treble.check() },
            architecture = safely("CPU architecture") { CPUArchitecture.check() },
            ab = safely("A/B") { AB.check() },
            systemAsRoot = safely("System-as-root") { SystemAsRoot.check() }
        )
    }

    // One failing check must not take the whole report down with it
    private inline fun <T> safely(name: String, block: () -> T?): T? {
        return try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$name check failed", e)
            null
        }
    }

}
