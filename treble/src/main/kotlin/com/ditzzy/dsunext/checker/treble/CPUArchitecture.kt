package com.ditzzy.dsunext.checker.treble

import android.os.Build
import java.io.File

data class ArchitectureResult(
    val cpuArch: ABI
)

enum class ABI {
    ARM32,
    ARM32_BINDER64,
    ARM64,
    X86,
    X86_64
}

object CPUArchitecture {

    fun check(): ArchitectureResult? {

        /**
         * Initializes the software-supported ABI, which is the preferred ABI and
         * is listed first in Build.SUPPORTED_ABIS.
         */
        val cpuArch = Build.SUPPORTED_ABIS.firstOrNull() ?: return null

        /**
         * Determines the bitness through the instruction sets supported by the
         * OS.
         */
        val softwareResult = when {
            cpuArch.contains("arm64-v8a") -> ABI.ARM64
            cpuArch.contains("armeabi-v7a") -> ABI.ARM32
            cpuArch.contains("x86_64") -> ABI.X86_64
            cpuArch.contains("x86") -> ABI.X86
            else -> null
        }

        /**
         * Determines the bitness through the CPU architecture.
         */
        val hardwareResult: String? = try {
            File("/proc/cpuinfo").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val data = line.split(":")
                    if (data.size > 1 && data[0].trim().replace(" ", "_").equals("cpu_architecture", true)) {
                        data[1].trim()
                    } else {
                        null
                    }
                }.firstOrNull()
            }
        } catch (e: Exception) {
            null
        }

        /**
         * Returns ARM32_BINDER64 if the OS supports only 32-bit but the hardware
         * supports 64-bit, otherwise returns the result from the software.
         */
        return if (softwareResult == ABI.ARM32 && (hardwareResult == "8" || hardwareResult.equals("aarch64", true))) {
            ArchitectureResult(ABI.ARM32_BINDER64)
        } else {
            softwareResult?.let { ArchitectureResult(it) }
        }

    }

}
