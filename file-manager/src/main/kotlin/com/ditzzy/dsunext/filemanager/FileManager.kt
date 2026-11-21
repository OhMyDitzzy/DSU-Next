package com.ditzzy.dsunext.filemanager

import com.ditzzy.dsunext.filemanager.api.*
import com.ditzzy.dsunext.filemanager.core.FileOperations
import com.ditzzy.dsunext.filemanager.core.OperationCancelled
import com.ditzzy.dsunext.filemanager.core.OperationContext
import com.ditzzy.dsunext.filemanager.core.fileError
import com.ditzzy.dsunext.filemanager.workspace.WorkspacePaths
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** The caller supplies an existing workspace directory and closes the manager when finished. */
class FileManager @JvmOverloads @Throws(IOException::class) constructor(
    workspace: File,
    collisionNaming: CollisionNaming = CollisionNaming.NUMBERED,
) : AutoCloseable {
    private val paths = WorkspacePaths(workspace)
    private val operations = FileOperations(paths, collisionNaming)
    private val workspaceLock = lockFor(paths.root)
    private val lifecycle = Any()
    private val pending = HashMap<String, CancellationToken>()
    private var closed = false
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "file-manager").apply { isDaemon = true }
    }

    /** Blocking API for callers that already own an I/O executor. */
    @JvmOverloads
    fun execute(
        request: FileRequest,
        cancellation: CancellationToken = CancellationToken(),
        progress: ProgressListener? = null,
        changes: ChangeListener? = null,
    ): FileResult {
        val id = UUID.randomUUID().toString()
        synchronized(lifecycle) {
            if (closed) return closedResult(id)
            pending[id] = cancellation
        }
        return try {
            run(id, request, cancellation, progress, changes)
        } finally {
            synchronized(lifecycle) { pending.remove(id) }
        }
    }

    @JvmOverloads
    fun submit(request: FileRequest, progress: ProgressListener? = null, changes: ChangeListener? = null): OperationHandle {
        val id = UUID.randomUUID().toString()
        val cancellation = CancellationToken()
        val completion = CompletableFuture<FileResult>()
        val handle = OperationHandle(id, cancellation, completion)
        synchronized(lifecycle) {
            if (closed) {
                completion.complete(closedResult(id))
                return handle
            }
            pending[id] = cancellation
            worker.execute {
                try {
                    completion.complete(run(id, request, cancellation, progress, changes))
                } finally {
                    synchronized(lifecycle) { pending.remove(id) }
                }
            }
        }
        return handle
    }

    private fun run(
        id: String,
        request: FileRequest,
        cancellation: CancellationToken,
        progress: ProgressListener?,
        changes: ChangeListener?,
    ): FileResult {
        if (workspaceLock.isHeldByCurrentThread) {
            return FileResult.Failure(id, FileError(FileErrorCode.BUSY, null,
                "A listener cannot execute a nested operation on the same workspace"), OperationReport())
        }
        val context = OperationContext(id, cancellation, progress, changes)
        var locked = false
        val result = try {
            while (!locked) {
                context.check()
                locked = workspaceLock.tryLock(50, TimeUnit.MILLISECONDS)
            }
            context.check()
            context.progress(OperationStage.STARTED)
            val value = operations.execute(request, context)
            val report = context.report()
            if (report.cleanupFailures.isNotEmpty()) {
                FileResult.Partial(id, report.cleanupFailures.first(), report)
            } else {
                FileResult.Success(id, value, report)
            }
        } catch (_: OperationCancelled) {
            FileResult.Cancelled(id, context.report())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            FileResult.Cancelled(id, context.report())
        } catch (error: Exception) {
            val report = context.report()
            val failure = fileError(error, context.path)
            if (cancellation.isCancelled || Thread.currentThread().isInterrupted) {
                FileResult.Cancelled(id, report)
            } else if (report.changeCount > 0 || report.outputOpened || report.cleanupFailures.isNotEmpty()) {
                FileResult.Partial(id, failure, report)
            } else {
                FileResult.Failure(id, failure, report)
            }
        } finally {
            if (locked) workspaceLock.unlock()
        }
        context.progress(OperationStage.FINISHED)
        return result
    }

    /** Pending work receives cancellation results; this does not block waiting for worker threads. */
    override fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            pending.values.forEach { it.cancel() }
            worker.shutdown()
        }
    }

    private fun closedResult(id: String): FileResult = FileResult.Failure(
        id, FileError(FileErrorCode.CLOSED, null, "The file manager is closed"), OperationReport(),
    )

    companion object {
        private val locks = HashMap<Path, WeakReference<ReentrantLock>>()

        private fun lockFor(root: Path): ReentrantLock = synchronized(locks) {
            locks[root]?.get()?.let { return@synchronized it }
            locks.entries.removeAll { it.value.get() == null }
            ReentrantLock(true).also { locks[root] = WeakReference(it) }
        }
    }
}
