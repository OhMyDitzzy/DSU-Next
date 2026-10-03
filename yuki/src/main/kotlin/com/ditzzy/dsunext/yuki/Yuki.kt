package com.ditzzy.dsunext.yuki

import java.io.File

object Yuki {
    /** False when `libyuki.so` could not be loaded; [loadError] says why. */
    @JvmStatic
    val isLoaded: Boolean
        get() = NativeBridge.loadError == null

    @JvmStatic
    val loadError: Throwable?
        get() = NativeBridge.loadError

    @JvmStatic
    @Throws(YukiException::class)
    fun yukiVersion(): String = withNative { NativeBridge.yukiVersion() }

    /** Creates `input/`, `output/` and `yuki.prop` in [workDir] when missing. Returns what it created. */
    @JvmStatic
    @Throws(YukiException::class)
    fun initWorkDir(workDir: File): List<File> = withNative {
        NativeBridge.initWorkDir(workDir.absolutePath).toFiles()
    }

    /**
     * Unpacks a ROM (zip, tgz, tar or payload.bin) into [workDir]: partition
     * files go to `partition/`, the rest of the ROM to `rom/`. Fails when the
     * folder is already unpacked; call [cleanup] first.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(YukiException::class)
    fun unpack(input: File, workDir: File, logger: YukiLogger? = null) {
        withNative { NativeBridge.unpack(input.absolutePath, workDir.absolutePath, logger) }
    }

    /** What [unpack] recorded, or null when [workDir] is not unpacked. */
    @JvmStatic
    @Throws(YukiException::class)
    fun state(workDir: File): RomState? = withNative {
        NativeBridge.state(workDir.absolutePath)?.let(::parseState)
    }

    /** Android version, ROM name and device read from the unpacked build.prop files. */
    @JvmStatic
    @Throws(YukiException::class)
    fun romInfo(workDir: File): Map<String, String> = withNative {
        val flat = NativeBridge.romInfo(workDir.absolutePath)
        val info = LinkedHashMap<String, String>()
        for (i in 0 until flat.size - 1 step 2) {
            info[flat[i]] = flat[i + 1]
        }
        info
    }

    /**
     * Repacks [workDir] into a new ROM. [output] defaults to
     * `output/NewROM-<date>.zip` in [workDir]. Returns the files written.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(YukiException::class)
    fun repack(
        workDir: File,
        output: File? = null,
        options: RepackOptions = RepackOptions(),
        logger: YukiLogger? = null,
    ): List<File> = withNative {
        NativeBridge.repack(
            workDir.absolutePath,
            output?.absolutePath,
            options.brotliQuality ?: -1,
            options.zipLevel ?: -1,
            options.outputFormats?.takeIf { it.isNotEmpty() }?.joinToString(",") { it.key },
            options.payloadOutput?.key,
            options.xzLevel ?: -1,
            options.signKey?.absolutePath,
            options.signCert?.absolutePath,
            logger,
        ).toFiles()
    }

    /** Removes what unpack and repack made. `input/` is kept, `output/` only when [all]. */
    @JvmStatic
    @JvmOverloads
    @Throws(YukiException::class)
    fun cleanup(workDir: File, all: Boolean = false): List<File> = withNative {
        NativeBridge.cleanup(workDir.absolutePath, all).toFiles()
    }

    /**
     * Extracts an ext4 or EROFS image into `<outDir>/<partition>/` with its
     * metadata in `<outDir>/config/`. [partition] defaults to the image name.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(YukiException::class)
    fun extractImage(
        image: File,
        outDir: File,
        partition: String? = null,
        logger: YukiLogger? = null,
    ): ExtractResult = withNative {
        parseExtract(NativeBridge.extractImage(image.absolutePath, outDir.absolutePath, partition, logger))
    }

    /**
     * Builds `<workDir>/<partition>/` plus `<workDir>/config/` back into an
     * ext4 or EROFS image. [size] is null for the original size, `"auto"` for
     * just enough, or bytes with an optional K, M or G suffix.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(YukiException::class)
    fun buildImage(
        workDir: File,
        partition: String,
        output: File,
        size: String? = null,
        overwrite: Boolean = false,
        logger: YukiLogger? = null,
    ): BuildResult = withNative {
        parseBuild(
            NativeBridge.buildImage(
                workDir.absolutePath,
                partition,
                output.absolutePath,
                size,
                overwrite,
                logger,
            ),
        )
    }

    /**
     * Appends an AVB hashtree footer to the raw ext4 or EROFS [image], in place.
     * The same as `avbtool add_hashtree_footer --image <image> --partition_name
     * <partition> --hash_algorithm <algorithm> --do_not_generate_fec`: no FEC and
     * no signing key (the vbmeta is unsigned), so the image only boots on an
     * unlocked device. [algorithm] is sha1, sha256 or sha512. The image grows by
     * the hash tree plus two blocks. An image that has a footer already gets a
     * fresh one. Throws, with the image at its old size, when it fails.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(YukiException::class)
    fun addHashtreeFooter(
        image: File,
        partition: String,
        algorithm: String = "sha256",
        logger: YukiLogger? = null,
    ): HashtreeFooterResult = withNative {
        parseHashtreeFooter(
            NativeBridge.addHashtreeFooter(image.absolutePath, partition, algorithm, logger),
        )
    }

    /** Reads the manifest of a `payload.bin` or of an OTA zip that contains one. */
    @JvmStatic
    @Throws(YukiException::class)
    fun payloadInfo(payload: File): PayloadInfo = withNative {
        parsePayloadInfo(NativeBridge.payloadInfo(payload.absolutePath))
    }

    /**
     * Writes the partitions of a full OTA payload to `<outDir>/<name>.img`, all
     * of them when [partitions] is null or empty. [threads] of 0 uses every core.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(YukiException::class)
    fun dumpPayload(
        payload: File,
        outDir: File,
        partitions: List<String>? = null,
        threads: Int = 0,
        logger: YukiLogger? = null,
    ): List<File> = withNative {
        NativeBridge.payloadDump(
            payload.absolutePath,
            outDir.absolutePath,
            partitions?.toTypedArray(),
            threads,
            logger,
        ).toFiles()
    }

    /** Reads the liblp metadata of a raw or sparse `super.img`. */
    @JvmStatic
    @JvmOverloads
    @Throws(YukiException::class)
    fun superInfo(image: File, slot: Int = 0): SuperInfo = withNative {
        parseSuperInfo(NativeBridge.superInfo(image.absolutePath, slot))
    }

    /**
     * Writes the logical partitions of a `super.img` to `<outDir>/<name>.img`;
     * every partition with data when [partitions] is null or empty.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(YukiException::class)
    fun dumpSuper(
        image: File,
        outDir: File,
        partitions: List<String>? = null,
        slot: Int = 0,
        logger: YukiLogger? = null,
    ): List<File> = withNative {
        NativeBridge.superDump(
            image.absolutePath,
            outDir.absolutePath,
            partitions?.toTypedArray(),
            slot,
            logger,
        ).toFiles()
    }

    /** Converts `*.new.dat[.br]` + `*.transfer.list` to a raw image. Returns the Android version name of the list. */
    @JvmStatic
    @JvmOverloads
    @Throws(YukiException::class)
    fun sdat2img(
        transferList: File,
        newData: File,
        output: File,
        logger: YukiLogger? = null,
    ): String = withNative {
        NativeBridge.sdat2img(
            transferList.absolutePath,
            newData.absolutePath,
            output.absolutePath,
            logger,
        )
    }

    /**
     * Converts an image to `<prefix>.new.dat` (or `.new.dat.br` when
     * [brotliQuality] is set, 0-11) and `<prefix>.transfer.list`.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(YukiException::class)
    fun img2sdat(
        image: File,
        outDir: File,
        prefix: String = "system",
        version: Int = 4,
        brotliQuality: Int? = null,
        logger: YukiLogger? = null,
    ): Img2SdatResult = withNative {
        val result = NativeBridge.img2sdat(
            image.absolutePath,
            outDir.absolutePath,
            prefix,
            version,
            brotliQuality ?: -1,
            logger,
        )
        Img2SdatResult(writtenBlocks = result[0].toLong(), newBlocks = result[1].toLong())
    }

    /** Compresses [input] into [output]. Null quality or window picks the Yuki default. Returns the output size. */
    @JvmStatic
    @JvmOverloads
    @Throws(YukiException::class)
    fun brotliCompress(input: File, output: File, quality: Int? = null, window: Int? = null): Long =
        withNative {
            NativeBridge.brotliCompress(input.absolutePath, output.absolutePath, quality ?: -1, window ?: -1)
        }

    /** Decompresses a brotli file into [output]. Returns the output size. */
    @JvmStatic
    @Throws(YukiException::class)
    fun brotliDecompress(input: File, output: File): Long = withNative {
        NativeBridge.brotliDecompress(input.absolutePath, output.absolutePath)
    }

    private inline fun <T> withNative(block: () -> T): T {
        NativeBridge.loadError?.let {
            throw YukiException("libyuki.so could not be loaded: ${it.message}", it)
        }
        return block()
    }

    private fun Array<String>.toFiles(): List<File> = map { File(it) }
}

private class Cursor(private val items: Array<String>) {
    private var index = 0

    val hasNext: Boolean
        get() = index < items.size

    fun next(): String = items[index++]

    fun nextInt(): Int = next().toInt()

    fun nextLong(): Long = next().toLong()

    fun rest(): List<String> {
        val tail = items.drop(index)
        index = items.size
        return tail
    }
}

private fun parseState(raw: Array<String>): RomState {
    val c = Cursor(raw)
    val format = RomFormat.fromKey(c.next())
    val partitions = ArrayList<RomPartition>()
    while (c.hasNext) {
        val name = c.next()
        val version = c.nextInt()
        val brotli = c.next() == "1"
        val size = c.nextLong()
        val fileSystem = c.next()
        partitions += RomPartition(name, version, brotli, size, fileSystem)
    }
    return RomState(format, partitions)
}

private fun parseExtract(raw: Array<String>): ExtractResult {
    val c = Cursor(raw)
    val fileSystem = c.next()
    val partition = c.next()
    val mountPoint = c.next()
    val directories = c.nextLong()
    val files = c.nextLong()
    val symlinks = c.nextLong()
    val symlinksNotCreated = c.nextLong()
    val specialFiles = c.nextLong()
    val bytes = c.nextLong()
    return ExtractResult(
        fileSystem, partition, mountPoint, directories, files, symlinks,
        symlinksNotCreated, specialFiles, bytes, c.rest(),
    )
}

private fun parseBuild(raw: Array<String>): BuildResult {
    val c = Cursor(raw)
    val mountPoint = c.next()
    val directories = c.nextLong()
    val files = c.nextLong()
    val symlinks = c.nextLong()
    val removed = c.nextLong()
    val blockSize = c.nextLong()
    val newEntries = List(c.nextInt()) { c.next() }
    return BuildResult(mountPoint, directories, files, symlinks, removed, blockSize, newEntries, c.rest())
}

private fun parseHashtreeFooter(raw: Array<String>): HashtreeFooterResult {
    val c = Cursor(raw)
    return HashtreeFooterResult(
        originalSize = c.nextLong(),
        hashedSize = c.nextLong(),
        treeOffset = c.nextLong(),
        treeSize = c.nextLong(),
        vbmetaOffset = c.nextLong(),
        vbmetaSize = c.nextLong(),
        finalSize = c.nextLong(),
        rootDigest = c.next(),
        salt = c.next(),
    )
}

private fun parsePayloadInfo(raw: Array<String>): PayloadInfo {
    val c = Cursor(raw)
    val blockSize = c.nextInt()
    val isFullOta = c.next() == "1"
    val count = c.nextInt()
    val partitions = ArrayList<PayloadPartition>(count)
    repeat(count) {
        val name = c.next()
        val size = c.nextLong()
        val operations = c.nextInt()
        partitions += PayloadPartition(name, size, operations)
    }
    val groups = ArrayList<DynamicGroup>()
    while (c.hasNext) {
        val name = c.next()
        val maxSize = c.nextLong()
        val members = c.next().split(',').filter { it.isNotEmpty() }
        groups += DynamicGroup(name, maxSize, members)
    }
    return PayloadInfo(blockSize, isFullOta, partitions, groups)
}

private fun parseSuperInfo(raw: Array<String>): SuperInfo {
    val c = Cursor(raw)
    val size = c.nextLong()
    val slotCount = c.nextInt()
    val isSparse = c.next() == "1"
    val groupCount = c.nextInt()
    val groupDefs = ArrayList<Pair<String, Long>>(groupCount)
    repeat(groupCount) {
        val name = c.next()
        val maxSize = c.nextLong()
        groupDefs += name to maxSize
    }
    val partitions = ArrayList<SuperPartition>()
    while (c.hasNext) {
        val name = c.next()
        val partSize = c.nextLong()
        val group = c.next()
        partitions += SuperPartition(name, partSize, group)
    }
    val groups = groupDefs.map { (name, maxSize) ->
        DynamicGroup(name, maxSize, partitions.filter { it.group == name }.map { it.name })
    }
    return SuperInfo(size, slotCount, isSparse, groups, partitions)
}
