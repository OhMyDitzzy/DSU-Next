package com.ditzzy.dsunext.filemanager.api

enum class FileKind { FILE, DIRECTORY, SYMLINK, OTHER }

data class FileEntry(
    val path: String,
    val name: String,
    val kind: FileKind,
    val size: Long,
    val modifiedAtMillis: Long,
    /** Null when the filesystem does not expose POSIX modes. */
    val mode: Int?,
    val symlinkTarget: String?,
)

enum class FileErrorCode {
    ALREADY_EXISTS, NOT_FOUND, NOT_DIRECTORY, IS_DIRECTORY, DIRECTORY_NOT_EMPTY,
    TYPE_MISMATCH, INVALID_PATH, INVALID_MODE, OUTSIDE_WORKSPACE, SYMLINK_NOT_ALLOWED,
    SAME_FILE, DESCENDANT_TARGET, ACCESS_DENIED, NO_SPACE, UNSUPPORTED,
    SOURCE_CHANGED, IO_ERROR, BUSY, CLOSED,
}

data class FileError(
    val code: FileErrorCode,
    val path: String?,
    /** Diagnostic text. The frontend should localize using [code]. */
    val message: String,
    val cause: Throwable? = null,
)

enum class ChangeKind { CREATED, REPLACED, COPIED, MOVED, DELETED, PERMISSIONS_CHANGED }

data class FileChange(
    val kind: ChangeKind,
    val path: String,
    val source: String? = null,
)

/** Samples are capped at 256 entries; totals always include all entries. */
data class OperationReport(
    val changes: List<FileChange> = emptyList(),
    val changeCount: Long = 0,
    val skippedPaths: List<String> = emptyList(),
    val skippedCount: Long = 0,
    val bytesProcessed: Long = 0,
    /** Bytes sent to a caller-provided output stream cannot be rolled back. */
    val outputBytes: Long = 0,
    /** Opening an output can truncate it even before the first byte is written. */
    val outputOpened: Boolean = false,
    val cleanupFailures: List<FileError> = emptyList(),
)

sealed class FileValue {
    data object Done : FileValue()
    data class Entry(val entry: FileEntry) : FileValue()
    data class Listing(val entries: List<FileEntry>) : FileValue()
    /** Null when a create/copy/move request was skipped. */
    data class Destination(val path: String?) : FileValue()
}

sealed class FileResult {
    abstract val operationId: String
    abstract val report: OperationReport

    data class Success(
        override val operationId: String,
        val value: FileValue,
        override val report: OperationReport,
    ) : FileResult()

    /** No published filesystem changes or external output writes were recorded. */
    data class Failure(
        override val operationId: String,
        val error: FileError,
        override val report: OperationReport,
    ) : FileResult()

    data class Partial(
        override val operationId: String,
        val error: FileError,
        override val report: OperationReport,
    ) : FileResult()

    /** The report remains authoritative: cancellation does not undo completed work. */
    data class Cancelled(
        override val operationId: String,
        override val report: OperationReport,
    ) : FileResult()
}
