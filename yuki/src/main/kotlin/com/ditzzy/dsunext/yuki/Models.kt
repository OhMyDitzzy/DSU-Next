package com.ditzzy.dsunext.yuki

import java.io.File

enum class RomFormat(val key: String) {
    /** `*.new.dat[.br]` + `*.transfer.list` recovery ROM. */
    SDAT("sdat"),

    /** Raw images in an `image-*.zip` (Pixel factory images). */
    FASTBOOT("fastboot"),

    /** A/B OTA with `payload.bin`. */
    PAYLOAD("payload"),

    /** ROM whose logical partitions live in a `super.img`. */
    SUPER("super");

    companion object {
        @JvmStatic
        fun fromKey(key: String): RomFormat =
            entries.firstOrNull { it.key == key } ?: throw YukiException("unknown ROM format: $key")
    }
}

enum class OutputFormat(val key: String) {
    /** The same kind of ROM as the input. */
    AUTO("auto"),
    SDAT("sdat"),
    FASTBOOT("fastboot"),
    PAYLOAD("payload"),
    SUPER("super"),
}

/** What `repack` makes from a payload.bin ROM when the output format is [OutputFormat.AUTO]. */
enum class PayloadOutput(val key: String) {
    PAYLOAD("payload"),
    FASTBOOT("fastboot"),
    BOTH("both"),
}

data class RomPartition(
    val name: String,
    val transferListVersion: Int,
    val brotli: Boolean,
    val size: Long,
    /** `ext4` or `erofs`. */
    val fileSystem: String,
)

data class RomState(
    val format: RomFormat,
    val partitions: List<RomPartition>,
)

/** A null field keeps what `yuki.prop` in the work folder says (or the Yuki default). */
data class RepackOptions @JvmOverloads constructor(
    /** Brotli quality 0-11 for `*.new.dat.br`. */
    val brotliQuality: Int? = null,
    /** Deflate level 0-9 for the other zip entries. */
    val zipLevel: Int? = null,
    val outputFormats: List<OutputFormat>? = null,
    val payloadOutput: PayloadOutput? = null,
    /** xz preset 0-9 for rebuilt images in a new payload.bin. */
    val xzLevel: Int? = null,
    /** Private key (`.pk8`) and certificate for a new payload.bin; the AOSP test key when both are null. */
    val signKey: File? = null,
    val signCert: File? = null,
)

data class ExtractResult(
    val fileSystem: String,
    val partition: String,
    val mountPoint: String,
    val directories: Long,
    val files: Long,
    val symlinks: Long,
    /** Symlinks only recorded in `<part>_symlinks` because the folder cannot hold them. */
    val symlinksNotCreated: Long,
    val specialFiles: Long,
    val bytes: Long,
    val warnings: List<String>,
)

data class BuildResult(
    val mountPoint: String,
    val directories: Long,
    val files: Long,
    val symlinks: Long,
    /** Entries in `fs_config` whose file no longer exists. */
    val removedEntries: Long,
    val blockSize: Long,
    /** Files without metadata that got Android-like defaults. */
    val newEntries: List<String>,
    val warnings: List<String>,
)

data class DynamicGroup(
    val name: String,
    /** 0 means no limit. */
    val maxSize: Long,
    val partitions: List<String>,
)

data class PayloadPartition(
    val name: String,
    val size: Long,
    val operations: Int,
)

data class PayloadInfo(
    val blockSize: Int,
    /** False for incremental OTAs, which Yuki refuses to dump. */
    val isFullOta: Boolean,
    val partitions: List<PayloadPartition>,
    val groups: List<DynamicGroup>,
)

data class SuperPartition(
    val name: String,
    val size: Long,
    val group: String,
)

data class SuperInfo(
    val size: Long,
    val slotCount: Int,
    val isSparse: Boolean,
    val groups: List<DynamicGroup>,
    val partitions: List<SuperPartition>,
)

data class Img2SdatResult(
    val writtenBlocks: Long,
    val newBlocks: Long,
)
