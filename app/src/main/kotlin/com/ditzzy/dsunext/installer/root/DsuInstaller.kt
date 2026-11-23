package com.ditzzy.dsunext.installer.root

import android.app.Application
import android.gsi.GsiProgress
import android.gsi.IGsiService
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.ErrnoException
import android.util.Log
import com.ditzzy.dsunext.model.DsuInstallationSource
import com.ditzzy.dsunext.model.ImagePartition
import com.ditzzy.dsunext.preparation.InstallationStep
import com.ditzzy.dsunext.util.GsiNames
import com.ditzzy.dsunext.util.InstallationJob
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * DSU installer implementation using Android APIs.
 * Based on InstallationAsyncTask from DynamicSystemInstallationService:
 * DynamicSystemInstallationService/src/com/android/dynsystem/InstallationAsyncTask.java
 *
 * <p>Calling the APIs directly is fast, because images are applied straight away instead of
 * preparing a file exclusively to be installed by the DSU system app. Having access to the APIs
 * also makes everything more flexible.
 *
 * <p>Unfortunately, this implementation has a downside: it requires MANAGE_DYNAMIC_SYSTEM, a
 * permission with a "signature" protection level. That's why this way of installing requires root.
 */
class DsuInstaller : DynamicSystemImpl {
    interface Callbacks {
        fun onInstallationError(error: InstallationStep, errorInfo: String)

        fun onInstallationProgressUpdate(progress: Float, partition: String)

        fun onCreatePartition(partition: String)

        fun onInstallationStepUpdate(step: InstallationStep)

        fun onInstallationSuccess()
    }

    private val application: Application

    private val userdataSize: Long

    private val dsuInstallation: DsuInstallationSource

    private val installationJob: InstallationJob

    private val dsuSlot: String

    private val multiGsi: Boolean

    private val callbacks: Callbacks

    /** Installs into the default slot and replaces nothing but a missing DSU, like a plain installer. */
    constructor(application: Application, userdataSize: Long, dsuInstallation: DsuInstallationSource, installationJob: InstallationJob, callbacks: Callbacks) : this(application, userdataSize, dsuInstallation, installationJob, GsiNames.DEFAULT_SLOT, false, callbacks) {

    }

    /**
     * @param dsuSlot  the slot gsid stores the installation under.
     * @param multiGsi when true other installed slots are left alone and only `dsuSlot`
     *                 has to be free, otherwise any installed DSU blocks the installation.
     */
    constructor(application: Application, userdataSize: Long, dsuInstallation: DsuInstallationSource, installationJob: InstallationJob, dsuSlot: String, multiGsi: Boolean, callbacks: Callbacks) : super() {
        this.application = application
        this.userdataSize = userdataSize
        this.dsuInstallation = dsuInstallation
        this.installationJob = installationJob
        this.dsuSlot = dsuSlot
        this.multiGsi = multiGsi
        this.callbacks = callbacks
    }

    private class MappedMemoryBuffer : AutoCloseable {
        var buffer: ByteBuffer? = null

        constructor(buffer: ByteBuffer) {
            this.buffer = buffer
        }

        override fun close() {
            if ((buffer != null)) {
                SharedMemory.unmap(buffer!!)
                buffer = null
            }
        }
    }

    /** Blocks until the installation is over, so it must be called from a background thread. */
    fun invoke() {
        try {
            runInstallation()
        } catch (e: Exception) {
            Log.e(TAG, "Installation failed.", e)
            if (!installationJob.isCancelled()) {
                callbacks.onInstallationError(InstallationStep.ERROR, java.lang.String.valueOf(e))
            }
            if (multiGsi) {
                discardPartialInstallation()
            }
        }
    }

    /** Throws away what was allocated so far, without touching any other installed GSI. */
    private fun discardPartialInstallation() {
        if (!multiGsi) {
            remove()
            return
        }
        try {
            abort()
            removeSlot(dsuSlot)
        } catch (e: RuntimeException) {
            Log.w(TAG, ("Unable to discard the partial installation of slot " + dsuSlot), e)
        }
    }

    private fun publishProgress(bytesRead: Long, totalBytes: Long, partition: String) {
        var progress: Float = 0f
        if (((totalBytes != 0L) && (bytesRead != 0L))) {
            progress = ((bytesRead).toFloat() / (totalBytes).toFloat())
        }
        callbacks.onInstallationProgressUpdate(progress, partition)
    }

    private fun installWritablePartition(partition: String, partitionSize: Long, readOnly: Boolean) {
        // Creating a partition blocks until it is fully allocated, so it runs on its own thread
        // while this one keeps reporting how far gsid got
        val creator: Thread = Thread({
            createNewPartition(partition, partitionSize, readOnly)
        }, ("dsu-create-" + partition))
        creator.start()
        publishProgress(0L, partitionSize, partition)
        var prevInstalledSize: Long = 0L
        while (creator.isAlive()) {
            val progress: GsiProgress? = getInstallationProgress()
            val installedSize: Long = (if ((progress != null)) progress!!.bytes_processed else 0L)
            if ((installedSize > (prevInstalledSize + MIN_PROGRESS_TO_PUBLISH))) {
                prevInstalledSize = installedSize
                publishProgress(installedSize, partitionSize, partition)
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        if (!closePartition()) {
            Log.e(TAG, (("Failed to install " + partition) + " partition"))
            installationJob.cancel()
            callbacks.onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition)
            return
        }
        // Ensure a 100% mark is published
        if ((prevInstalledSize != partitionSize)) {
            publishProgress(partitionSize, partitionSize, partition)
        }
        Log.d(TAG, ((((("Partition " + partition) + " installed, readOnly: ") + readOnly) + ", partitionSize: ") + partitionSize))
    }

    @Throws(IOException::class, ErrnoException::class)
    private fun installImage(partition: String, uncompressedSize: Long, inputStream: InputStream, readOnly: Boolean) {
        val sis: SparseInputStream = SparseInputStream(BufferedInputStream(inputStream))
        val partitionSize: Long = (if ((sis.getUnsparseSize() != -1L)) sis.getUnsparseSize() else uncompressedSize)
        callbacks.onCreatePartition(partition)
        createNewPartition(partition, partitionSize, readOnly)
        if (installationJob.isCancelled()) {
            return
        }
        callbacks.onInstallationStepUpdate(InstallationStep.INSTALLING_ROOTED)
        SharedMemory.create(("dsu_buffer_" + partition), SHARED_MEM_SIZE).use { sharedMemory ->
            MappedMemoryBuffer(sharedMemory.mapReadWrite()).use { mappedBuffer ->
                val fdDup: ParcelFileDescriptor = getFdDup(sharedMemory)
                setAshmem(fdDup, (sharedMemory.getSize()).toLong())
                publishProgress(0L, partitionSize, partition)
                var installedSize: Long = 0L
                val readBuffer: ByteArray = ByteArray(sharedMemory.getSize())
                val buffer: ByteBuffer = mappedBuffer.buffer!!
                var numBytesRead: Int = 0
                while (((sis.read(readBuffer, 0, readBuffer.size)).also { numBytesRead = it } > 0)) {
                    if (installationJob.isCancelled()) {
                        return
                    }
                    buffer.position(0)
                    buffer.put(readBuffer, 0, numBytesRead)
                    submitFromAshmem((numBytesRead).toLong())
                    installedSize += (numBytesRead).toLong()
                    publishProgress(installedSize, partitionSize, partition)
                }
                publishProgress(partitionSize, partitionSize, partition)
            }
        }
        if (!closePartition()) {
            Log.d(TAG, (("Failed to install " + partition) + " partition"))
            installationJob.cancel()
            callbacks.onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition)
            return
        }
        Log.d(TAG, ((((("Partition " + partition) + " installed, readOnly: ") + readOnly) + ", partitionSize: ") + partitionSize))
    }

    @Throws(IOException::class, ErrnoException::class)
    private fun installStreamingZipUpdate(inputStream: InputStream) {
        val zis: ZipInputStream = ZipInputStream(inputStream)
        var entry: ZipEntry? = null
        while (((zis.getNextEntry()).also { entry = it } != null)) {
            val fileName: String = entry!!.getName()
            if (shouldInstallEntry(fileName)) {
                installImageFromEntry(entry!!, zis)
            } else {
                Log.d(TAG, (fileName + " installation is not supported, skip it."))
            }
            if (installationJob.isCancelled()) {
                break
            }
        }
    }

    @Throws(IOException::class, ErrnoException::class)
    private fun installImageFromEntry(entry: ZipEntry, inputStream: InputStream) {
        val fileName: String = entry.getName()
        Log.d(TAG, ("Installing: " + fileName))
        val partitionName: String = fileName.substring(0, (fileName.length - ".img".length))
        installImage(partitionName, entry.getSize(), inputStream, true)
    }

    @Throws(IOException::class, ErrnoException::class)
    private fun runInstallation() {
        setDynProp()
        if (isInUse()) {
            callbacks.onInstallationError(InstallationStep.ERROR_ALREADY_RUNNING_DYN_OS, "")
            return
        }
        if (multiGsi) {
            // Other GSIs may stay, only this slot has to be free
            if (getInstalledSlots().contains(dsuSlot)) {
                callbacks.onInstallationError(InstallationStep.ERROR_REQUIRES_DISCARD_DSU, "")
                return
            }
        } else if (isInstalled()) {
            callbacks.onInstallationError(InstallationStep.ERROR_REQUIRES_DISCARD_DSU, "")
            return
        }
        forceStopDsu()
        if ((!startInstallation(dsuSlot) && multiGsi)) {
            Log.e(TAG, ("startInstallation() refused slot " + dsuSlot))
            callbacks.onInstallationError(InstallationStep.ERROR, ("Unable to start the installation in slot " + dsuSlot))
            return
        }
        installWritablePartition("userdata", userdataSize, false)
        if (!installationJob.isCancelled()) {
            when (dsuInstallation.getType()) {
                DsuInstallationSource.Type.SINGLE_SYSTEM_IMAGE -> {
                    installImage("system", dsuInstallation.getFileSize(), dsuInstallation.getUri())
                }
                DsuInstallationSource.Type.MULTIPLE_IMAGES -> {
                    installImages(dsuInstallation.getImages())
                }
                DsuInstallationSource.Type.DSU_PACKAGE -> {
                    installStreamingZipUpdate(openInputStream(dsuInstallation.getUri()))
                }
                DsuInstallationSource.Type.URL -> {
                    installStreamingZipUpdate(URL(dsuInstallation.getUri().toString()).openStream())
                }
                else -> {

                }
            }
        }
        if (installationJob.isCancelled()) {
            discardPartialInstallation()
            return
        }
        finishInstallation()
        Log.d(TAG, "Installation finished successfully.")
        callbacks.onInstallationSuccess()
    }

    @Throws(IOException::class, ErrnoException::class)
    private fun installImages(images: List<ImagePartition>) {
        for (image in images) {
            if (isPartitionSupported(image.getPartitionName())) {
                installImage(image.getPartitionName(), image.getFileSize(), image.getUri())
            }
            if (installationJob.isCancelled()) {
                break
            }
        }
    }

    @Throws(IOException::class, ErrnoException::class)
    private fun installImage(partitionName: String, uncompressedSize: Long, uri: Uri) {
        installImage(partitionName, uncompressedSize, openInputStream(uri), true)
    }

    @Throws(IOException::class)
    private fun openInputStream(uri: Uri): InputStream {
        val stream: InputStream? = application.getContentResolver().openInputStream(uri)
        if ((stream == null)) {
            throw FileNotFoundException(("Unable to open: " + uri))
        }
        return stream!!
    }

    private fun createNewPartition(partition: String, partitionSize: Long, readOnly: Boolean) {
        val result: Int = createPartition(partition, partitionSize, readOnly)
        if ((result != IGsiService.INSTALL_OK)) {
            Log.d(TAG, (((("Failed to create " + partition) + " partition, error code: ") + result) + " (check: IGsiService.INSTALL_*)"))
            installationJob.cancel()
            callbacks.onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition)
        }
    }

    companion object {
        private const val TAG: String = "DsuInstaller"

        private const val SHARED_MEM_SIZE: Int = 524288

        private const val MIN_PROGRESS_TO_PUBLISH: Long = (1L shl 27)

        private val UNSUPPORTED_PARTITIONS: List<String> = Arrays.asList("vbmeta", "boot", "userdata", "dtbo", "super_empty", "system_other", "scratch")

        private fun isPartitionSupported(partitionName: String): Boolean {
            return !UNSUPPORTED_PARTITIONS.contains(partitionName)
        }

        // getFdDup() is a hidden API, so it has to be reached through HiddenApiBypass
        private fun getFdDup(sharedMemory: SharedMemory): ParcelFileDescriptor {
            try {
                return (HiddenApiBypass.invoke(sharedMemory.javaClass, sharedMemory, "getFdDup") as ParcelFileDescriptor)
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException("Unable to duplicate the shared memory fd.", e)
            }
        }

        private fun shouldInstallEntry(name: String): Boolean {
            if (!name.endsWith(".img")) {
                return false
            }
            val partitionName: String = name.substring(0, (name.length - ".img".length))
            return isPartitionSupported(partitionName)
        }
    }
}
