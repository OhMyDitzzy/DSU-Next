package com.ditzzy.dsunext.checker.treble

import android.util.Log

private const val TAG = "TrebleUtils"

private val PROPERTY_LINE = Regex("""^\[(.+?)]: \[(.*)]$""")

@Volatile
private var properties: Map<String, String>? = null

/**
 * Drops the cached `getprop` output so the next lookup reads it again.
 */
internal fun reloadProperties() {
    properties = readAllProperties()
}

/**
 * Reads a system property. `getprop` is executed only once and its output is
 * cached, so checking many properties doesn't spawn a process for each one.
 */
fun getProperty(property: String, defaultValue: String? = null): String? {
    val snapshot = properties ?: readAllProperties().also { properties = it }
    return snapshot[property] ?: defaultValue
}

private fun readAllProperties(): Map<String, String> {
    val result = HashMap<String, String>()
    var process: Process? = null
    try {
        process = ProcessBuilder("getprop").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                PROPERTY_LINE.find(line)?.let { match ->
                    result[match.groupValues[1]] = match.groupValues[2]
                }
            }
        }
        process.waitFor()
    } catch (e: Exception) {
        Log.w(TAG, "Unable to read system properties", e)
    } finally {
        process?.destroy()
    }
    return result
}
