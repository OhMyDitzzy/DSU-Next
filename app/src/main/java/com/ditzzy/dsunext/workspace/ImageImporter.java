package com.ditzzy.dsunext.workspace;

import android.os.ParcelFileDescriptor;

import com.ditzzy.dsunext.installer.root.SparseInputStream;
import com.ditzzy.dsunext.yuki.ExtractResult;
import com.ditzzy.dsunext.yuki.Yuki;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

public final class ImageImporter {

    private static final int BUFFER = 256 * 1024;
    private static final String RAW_IMAGE = "raw.img";

    private ImageImporter() {
    }

    /** Takes ownership of {@code pfd}. Returns a JSON object with the name of the workspace. */
    public static String run(ParcelFileDescriptor pfd, String sourceName, String name,
            JobReporter reporter) throws IOException, JSONException {
        String nativeError = WorkspaceStore.nativeError();
        if (!nativeError.isEmpty()) {
            closeQuietly(pfd);
            throw new IOException(nativeError);
        }

        File workspace;
        try {
            workspace = WorkspaceStore.dir(name);
            if (workspace.exists()) {
                throw new IOException("A workspace named \"" + name + "\" already exists");
            }
            File base = new File(WorkspaceStore.BASE_DIR);
            if (!base.isDirectory() && !base.mkdirs()) {
                throw new IOException("Cannot create " + WorkspaceStore.BASE_DIR);
            }
            if (!workspace.mkdirs()) {
                throw new IOException("Cannot create " + workspace);
            }
            WorkspaceStore.markBusy(workspace);
        } catch (IOException e) {
            closeQuietly(pfd);
            throw e;
        }

        boolean success = false;
        try (ImageSource source = new ImageSource(pfd, sourceName)) {
            reporter.log("- Reading " + sourceName);
            File staging = new File(workspace, WorkspaceStore.STAGING_DIR);
            String imagePath = prepareImage(source, staging, reporter);

            long imageSize = new File(imagePath).length();
            long free = new File(WorkspaceStore.BASE_DIR).getUsableSpace();
            if (free < imageSize) {
                throw new IOException("Not enough free space in /data: the image needs about "
                        + mb(imageSize) + " MB, " + mb(free) + " MB are free");
            }

            reporter.progress(-1);
            ExtractResult result = Yuki.extractImage(
                    new File(imagePath), workspace, WorkspaceStore.PARTITION, reporter::log);
            for (String warning : result.getWarnings()) {
                reporter.log("! " + warning);
            }

            if (staging.exists()) {
                WorkspaceStore.deleteTree(staging.toPath());
            }

            Properties props = new Properties();
            props.setProperty(WorkspaceStore.K_PARTITION, WorkspaceStore.PARTITION);
            props.setProperty(WorkspaceStore.K_SOURCE, sourceName);
            props.setProperty(WorkspaceStore.K_FS_TYPE, result.getFileSystem());
            props.setProperty(WorkspaceStore.K_MOUNT_POINT, result.getMountPoint());
            props.setProperty(WorkspaceStore.K_IMAGE_SIZE, Long.toString(imageSize));
            props.setProperty(WorkspaceStore.K_IMPORTED_AT, Long.toString(System.currentTimeMillis()));
            props.setProperty(WorkspaceStore.K_DIRS, Long.toString(result.getDirectories()));
            props.setProperty(WorkspaceStore.K_FILES, Long.toString(result.getFiles()));
            props.setProperty(WorkspaceStore.K_SYMLINKS, Long.toString(result.getSymlinks()));
            props.setProperty(WorkspaceStore.K_BYTES, Long.toString(result.getBytes()));
            props.putAll(WorkspaceStore.readBuildInfo(workspace, WorkspaceStore.PARTITION));
            WorkspaceStore.writeProps(workspace, props);
            WorkspaceStore.clearBusy(workspace);

            reporter.progress(100);
            reporter.log("- Workspace ready: " + workspace);
            success = true;
            return new JSONObject().put("name", name).toString();
        } finally {
            if (!success) {
                try {
                    WorkspaceStore.deleteTree(workspace.toPath());
                } catch (IOException ignored) {
                    // Left over files show up as an incomplete workspace the user can delete
                }
            }
        }
    }

    /** Returns the path of a raw ext4 or EROFS image, staging a copy only when it has to. */
    private static String prepareImage(ImageSource source, File staging, JobReporter reporter)
            throws IOException {
        ImageFormat.Container kind = ImageFormat.detect(source);
        if (kind.isRawImage()) {
            reporter.log("- Raw " + kind + " image");
            return source.path();
        }
        if (kind == ImageFormat.Container.UNKNOWN) {
            throw new IOException("Unsupported file: expected an ext4 or EROFS image, or a zip, "
                    + "7z, tar, gz or xz archive that contains one");
        }
        if (!staging.isDirectory() && !staging.mkdirs()) {
            throw new IOException("Cannot create " + staging);
        }

        if (kind == ImageFormat.Container.SPARSE) {
            reporter.log("- Sparse image, converting to a raw image");
            File raw = new File(staging, RAW_IMAGE);
            unsparse(source.openStream(), raw, reporter);
            return requireRaw(raw);
        }

        File extracted = ArchiveExtractor.extract(source, kind, staging, reporter);
        try (ImageSource inner = new ImageSource(extracted)) {
            ImageFormat.Container innerKind = ImageFormat.detect(inner);
            if (innerKind.isRawImage()) {
                return extracted.getAbsolutePath();
            }
            if (innerKind == ImageFormat.Container.SPARSE) {
                reporter.log("- Sparse image, converting to a raw image");
                File raw = new File(staging, RAW_IMAGE);
                unsparse(inner.openStream(), raw, reporter);
                //noinspection ResultOfMethodCallIgnored
                extracted.delete();
                return requireRaw(raw);
            }
        }
        throw new IOException("The archive does not contain an ext4 or EROFS image");
    }

    private static String requireRaw(File raw) throws IOException {
        try (ImageSource check = new ImageSource(raw)) {
            if (!ImageFormat.detect(check).isRawImage()) {
                throw new IOException("The image is neither ext4 nor EROFS");
            }
        }
        return raw.getAbsolutePath();
    }

    private static void unsparse(InputStream in, File out, JobReporter reporter) throws IOException {
        try (SparseInputStream sparse = new SparseInputStream(new BufferedInputStream(in, BUFFER));
             OutputStream output = new FileOutputStream(out)) {
            long total = sparse.getUnsparseSize();
            long written = 0L;
            int lastPercent = -1;
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = sparse.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                written += read;
                int percent = total > 0L ? (int) Math.min(99L, written * 100L / total) : -1;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    reporter.progress(percent);
                }
            }
        }
    }

    private static long mb(long bytes) {
        return bytes / (1024L * 1024L);
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        try {
            pfd.close();
        } catch (IOException ignored) {
            // Nothing left to release
        }
    }
}
