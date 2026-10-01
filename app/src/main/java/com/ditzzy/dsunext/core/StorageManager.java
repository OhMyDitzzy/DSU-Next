package com.ditzzy.dsunext.core;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import com.ditzzy.dsunext.util.FilenameUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Handles every file operation done by the app, most of them go through the folder the user
 * granted access to using the Storage Access Framework.
 */
public final class StorageManager {

    private static final String WORKSPACE_FOLDER = "workspace_dsunext";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final Context appContext;

    private volatile String rwPathAllowedByUser = "";
    private DocumentFile workspaceFolder;

    public StorageManager(Context appContext) {
        this.appContext = appContext.getApplicationContext();
    }

    /**
     * Checks if the folder was granted with read and write access. The folder is also remembered
     * as the workspace location when it is valid, so this must run before anything else uses it.
     * Does disk and provider I/O, so it must not be called from the main thread.
     */
    public boolean arePermissionsGrantedToFolder(String path) {
        for (UriPermission folder : appContext.getContentResolver().getPersistedUriPermissions()) {
            if (path.equals(folder.getUri().toString())) {
                // The folder may be gone (eg: deleted by the user, or app data restored from a
                // backup), in which case the user has to grant a folder again
                DocumentFile documentFile = DocumentFile.fromTreeUri(appContext, folder.getUri());
                if (documentFile == null || !documentFile.exists()) {
                    return false;
                }
                if (folder.isWritePermission() && folder.isReadPermission()) {
                    rwPathAllowedByUser = path;
                    return true;
                }
            } else {
                // Permissions to any other folder are not needed, so they are revoked
                appContext.revokeUriPermission(
                        folder.getUri(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        }
        return false;
    }

    // Creates or obtains the subfolder, inside the folder picked by the user, that holds the
    // images prepared to be consumed by DSU
    private synchronized DocumentFile getWorkspaceFolder() throws IOException {
        if (workspaceFolder != null && workspaceFolder.canRead()) {
            return workspaceFolder;
        }

        if (rwPathAllowedByUser.isEmpty()) {
            throw new IOException("Allowed path is empty, storage permission must be granted again.");
        }

        DocumentFile writableDir = DocumentFile.fromTreeUri(appContext, Uri.parse(rwPathAllowedByUser));
        if (writableDir == null) {
            throw new IOException("Workspace folder cannot be null.");
        }

        DocumentFile folder = writableDir.findFile(WORKSPACE_FOLDER);
        if (folder == null) {
            folder = writableDir.createDirectory(WORKSPACE_FOLDER);
        }
        if (folder == null) {
            throw new IOException("Unable to create the workspace folder.");
        }
        workspaceFolder = folder;
        return workspaceFolder;
    }

    public void cleanWorkspaceFolder(boolean deleteAlsoGzFile) throws IOException {
        for (DocumentFile file : getWorkspaceFolder().listFiles()) {
            String name = file.getName();
            if (deleteAlsoGzFile || name == null || !name.endsWith("gz")) {
                file.delete();
            }
        }
    }

    public DocumentFile createDocumentFile(String filename) throws IOException {
        DocumentFile file = getWorkspaceFolder().createFile("application/octet-stream", filename);
        if (file == null) {
            throw new IOException("Unable to create file: " + filename);
        }
        return file;
    }

    /**
     * Returns an uri that can be resolved to a real path. Uris handed out by the downloads
     * provider ("msf:" ids) can't be, so those files are copied into the workspace first.
     */
    public Uri getUriSafe(Uri uri) throws IOException {
        if (String.valueOf(uri.getPath()).contains("msf:")) {
            return copyFileToWorkspace(uri);
        }
        return uri;
    }

    private Uri copyFileToWorkspace(Uri inputFile) throws IOException {
        DocumentFile clone = createDocumentFile(getFilenameFromUri(inputFile));
        try (InputStream in = openInputStream(inputFile);
             OutputStream out = openOutputStream(clone.getUri())) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return clone.getUri();
    }

    public String writeStringToExternalFileDir(String content, String filename) throws IOException {
        File externalFilesDir = appContext.getExternalFilesDir(null);
        if (externalFilesDir == null) {
            throw new IOException("externalFilesDir cannot be null.");
        }
        File newFile = new File(externalFilesDir, filename);
        if (newFile.exists() && !newFile.delete()) {
            throw new IOException("Unable to replace: " + newFile.getAbsolutePath());
        }
        if (!newFile.createNewFile()) {
            throw new IOException("Unable to create: " + newFile.getAbsolutePath());
        }
        Files.write(newFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return newFile.getAbsolutePath();
    }

    public void writeStringToUri(String content, Uri uri) throws IOException {
        try (OutputStream out = openOutputStream(uri)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    public String readFileFromAssets(String filename) throws IOException {
        try (InputStream in = appContext.getAssets().open(filename)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public String getFilenameFromUri(Uri uri) {
        return FilenameUtils.queryName(appContext.getContentResolver(), uri);
    }

    public long getFilesizeFromUri(Uri uri) {
        return FilenameUtils.getLengthFromFile(appContext, uri);
    }

    public InputStream openInputStream(Uri uri) throws IOException {
        InputStream stream = appContext.getContentResolver().openInputStream(uri);
        if (stream == null) {
            throw new FileNotFoundException("Unable to open: " + uri);
        }
        return stream;
    }

    public OutputStream openOutputStream(Uri uri) throws IOException {
        OutputStream stream = appContext.getContentResolver().openOutputStream(uri);
        if (stream == null) {
            throw new FileNotFoundException("Unable to open: " + uri);
        }
        return stream;
    }
}
