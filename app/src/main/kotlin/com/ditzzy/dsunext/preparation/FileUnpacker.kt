package com.ditzzy.dsunext.preparation

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ditzzy.dsunext.core.StorageManager
import com.ditzzy.dsunext.util.InstallationJob
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.utils.InputStreamStatistics
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.function.LongConsumer

/** Compresses or decompresses an image into the workspace folder. */
class FileUnpacker {
    fun interface ProgressListener {
        fun onProgress(progress: Float)
    }

    private val storageManager: StorageManager

    private val inputFile: Uri

    private val job: InstallationJob

    private val progressListener: ProgressListener

    private val finalFile: DocumentFile

    private val inputFileSize: Long

    private var lastPublishedPermille: Int = -1

    private var bytesRead: Long = 0L

    @Throws(IOException::class)
    constructor(storageManager: StorageManager, inputFile: Uri, outputFile: String, job: InstallationJob, progressListener: ProgressListener) {
        this.storageManager = storageManager
        this.inputFile = inputFile
        this.job = job
        this.progressListener = progressListener
        this.finalFile = storageManager.createDocumentFile(outputFile)
        this.inputFileSize = storageManager.getFilesizeFromUri(inputFile)
    }

    /** Number of bytes read from the input file by the last pack() or unpack() call. */
    fun getBytesRead(): Long {
        return bytesRead
    }

    @Throws(IOException::class)
    fun pack(): PreparedFile {
        storageManager.openInputStream(inputFile).use { `in` ->
            storageManager.openOutputStream(finalFile.getUri()).use { raw ->
                GzipCompressorOutputStream(raw).use { `out` ->
                    copy(`in`, `out`, LongConsumer { readBytes ->
                        updateProgress(readBytes)
                    })
                }
            }
        }
        return PreparedFile(finalFile.getUri(), storageManager.getFilesizeFromUri(finalFile.getUri()))
    }

    @Throws(IOException::class)
    fun unpack(): PreparedFile {
        val filename: String = storageManager.getFilenameFromUri(inputFile)
        storageManager.openInputStream(inputFile).use { `in` ->
            storageManager.openOutputStream(finalFile.getUri()).use { `out` ->
                var archive: InputStream? = null
                if (filename.endsWith("xz")) {
                    archive = XZCompressorInputStream(`in`)
                } else if ((filename.endsWith("gz") || filename.endsWith("gzip"))) {
                    archive = GzipCompressorInputStream(`in`)
                } else {
                    throw IOException("File type not supported")
                }
                archive!!.use { decompressed ->
                    val statistics: InputStreamStatistics = (decompressed as InputStreamStatistics)
                    copy(decompressed, `out`, LongConsumer { readBytes ->
                        updateProgress(statistics.getCompressedCount())
                    })
                }
            }
        }
        return PreparedFile(finalFile.getUri(), storageManager.getFilesizeFromUri(finalFile.getUri()))
    }

    @Throws(IOException::class)
    private fun copy(`in`: InputStream, `out`: OutputStream, onBytesRead: LongConsumer) {
        val buffer: ByteArray = ByteArray(BUFFER_SIZE)
        var total: Long = 0L
        var read: Int = 0
        while ((!job.isCancelled() && ((`in`.read(buffer)).also { read = it } != -1))) {
            total += (read).toLong()
            onBytesRead.accept(total)
            `out`.write(buffer, 0, read)
        }
        `out`.flush()
        bytesRead = total
    }

    private fun updateProgress(processedBytes: Long) {
        if ((inputFileSize <= 0L)) {
            return
        }
        val progress: Float = ((processedBytes).toFloat() / (inputFileSize).toFloat())
        val permille: Int = ((progress * 1000f)).toInt()
        if ((permille != lastPublishedPermille)) {
            lastPublishedPermille = permille
            progressListener.onProgress(Math.min(progress, 1f))
        }
    }

    companion object {
        private const val BUFFER_SIZE: Int = (64 * 1024)
    }
}
