package com.ditzzy.dsunext.core

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ditzzy.dsunext.util.FilenameUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Handles every file operation done by the app, most of them go through the folder the user
 * granted access to using the Storage Access Framework.
 */
class StorageManager {
    private val appContext: Context

    @Volatile
    private var rwPathAllowedByUser: String = ""

    private var workspaceFolder: DocumentFile? = null

    constructor(appContext: Context) {
        this.appContext = appContext.getApplicationContext()
    }

    /**
     * Checks if the folder was granted with read and write access. The folder is also remembered
     * as the workspace location when it is valid, so this must run before anything else uses it.
     * Does disk and provider I/O, so it must not be called from the main thread.
     */
    fun arePermissionsGrantedToFolder(path: String): Boolean {
        for (folder in appContext.getContentResolver().getPersistedUriPermissions()) {
            if (path.equals(folder.getUri().toString())) {
                // The folder may be gone (eg: deleted by the user, or app data restored from a
                // backup), in which case the user has to grant a folder again
                val documentFile: DocumentFile? = DocumentFile.fromTreeUri(appContext, folder.getUri())
                if (((documentFile == null) || !documentFile!!.exists())) {
                    return false
                }
                if ((folder.isWritePermission() && folder.isReadPermission())) {
                    rwPathAllowedByUser = path
                    return true
                }
            } else {
                appContext.revokeUriPermission(folder.getUri(), (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            }
        }
        return false
    }

    // Creates or obtains the subfolder, inside the folder picked by the user, that holds the
    // images prepared to be consumed by DSU
    @Synchronized
    @Throws(IOException::class)
    private fun getWorkspaceFolder(): DocumentFile {
        if (((workspaceFolder != null) && workspaceFolder!!.canRead())) {
            return workspaceFolder!!
        }
        if (rwPathAllowedByUser.isEmpty()) {
            throw IOException("Allowed path is empty, storage permission must be granted again.")
        }
        val writableDir: DocumentFile? = DocumentFile.fromTreeUri(appContext, Uri.parse(rwPathAllowedByUser))
        if ((writableDir == null)) {
            throw IOException("Workspace folder cannot be null.")
        }
        var folder: DocumentFile? = writableDir!!.findFile(WORKSPACE_FOLDER)
        if ((folder == null)) {
            folder = writableDir!!.createDirectory(WORKSPACE_FOLDER)
        }
        if ((folder == null)) {
            throw IOException("Unable to create the workspace folder.")
        }
        workspaceFolder = folder
        return workspaceFolder!!
    }

    @Throws(IOException::class)
    fun cleanWorkspaceFolder(deleteAlsoGzFile: Boolean) {
        for (file in getWorkspaceFolder().listFiles()!!) {
            val name: String? = file.getName()
            if (((deleteAlsoGzFile || (name == null)) || !name!!.endsWith("gz"))) {
                file.delete()
            }
        }
    }

    @Throws(IOException::class)
    fun createDocumentFile(filename: String): DocumentFile {
        val file: DocumentFile? = getWorkspaceFolder().createFile("application/octet-stream", filename)
        if ((file == null)) {
            throw IOException(("Unable to create file: " + filename))
        }
        return file!!
    }

    /**
     * Returns an uri that can be resolved to a real path. Uris handed out by the downloads
     * provider ("msf:" ids) can't be, so those files are copied into the workspace first.
     */
    @Throws(IOException::class)
    fun getUriSafe(uri: Uri): Uri {
        if (java.lang.String.valueOf(uri.getPath()).contains("msf:")) {
            return copyFileToWorkspace(uri)
        }
        return uri
    }

    @Throws(IOException::class)
    private fun copyFileToWorkspace(inputFile: Uri): Uri {
        val clone: DocumentFile = createDocumentFile(getFilenameFromUri(inputFile))
        openInputStream(inputFile).use { `in` ->
            openOutputStream(clone.getUri()).use { `out` ->
                val buffer: ByteArray = ByteArray(COPY_BUFFER_SIZE)
                var read: Int = 0
                while (((`in`.read(buffer)).also { read = it } != -1)) {
                    `out`.write(buffer, 0, read)
                }
            }
        }
        return clone.getUri()
    }

    @Throws(IOException::class)
    fun writeStringToExternalFileDir(content: String, filename: String): String {
        val externalFilesDir: File? = appContext.getExternalFilesDir(null)
        if ((externalFilesDir == null)) {
            throw IOException("externalFilesDir cannot be null.")
        }
        val newFile: File = File(externalFilesDir, filename)
        if ((newFile.exists() && !newFile.delete())) {
            throw IOException(("Unable to replace: " + newFile.getAbsolutePath()))
        }
        if (!newFile.createNewFile()) {
            throw IOException(("Unable to create: " + newFile.getAbsolutePath()))
        }
        Files.write(newFile.toPath(), content.toByteArray(StandardCharsets.UTF_8))
        return newFile.getAbsolutePath()
    }

    @Throws(IOException::class)
    fun writeStringToUri(content: String, uri: Uri) {
        openOutputStream(uri).use { `out` ->
            `out`.write(content.toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Throws(IOException::class)
    fun readFileFromAssets(filename: String): String {
        appContext.getAssets().`open`(filename).use { `in` ->
            val buffer: ByteArrayOutputStream = ByteArrayOutputStream()
            val chunk: ByteArray = ByteArray(4096)
            var read: Int = 0
            while (((`in`.read(chunk)).also { read = it } != -1)) {
                buffer.write(chunk, 0, read)
            }
            return String(buffer.toByteArray(), StandardCharsets.UTF_8)
        }
    }

    fun getFilenameFromUri(uri: Uri): String {
        return FilenameUtils.queryName(appContext.getContentResolver(), uri)
    }

    fun getFilesizeFromUri(uri: Uri): Long {
        return FilenameUtils.getLengthFromFile(appContext, uri)
    }

    /**
     * Size of the file as the filesystem sees it (fstat), falling back to the provider's reported
     * length. Providers can report a stale length for files that were rewritten.
     */
    fun getRealFilesize(uri: Uri): Long {
        try {
            appContext.getContentResolver().openFileDescriptor(uri, "r").use { pfd ->
                if (((pfd != null) && (pfd.getStatSize() >= 0L))) {
                    return pfd.getStatSize()
                }
            }
        } catch (ignored: IOException) {

        } catch (ignored: RuntimeException) {

        }
        return getFilesizeFromUri(uri)
    }

    @Throws(IOException::class)
    fun openInputStream(uri: Uri): InputStream {
        val stream: InputStream? = appContext.getContentResolver().openInputStream(uri)
        if ((stream == null)) {
            throw FileNotFoundException(("Unable to open: " + uri))
        }
        return stream!!
    }

    @Throws(IOException::class)
    fun openOutputStream(uri: Uri): OutputStream {
        val stream: OutputStream? = appContext.getContentResolver().openOutputStream(uri)
        if ((stream == null)) {
            throw FileNotFoundException(("Unable to open: " + uri))
        }
        return stream!!
    }

    companion object {
        private const val WORKSPACE_FOLDER: String = "workspace_dsunext"

        private const val COPY_BUFFER_SIZE: Int = (64 * 1024)
    }
}
