package com.ditzzy.dsunext.yuki

internal object NativeBridge {
    val loadError: Throwable? = try {
        System.loadLibrary("yuki")
        null
    } catch (t: Throwable) {
        t
    }

    @JvmStatic external fun yukiVersion(): String

    @JvmStatic external fun initWorkDir(workDir: String): Array<String>

    @JvmStatic external fun unpack(input: String, workDir: String, logger: YukiLogger?)

    @JvmStatic external fun state(workDir: String): Array<String>?

    @JvmStatic external fun romInfo(workDir: String): Array<String>

    @JvmStatic external fun repack(
        workDir: String,
        output: String?,
        brotli: Int,
        zip: Int,
        formats: String?,
        payloadOutput: String?,
        xz: Int,
        signKey: String?,
        signCert: String?,
        logger: YukiLogger?,
    ): Array<String>

    @JvmStatic external fun cleanup(workDir: String, all: Boolean): Array<String>

    @JvmStatic external fun extractImage(
        image: String,
        outDir: String,
        part: String?,
        logger: YukiLogger?,
    ): Array<String>

    @JvmStatic external fun buildImage(
        workDir: String,
        part: String,
        output: String,
        size: String?,
        force: Boolean,
        logger: YukiLogger?,
    ): Array<String>

    @JvmStatic external fun payloadInfo(path: String): Array<String>

    @JvmStatic external fun payloadDump(
        path: String,
        outDir: String,
        names: Array<String>?,
        threads: Int,
        logger: YukiLogger?,
    ): Array<String>

    @JvmStatic external fun superInfo(path: String, slot: Int): Array<String>

    @JvmStatic external fun superDump(
        path: String,
        outDir: String,
        names: Array<String>?,
        slot: Int,
        logger: YukiLogger?,
    ): Array<String>

    @JvmStatic external fun sdat2img(
        transferList: String,
        newData: String,
        output: String,
        logger: YukiLogger?,
    ): String

    @JvmStatic external fun img2sdat(
        image: String,
        outDir: String,
        prefix: String,
        version: Int,
        brotli: Int,
        logger: YukiLogger?,
    ): Array<String>

    @JvmStatic external fun brotliCompress(input: String, output: String, quality: Int, window: Int): Long

    @JvmStatic external fun brotliDecompress(input: String, output: String): Long
}
