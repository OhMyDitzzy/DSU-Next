package com.ditzzy.dsunext.filemanager.api

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class CancellationToken {
    private val cancelled = AtomicBoolean(false)
    val isCancelled: Boolean get() = cancelled.get()
    fun cancel(): Boolean = cancelled.compareAndSet(false, true)
}

enum class OperationStage { STARTED, PROCESSING, FINISHED }

data class OperationProgress(
    val operationId: String,
    val stage: OperationStage,
    val path: String?,
    val bytesProcessed: Long,
    val changeCount: Long,
)

/** Called on the operation thread. Listener exceptions are isolated from file operations. */
fun interface ProgressListener {
    fun onProgress(progress: OperationProgress)
}

/** Called on a worker thread, or immediately if the result is already available. */
fun interface CompletionListener {
    fun onComplete(result: FileResult)
}

class OperationHandle internal constructor(
    val id: String,
    private val cancellation: CancellationToken,
    private val completion: CompletableFuture<FileResult>,
) {
    val isDone: Boolean get() = completion.isDone

    /** Requests cooperative cancellation. The final result still arrives normally. */
    fun cancel(): Boolean = !isDone && cancellation.cancel()

    /** Blocking: call from a background thread. */
    @Throws(InterruptedException::class, ExecutionException::class)
    fun await(): FileResult = completion.get()

    @Throws(InterruptedException::class, ExecutionException::class, TimeoutException::class)
    fun await(timeout: Long, unit: TimeUnit): FileResult = completion.get(timeout, unit)

    fun onComplete(listener: CompletionListener) {
        completion.thenAccept { listener.onComplete(it) }
    }
}
