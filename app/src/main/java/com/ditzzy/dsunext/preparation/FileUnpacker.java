package com.ditzzy.dsunext.preparation;

import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import com.ditzzy.dsunext.core.StorageManager;
import com.ditzzy.dsunext.util.InstallationJob;

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.utils.InputStreamStatistics;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.LongConsumer;

/** Compresses or decompresses an image into the workspace folder. */
public final class FileUnpacker {

    public interface ProgressListener {
        void onProgress(float progress);
    }

    private static final int BUFFER_SIZE = 64 * 1024;

    private final StorageManager storageManager;
    private final Uri inputFile;
    private final InstallationJob job;
    private final ProgressListener progressListener;
    private final DocumentFile finalFile;
    private final long inputFileSize;

    private int lastPublishedPermille = -1;

    public FileUnpacker(
            StorageManager storageManager,
            Uri inputFile,
            String outputFile,
            InstallationJob job,
            ProgressListener progressListener) throws IOException {
        this.storageManager = storageManager;
        this.inputFile = inputFile;
        this.job = job;
        this.progressListener = progressListener;
        this.finalFile = storageManager.createDocumentFile(outputFile);
        this.inputFileSize = storageManager.getFilesizeFromUri(inputFile);
    }

    public PreparedFile pack() throws IOException {
        try (InputStream in = storageManager.openInputStream(inputFile);
             OutputStream raw = storageManager.openOutputStream(finalFile.getUri());
             GzipCompressorOutputStream out = new GzipCompressorOutputStream(raw)) {
            copy(in, out, readBytes -> updateProgress(readBytes));
        }
        return new PreparedFile(finalFile.getUri(), storageManager.getFilesizeFromUri(finalFile.getUri()));
    }

    public PreparedFile unpack() throws IOException {
        String filename = storageManager.getFilenameFromUri(inputFile);

        try (InputStream in = storageManager.openInputStream(inputFile);
             OutputStream out = storageManager.openOutputStream(finalFile.getUri())) {
            InputStream archive;
            if (filename.endsWith("xz")) {
                archive = new XZCompressorInputStream(in);
            } else if (filename.endsWith("gz") || filename.endsWith("gzip")) {
                archive = new GzipCompressorInputStream(in);
            } else {
                throw new IOException("File type not supported");
            }

            try (InputStream decompressed = archive) {
                // Progress is tracked against the compressed size, since the final size is unknown
                InputStreamStatistics statistics = (InputStreamStatistics) decompressed;
                copy(decompressed, out, readBytes -> updateProgress(statistics.getCompressedCount()));
            }
        }
        return new PreparedFile(finalFile.getUri(), storageManager.getFilesizeFromUri(finalFile.getUri()));
    }

    private void copy(InputStream in, OutputStream out, LongConsumer onBytesRead) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        int read;
        while (!job.isCancelled() && (read = in.read(buffer)) != -1) {
            total += read;
            onBytesRead.accept(total);
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    private void updateProgress(long processedBytes) {
        if (inputFileSize <= 0L) {
            return;
        }
        float progress = (float) processedBytes / (float) inputFileSize;

        // Publishing after every chunk would flood the UI, so only report meaningful changes
        int permille = (int) (progress * 1000F);
        if (permille != lastPublishedPermille) {
            lastPublishedPermille = permille;
            progressListener.onProgress(Math.min(progress, 1F));
        }
    }
}
