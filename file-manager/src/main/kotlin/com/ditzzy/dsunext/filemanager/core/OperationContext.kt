package com.ditzzy.dsunext.filemanager.core

import com.ditzzy.dsunext.filemanager.api.*
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException

internal class OperationFailure(val error: FileError) : Exception(error.message, error.cause)
internal class OperationCancelled : Exception()

internal fun fail(code: FileErrorCode, path: String?, message: String): Nothing =
    throw OperationFailure(FileError(code, path, message))

internal fun fileError(error: Exception, path: String?): FileError {
    if (error is OperationFailure) return error.error
    val code = when (error) {
        is FileAlreadyExistsException -> FileErrorCode.ALREADY_EXISTS
        is NoSuchFileException -> FileErrorCode.NOT_FOUND
        is AccessDeniedException, is SecurityException -> FileErrorCode.ACCESS_DENIED
        is NotDirectoryException -> FileErrorCode.NOT_DIRECTORY
        is DirectoryNotEmptyException -> FileErrorCode.DIRECTORY_NOT_EMPTY
        is UnsupportedOperationException, is AtomicMoveNotSupportedException -> FileErrorCode.UNSUPPORTED
        is InvalidPathException -> FileErrorCode.INVALID_PATH
        is FileSystemException -> {
            val reason = error.reason.orEmpty().lowercase(java.util.Locale.ROOT)
            when {
                reason.contains("no space left") || reason.contains("disk quota") ||
                    reason.contains("enospc") || reason.contains("edquot") -> FileErrorCode.NO_SPACE
                reason.contains("not supported") || reason.contains("enotsup") ||
                    reason.contains("eopnotsupp") -> FileErrorCode.UNSUPPORTED
                reason.contains("permission denied") || reason.contains("operation not permitted") ||
                    reason.contains("read-only file system") -> FileErrorCode.ACCESS_DENIED
                else -> FileErrorCode.IO_ERROR
            }
        }
        else -> FileErrorCode.IO_ERROR
    }
    return FileError(code, path, error.message ?: error.javaClass.simpleName, error)
}

internal class OperationContext(
    val id: String,
    private val cancellation: CancellationToken,
    private val listener: ProgressListener?,
    private val changesListener: ChangeListener? = null,
) {
    var path: String? = null
    var bytesProcessed = 0L
    var outputBytes = 0L
    var outputOpened = false
    var changeCount = 0L
        private set
    var skippedCount = 0L
        private set
    private val changes = ArrayList<FileChange>()
    private val skipped = ArrayList<String>()
    private val cleanupFailures = ArrayList<FileError>()
    private var lastProgressAt = 0L

    fun check() {
        if (cancellation.isCancelled || Thread.currentThread().isInterrupted) throw OperationCancelled()
    }

    fun change(kind: ChangeKind, destination: String, source: String? = null) {
        changeCount++
        val change = FileChange(kind, destination, source)
        if (changes.size < SAMPLE_LIMIT) changes += change
        try {
            changesListener?.onChange(change)
        } catch (error: Exception) {
            throw OperationFailure(fileError(error, destination))
        }
    }

    fun skip(path: String) {
        skippedCount++
        if (skipped.size < SAMPLE_LIMIT) skipped += path
    }

    fun cleanupFailed(error: Exception, path: String) {
        if (cleanupFailures.size < SAMPLE_LIMIT) cleanupFailures += fileError(error, path)
    }

    fun progress(stage: OperationStage = OperationStage.PROCESSING) {
        val callback = listener ?: return
        val now = System.nanoTime()
        if (stage == OperationStage.PROCESSING && now - lastProgressAt < 100_000_000L) return
        lastProgressAt = now
        try {
            callback.onProgress(OperationProgress(id, stage, path, bytesProcessed, changeCount))
        } catch (_: Exception) {
            // A UI callback must not turn a completed write into a failed file operation.
        }
    }

    fun report(): OperationReport = OperationReport(
        changes.toList(), changeCount, skipped.toList(), skippedCount, bytesProcessed,
        outputBytes, outputOpened, cleanupFailures.toList(),
    )

    companion object {
        private const val SAMPLE_LIMIT = 256
    }
}
