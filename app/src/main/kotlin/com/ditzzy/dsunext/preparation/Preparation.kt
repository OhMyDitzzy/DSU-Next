package com.ditzzy.dsunext.preparation

import android.net.Uri
import android.util.Log
import com.ditzzy.dsunext.core.StorageManager
import com.ditzzy.dsunext.model.DsuConstants
import com.ditzzy.dsunext.model.DsuInstallationSource
import com.ditzzy.dsunext.model.Session
import com.ditzzy.dsunext.util.InstallationJob
import java.io.IOException
import java.io.InputStream

/**
 * Turns whatever the user picked into something DSU can consume, then hands it over through
 * [Callbacks#onFinished(DsuInstallationSource)].
 */
class Preparation {
    interface Callbacks {
        fun onStepUpdate(step: InstallationStep)

        fun onProgressUpdate(progress: Float)

        fun onCanceled()

        fun onFinished(preparedSource: DsuInstallationSource)
    }

    private val storageManager: StorageManager

    private val session: Session

    private val job: InstallationJob

    private val callbacks: Callbacks

    private val userSelectedImageSize: Long

    private val userSelectedFileUri: Uri

    constructor(storageManager: StorageManager, session: Session, job: InstallationJob, callbacks: Callbacks) {
        this.storageManager = storageManager
        this.session = session
        this.job = job
        this.callbacks = callbacks
        this.userSelectedImageSize = session.getUserSelection().getUserSelectedImageSize()
        this.userSelectedFileUri = session.getUserSelection().getSelectedFileUri()
    }

    @Throws(IOException::class)
    fun invoke() {
        if ((session.getPreferences().isUseBuiltinInstaller() && session.isRoot())) {
            prepareRooted()
            return
        }
        prepareForDsu()
    }

    @Throws(IOException::class)
    private fun prepareRooted() {
        var source: DsuInstallationSource? = null
        when (getExtension(userSelectedFileUri)) {
            "img" -> {
                source = DsuInstallationSource.singleSystemImage(userSelectedFileUri, storageManager.getRealFilesize(userSelectedFileUri))
            }
            "xz", "gz", "gzip" -> {
                val extracted: PreparedFile = extractFile(userSelectedFileUri, "system")
                source = DsuInstallationSource.singleSystemImage(extracted.getUri(), extracted.getSize())
            }
            "zip" -> {
                source = DsuInstallationSource.dsuPackage(userSelectedFileUri)
            }
            else -> {
                throw IOException("Unsupported filetype")
            }
        }
        finish(source!!)
    }

    @Throws(IOException::class)
    private fun prepareForDsu() {
        storageManager.cleanWorkspaceFolder(true)
        val fileExtension: String = getExtension(userSelectedFileUri)
        var prepared: PreparedFile? = null
        when (fileExtension) {
            "xz" -> {
                prepared = prepareXz(userSelectedFileUri)
            }
            "img" -> {
                prepared = prepareImage(userSelectedFileUri)
            }
            "gz", "gzip" -> {
                prepared = prepareGz(userSelectedFileUri)
            }
            "zip" -> {
                prepared = prepareZip(userSelectedFileUri)
            }
            else -> {
                throw IOException("Unsupported filetype")
            }
        }
        val source: DsuInstallationSource = (if (fileExtension.equals("zip")) DsuInstallationSource.dsuPackage(prepared!!.getUri()) else DsuInstallationSource.singleSystemImage(prepared!!.getUri(), prepared!!.getSize()))
        callbacks.onStepUpdate(InstallationStep.WAITING_USER_CONFIRMATION)
        storageManager.cleanWorkspaceFolder(job.isCancelled())
        finish(source)
    }

    private fun finish(source: DsuInstallationSource) {
        if (job.isCancelled()) {
            callbacks.onCanceled()
        } else {
            callbacks.onFinished(source)
        }
    }

    @Throws(IOException::class)
    private fun prepareZip(zipFile: Uri): PreparedFile {
        return PreparedFile(getSafeUri(zipFile), -1L)
    }

    @Throws(IOException::class)
    private fun prepareXz(xzFile: Uri): PreparedFile {
        callbacks.onStepUpdate(InstallationStep.DECOMPRESSING_XZ)
        val imgFile: PreparedFile = FileUnpacker(storageManager, xzFile, getFileName(xzFile), job, callbacks::onProgressUpdate).unpack()
        return prepareImage(imgFile.getUri())
    }

    @Throws(IOException::class)
    private fun prepareImage(imageFile: Uri): PreparedFile {
        callbacks.onStepUpdate(InstallationStep.COMPRESSING_TO_GZ)
        val packer: FileUnpacker = FileUnpacker(storageManager, imageFile, (getFileName(imageFile) + ".img.gz"), job, callbacks::onProgressUpdate)
        val compressed: PreparedFile = packer.pack()
        val imageSize: Long = packer.getBytesRead()
        val providerSize: Long = storageManager.getFilesizeFromUri(imageFile)
        if ((providerSize != imageSize)) {
            Log.w("Preparation", (((("Provider reported " + providerSize) + " bytes but ") + imageSize) + " bytes were read, using the latter"))
        }
        return PreparedFile(compressed.getUri(), imageSize)
    }

    @Throws(IOException::class)
    private fun prepareGz(gzFile: Uri): PreparedFile {
        val uri: Uri = getSafeUri(gzFile)
        if ((userSelectedImageSize != DsuConstants.DEFAULT_IMAGE_SIZE)) {
            return PreparedFile(uri, userSelectedImageSize)
        }
        callbacks.onStepUpdate(InstallationStep.PROCESSING)
        val fileSize: Long = storageManager.getFilesizeFromUri(uri)
        // If the gz is small enough, try to get the image size by reading its last four bytes
        if (((fileSize).toDouble() < GZIP_FOOTER_SIZE_LIMIT)) {
            val imageSize: Long = readGzipFooterSize(uri, fileSize)
            // An image can't be smaller than its compressed file, otherwise the value is wrong
            if ((imageSize > fileSize)) {
                return PreparedFile(uri, imageSize)
            }
        }
        callbacks.onStepUpdate(InstallationStep.DECOMPRESSING_GZIP)
        val extracted: PreparedFile = FileUnpacker(storageManager, uri, getFileName(uri), job, callbacks::onProgressUpdate).unpack()
        return PreparedFile(uri, extracted.getSize())
    }

    @Throws(IOException::class)
    private fun readGzipFooterSize(uri: Uri, fileSize: Long): Long {
        if ((fileSize < 4L)) {
            return -1L
        }
        storageManager.openInputStream(uri).use { `in` ->
            val toSkip: Long = (fileSize - 4L)
            var skipped: Long = 0L
            while ((skipped < toSkip)) {
                val n: Long = `in`.skip((toSkip - skipped))
                if ((n <= 0L)) {
                    return -1L
                }
                skipped += n
            }
            val footer: ByteArray = ByteArray(4)
            var total: Int = 0
            while ((total < footer.size)) {
                val n: Int = `in`.read(footer, total, (footer.size - total))
                if ((n < 0)) {
                    return -1L
                }
                total += n
            }
            return ((((((footer[0]).toInt() and (0xFFL).toInt()) or (((footer[1]).toInt() and (0xFFL).toInt()) shl 8)) or (((footer[2]).toInt() and (0xFFL).toInt()) shl 16)) or (((footer[3]).toInt() and (0xFFL).toInt()) shl 24))).toLong()
        }
    }

    @Throws(IOException::class)
    private fun extractFile(uri: Uri, partitionName: String): PreparedFile {
        callbacks.onStepUpdate(InstallationStep.EXTRACTING_FILE)
        return FileUnpacker(storageManager, uri, (partitionName + ".img"), job, callbacks::onProgressUpdate).unpack()
    }

    @Throws(IOException::class)
    private fun getSafeUri(uri: Uri): Uri {
        callbacks.onStepUpdate(InstallationStep.COPYING_FILE)
        return storageManager.getUriSafe(uri)
    }

    private fun getFileName(uri: Uri): String {
        val name: String = storageManager.getFilenameFromUri(uri)
        val dot: Int = name.lastIndexOf('.')
        return (if ((dot >= 0)) name.substring(0, dot) else name)
    }

    private fun getExtension(uri: Uri): String {
        val name: String = storageManager.getFilenameFromUri(uri)
        val dot: Int = name.lastIndexOf('.')
        return (if ((dot >= 0)) name.substring((dot + 1)) else "")
    }

    companion object {
        private const val GZIP_FOOTER_SIZE_LIMIT: Double = (java.lang.Integer.MAX_VALUE * 1.5)
    }
}
