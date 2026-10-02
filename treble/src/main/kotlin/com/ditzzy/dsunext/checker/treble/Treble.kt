package com.ditzzy.dsunext.checker.treble

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

data class TrebleResult(
    val isTrebleLegacy: Boolean,
    val isVndkLite: Boolean,
    val vndkVersion: String?
)

object Treble {

    fun check(): TrebleResult? {

        /**
         * Checks for the build.prop if treble is enabled, returns null if the
         * device doesn't include support for Project Treble at all.
         */
        if (getProperty("ro.treble.enabled") != "true") {
            return null
        }

        /**
         * Checks the file location of the vendor manifest to determine if the
         * device uses an old implementation of Project Treble. When neither
         * file can be seen (for example SELinux hides it from apps) the device
         * is still Treble-enabled, so it is reported as a regular Treble device
         * with an unknown VNDK version instead of as unsupported.
         */
        val newVndkManifest = File("/vendor/etc/vintf/manifest.xml")
        val oldVndkManifest = File("/vendor/manifest.xml")
        val mIsLegacyTreble = when {
            newVndkManifest.exists() -> false
            oldVndkManifest.exists() -> true
            else -> false
        }

        /**
         * Checks if the shipped VNDK Lite, which is possibly shipped when the
         * device is launched with Android versions older than Oreo, then just
         * updated to Oreo or newer.
         */
        val mIsVndkLite = getProperty("ro.vndk.lite") == "true"

        /**
         * Reads the vendor manifest to check for the SELinux Policy version of
         * the device, falling back to ro.vndk.version when the manifest can't
         * be read.
         */
        val manifest = if (mIsLegacyTreble) oldVndkManifest else newVndkManifest
        val mVndkVersion = readSepolicyVersion(manifest)
            ?: getProperty("ro.vndk.version")?.takeIf { it.isNotBlank() }

        /**
         * Arranges the result in a data class and returns it
         */
        return TrebleResult(mIsLegacyTreble, mIsVndkLite, mVndkVersion)
    }

    private fun readSepolicyVersion(manifest: File): String? {
        return try {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
            document.getElementsByTagName("sepolicy").item(0)
                ?.textContent
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

}
