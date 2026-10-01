package com.ditzzy.dsunext.preparation;

import android.net.Uri;

import com.ditzzy.dsunext.core.StorageManager;
import com.ditzzy.dsunext.model.DsuConstants;
import com.ditzzy.dsunext.model.DsuInstallationSource;
import com.ditzzy.dsunext.model.Session;
import com.ditzzy.dsunext.util.InstallationJob;

import java.io.IOException;
import java.io.InputStream;

/**
 * Turns whatever the user picked into something DSU can consume, then hands it over through
 * {@link Callbacks#onFinished(DsuInstallationSource)}.
 */
public final class Preparation {

    public interface Callbacks {
        void onStepUpdate(InstallationStep step);

        void onProgressUpdate(float progress);

        void onCanceled();

        void onFinished(DsuInstallationSource preparedSource);
    }

    // Anything at or above this size (Int.MAX_VALUE * 1.5) can't be trusted to report its size in
    // the gzip footer, since ISIZE only stores the uncompressed size modulo 2^32
    private static final double GZIP_FOOTER_SIZE_LIMIT = Integer.MAX_VALUE * 1.5;

    private final StorageManager storageManager;
    private final Session session;
    private final InstallationJob job;
    private final Callbacks callbacks;

    private final long userSelectedImageSize;
    private final Uri userSelectedFileUri;

    public Preparation(
            StorageManager storageManager,
            Session session,
            InstallationJob job,
            Callbacks callbacks) {
        this.storageManager = storageManager;
        this.session = session;
        this.job = job;
        this.callbacks = callbacks;
        this.userSelectedImageSize = session.getUserSelection().getUserSelectedImageSize();
        this.userSelectedFileUri = session.getUserSelection().getSelectedFileUri();
    }

    public void invoke() throws IOException {
        if (session.getPreferences().isUseBuiltinInstaller() && session.isRoot()) {
            prepareRooted();
            return;
        }
        prepareForDsu();
    }

    private void prepareRooted() throws IOException {
        DsuInstallationSource source;
        switch (getExtension(userSelectedFileUri)) {
            case "img":
                source = DsuInstallationSource.singleSystemImage(
                        userSelectedFileUri, storageManager.getFilesizeFromUri(userSelectedFileUri));
                break;
            case "xz":
            case "gz":
            case "gzip": {
                PreparedFile extracted = extractFile(userSelectedFileUri, "system");
                source = DsuInstallationSource.singleSystemImage(extracted.getUri(), extracted.getSize());
                break;
            }
            case "zip":
                source = DsuInstallationSource.dsuPackage(userSelectedFileUri);
                break;
            default:
                throw new IOException("Unsupported filetype");
        }
        finish(source);
    }

    private void prepareForDsu() throws IOException {
        storageManager.cleanWorkspaceFolder(true);

        String fileExtension = getExtension(userSelectedFileUri);
        PreparedFile prepared;
        switch (fileExtension) {
            case "xz":
                prepared = prepareXz(userSelectedFileUri);
                break;
            case "img":
                prepared = prepareImage(userSelectedFileUri);
                break;
            case "gz":
            case "gzip":
                prepared = prepareGz(userSelectedFileUri);
                break;
            case "zip":
                prepared = prepareZip(userSelectedFileUri);
                break;
            default:
                throw new IOException("Unsupported filetype");
        }

        DsuInstallationSource source = fileExtension.equals("zip")
                ? DsuInstallationSource.dsuPackage(prepared.getUri())
                : DsuInstallationSource.singleSystemImage(prepared.getUri(), prepared.getSize());

        callbacks.onStepUpdate(InstallationStep.WAITING_USER_CONFIRMATION);

        // Only the gz is kept, unless the job was canceled, in which case nothing should be left
        storageManager.cleanWorkspaceFolder(job.isCancelled());
        finish(source);
    }

    private void finish(DsuInstallationSource source) {
        if (job.isCancelled()) {
            callbacks.onCanceled();
        } else {
            callbacks.onFinished(source);
        }
    }

    private PreparedFile prepareZip(Uri zipFile) throws IOException {
        return new PreparedFile(getSafeUri(zipFile), -1L);
    }

    private PreparedFile prepareXz(Uri xzFile) throws IOException {
        callbacks.onStepUpdate(InstallationStep.DECOMPRESSING_XZ);
        PreparedFile imgFile = new FileUnpacker(
                storageManager, xzFile, getFileName(xzFile), job, callbacks::onProgressUpdate).unpack();
        return prepareImage(imgFile.getUri());
    }

    private PreparedFile prepareImage(Uri imageFile) throws IOException {
        callbacks.onStepUpdate(InstallationStep.COMPRESSING_TO_GZ);
        PreparedFile compressed = new FileUnpacker(
                storageManager,
                imageFile,
                getFileName(imageFile) + ".img.gz",
                job,
                callbacks::onProgressUpdate).pack();
        return new PreparedFile(compressed.getUri(), storageManager.getFilesizeFromUri(imageFile));
    }

    private PreparedFile prepareGz(Uri gzFile) throws IOException {
        Uri uri = getSafeUri(gzFile);
        if (userSelectedImageSize != DsuConstants.DEFAULT_IMAGE_SIZE) {
            return new PreparedFile(uri, userSelectedImageSize);
        }

        callbacks.onStepUpdate(InstallationStep.PROCESSING);
        long fileSize = storageManager.getFilesizeFromUri(uri);

        // If the gz is small enough, try to get the image size by reading its last four bytes
        if (fileSize < GZIP_FOOTER_SIZE_LIMIT) {
            long imageSize = readGzipFooterSize(uri, fileSize);
            // An image can't be smaller than its compressed file, otherwise the value is wrong
            if (imageSize > fileSize) {
                return new PreparedFile(uri, imageSize);
            }
        }

        // The gz is too big, or the fast way returned a wrong value, so the file has to be
        // decompressed to find out its size. This is slow.
        callbacks.onStepUpdate(InstallationStep.DECOMPRESSING_GZIP);
        PreparedFile extracted = new FileUnpacker(
                storageManager, uri, getFileName(uri), job, callbacks::onProgressUpdate).unpack();
        return new PreparedFile(uri, extracted.getSize());
    }

    // Returns -1 when the footer could not be read
    private long readGzipFooterSize(Uri uri, long fileSize) throws IOException {
        if (fileSize < 4L) {
            return -1L;
        }
        try (InputStream in = storageManager.openInputStream(uri)) {
            long toSkip = fileSize - 4L;
            long skipped = 0L;
            while (skipped < toSkip) {
                long n = in.skip(toSkip - skipped);
                if (n <= 0L) {
                    return -1L;
                }
                skipped += n;
            }

            byte[] footer = new byte[4];
            int total = 0;
            while (total < footer.length) {
                int n = in.read(footer, total, footer.length - total);
                if (n < 0) {
                    return -1L;
                }
                total += n;
            }

            // The footer is stored in little endian
            return (footer[0] & 0xFFL)
                    | (footer[1] & 0xFFL) << 8
                    | (footer[2] & 0xFFL) << 16
                    | (footer[3] & 0xFFL) << 24;
        }
    }

    private PreparedFile extractFile(Uri uri, String partitionName) throws IOException {
        callbacks.onStepUpdate(InstallationStep.EXTRACTING_FILE);
        return new FileUnpacker(
                storageManager, uri, partitionName + ".img", job, callbacks::onProgressUpdate).unpack();
    }

    private Uri getSafeUri(Uri uri) throws IOException {
        callbacks.onStepUpdate(InstallationStep.COPYING_FILE);
        return storageManager.getUriSafe(uri);
    }

    private String getFileName(Uri uri) {
        String name = storageManager.getFilenameFromUri(uri);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private String getExtension(Uri uri) {
        String name = storageManager.getFilenameFromUri(uri);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }
}
