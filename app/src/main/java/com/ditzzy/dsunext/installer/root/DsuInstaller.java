package com.ditzzy.dsunext.installer.root;

import android.app.Application;
import android.gsi.GsiProgress;
import android.gsi.IGsiService;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.util.Log;

import com.ditzzy.dsunext.model.DsuInstallationSource;
import com.ditzzy.dsunext.model.ImagePartition;
import com.ditzzy.dsunext.preparation.InstallationStep;
import com.ditzzy.dsunext.util.InstallationJob;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
public final class DsuInstaller extends DynamicSystemImpl {

    public interface Callbacks {
        void onInstallationError(InstallationStep error, String errorInfo);

        void onInstallationProgressUpdate(float progress, String partition);

        void onCreatePartition(String partition);

        void onInstallationStepUpdate(InstallationStep step);

        void onInstallationSuccess();
    }

    private static final String TAG = "DsuInstaller";
    private static final String DEFAULT_SLOT = "dsu";
    private static final int SHARED_MEM_SIZE = 524288;
    private static final long MIN_PROGRESS_TO_PUBLISH = 1L << 27;

    private static final List<String> UNSUPPORTED_PARTITIONS = Arrays.asList(
            "vbmeta", "boot", "userdata", "dtbo", "super_empty", "system_other", "scratch");

    private final Application application;
    private final long userdataSize;
    private final DsuInstallationSource dsuInstallation;
    private final InstallationJob installationJob;
    private final Callbacks callbacks;

    public DsuInstaller(
            Application application,
            long userdataSize,
            DsuInstallationSource dsuInstallation,
            InstallationJob installationJob,
            Callbacks callbacks) {
        this.application = application;
        this.userdataSize = userdataSize;
        this.dsuInstallation = dsuInstallation;
        this.installationJob = installationJob;
        this.callbacks = callbacks;
    }

    // Wraps the mapped buffer so it is always unmapped once the shared memory is done with
    private static final class MappedMemoryBuffer implements AutoCloseable {

        private ByteBuffer buffer;

        MappedMemoryBuffer(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void close() {
            if (buffer != null) {
                SharedMemory.unmap(buffer);
                buffer = null;
            }
        }
    }

    /** Blocks until the installation is over, so it must be called from a background thread. */
    public void invoke() {
        try {
            runInstallation();
        } catch (Exception e) {
            Log.e(TAG, "Installation failed.", e);
            // Canceling closes streams on purpose, which isn't an error worth reporting
            if (!installationJob.isCancelled()) {
                callbacks.onInstallationError(InstallationStep.ERROR, String.valueOf(e));
            }
        }
    }

    private static boolean isPartitionSupported(String partitionName) {
        return !UNSUPPORTED_PARTITIONS.contains(partitionName);
    }

    // getFdDup() is a hidden API, so it has to be reached through HiddenApiBypass
    private static ParcelFileDescriptor getFdDup(SharedMemory sharedMemory) {
        try {
            return (ParcelFileDescriptor) HiddenApiBypass.invoke(
                    sharedMemory.getClass(), sharedMemory, "getFdDup");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to duplicate the shared memory fd.", e);
        }
    }

    private static boolean shouldInstallEntry(String name) {
        if (!name.endsWith(".img")) {
            return false;
        }
        String partitionName = name.substring(0, name.length() - ".img".length());
        return isPartitionSupported(partitionName);
    }

    private void publishProgress(long bytesRead, long totalBytes, String partition) {
        float progress = 0F;
        if (totalBytes != 0L && bytesRead != 0L) {
            progress = (float) bytesRead / (float) totalBytes;
        }
        callbacks.onInstallationProgressUpdate(progress, partition);
    }

    private void installWritablePartition(String partition, long partitionSize, boolean readOnly) {
        // Creating a partition blocks until it is fully allocated, so it runs on its own thread
        // while this one keeps reporting how far gsid got
        Thread creator = new Thread(
                () -> createNewPartition(partition, partitionSize, readOnly), "dsu-create-" + partition);
        creator.start();

        publishProgress(0L, partitionSize, partition);
        long prevInstalledSize = 0L;
        while (creator.isAlive()) {
            GsiProgress progress = getInstallationProgress();
            long installedSize = progress != null ? progress.bytes_processed : 0L;
            if (installedSize > prevInstalledSize + MIN_PROGRESS_TO_PUBLISH) {
                prevInstalledSize = installedSize;
                publishProgress(installedSize, partitionSize, partition);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (!closePartition()) {
            Log.e(TAG, "Failed to install " + partition + " partition");
            installationJob.cancel();
            callbacks.onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition);
            return;
        }

        // Ensure a 100% mark is published
        if (prevInstalledSize != partitionSize) {
            publishProgress(partitionSize, partitionSize, partition);
        }
        Log.d(TAG, "Partition " + partition + " installed, readOnly: " + readOnly
                + ", partitionSize: " + partitionSize);
    }

    private void installImage(
            String partition,
            long uncompressedSize,
            InputStream inputStream,
            boolean readOnly) throws IOException, ErrnoException {
        SparseInputStream sis = new SparseInputStream(new BufferedInputStream(inputStream));
        long partitionSize = sis.getUnsparseSize() != -1L ? sis.getUnsparseSize() : uncompressedSize;

        callbacks.onCreatePartition(partition);
        createNewPartition(partition, partitionSize, readOnly);
        if (installationJob.isCancelled()) {
            return;
        }
        callbacks.onInstallationStepUpdate(InstallationStep.INSTALLING_ROOTED);

        try (SharedMemory sharedMemory = SharedMemory.create("dsu_buffer_" + partition, SHARED_MEM_SIZE);
             MappedMemoryBuffer mappedBuffer = new MappedMemoryBuffer(sharedMemory.mapReadWrite())) {
            ParcelFileDescriptor fdDup = getFdDup(sharedMemory);
            setAshmem(fdDup, sharedMemory.getSize());
            publishProgress(0L, partitionSize, partition);

            long installedSize = 0L;
            byte[] readBuffer = new byte[sharedMemory.getSize()];
            ByteBuffer buffer = mappedBuffer.buffer;
            int numBytesRead;
            while ((numBytesRead = sis.read(readBuffer, 0, readBuffer.length)) > 0) {
                if (installationJob.isCancelled()) {
                    return;
                }
                buffer.position(0);
                buffer.put(readBuffer, 0, numBytesRead);
                submitFromAshmem(numBytesRead);
                installedSize += numBytesRead;
                publishProgress(installedSize, partitionSize, partition);
            }
            publishProgress(partitionSize, partitionSize, partition);
        }

        if (!closePartition()) {
            Log.d(TAG, "Failed to install " + partition + " partition");
            installationJob.cancel();
            callbacks.onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition);
            return;
        }
        Log.d(TAG, "Partition " + partition + " installed, readOnly: " + readOnly
                + ", partitionSize: " + partitionSize);
    }

    private void installStreamingZipUpdate(InputStream inputStream) throws IOException, ErrnoException {
        ZipInputStream zis = new ZipInputStream(inputStream);
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String fileName = entry.getName();
            if (shouldInstallEntry(fileName)) {
                installImageFromEntry(entry, zis);
            } else {
                Log.d(TAG, fileName + " installation is not supported, skip it.");
            }
            if (installationJob.isCancelled()) {
                break;
            }
        }
    }

    private void installImageFromEntry(ZipEntry entry, InputStream inputStream)
            throws IOException, ErrnoException {
        String fileName = entry.getName();
        Log.d(TAG, "Installing: " + fileName);
        String partitionName = fileName.substring(0, fileName.length() - ".img".length());
        installImage(partitionName, entry.getSize(), inputStream, true);
    }

    private void runInstallation() throws IOException, ErrnoException {
        setDynProp();
        if (isInUse()) {
            callbacks.onInstallationError(InstallationStep.ERROR_ALREADY_RUNNING_DYN_OS, "");
            return;
        }
        if (isInstalled()) {
            callbacks.onInstallationError(InstallationStep.ERROR_REQUIRES_DISCARD_DSU, "");
            return;
        }

        forceStopDsu();
        startInstallation(DEFAULT_SLOT);
        installWritablePartition("userdata", userdataSize, false);

        if (!installationJob.isCancelled()) {
            switch (dsuInstallation.getType()) {
                case SINGLE_SYSTEM_IMAGE:
                    installImage("system", dsuInstallation.getFileSize(), dsuInstallation.getUri());
                    break;
                case MULTIPLE_IMAGES:
                    installImages(dsuInstallation.getImages());
                    break;
                case DSU_PACKAGE:
                    installStreamingZipUpdate(openInputStream(dsuInstallation.getUri()));
                    break;
                case URL:
                    installStreamingZipUpdate(new URL(dsuInstallation.getUri().toString()).openStream());
                    break;
                default:
                    break;
            }
        }

        if (installationJob.isCancelled()) {
            // Whatever was allocated so far is useless, and would block the next installation
            remove();
            return;
        }
        finishInstallation();
        Log.d(TAG, "Installation finished successfully.");
        callbacks.onInstallationSuccess();
    }

    private void installImages(List<ImagePartition> images) throws IOException, ErrnoException {
        for (ImagePartition image : images) {
            if (isPartitionSupported(image.getPartitionName())) {
                installImage(image.getPartitionName(), image.getFileSize(), image.getUri());
            }
            if (installationJob.isCancelled()) {
                break;
            }
        }
    }

    private void installImage(String partitionName, long uncompressedSize, Uri uri)
            throws IOException, ErrnoException {
        installImage(partitionName, uncompressedSize, openInputStream(uri), true);
    }

    private InputStream openInputStream(Uri uri) throws IOException {
        InputStream stream = application.getContentResolver().openInputStream(uri);
        if (stream == null) {
            throw new FileNotFoundException("Unable to open: " + uri);
        }
        return stream;
    }

    private void createNewPartition(String partition, long partitionSize, boolean readOnly) {
        int result = createPartition(partition, partitionSize, readOnly);
        if (result != IGsiService.INSTALL_OK) {
            Log.d(TAG, "Failed to create " + partition + " partition, error code: " + result
                    + " (check: IGsiService.INSTALL_*)");
            installationJob.cancel();
            callbacks.onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition);
        }
    }
}
