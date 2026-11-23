package com.ditzzy.dsunext.workspace

import android.os.ParcelFileDescriptor
import com.ditzzy.dsunext.installer.root.SparseInputStream
import com.ditzzy.dsunext.yuki.ExtractResult
import com.ditzzy.dsunext.yuki.Yuki
import com.ditzzy.dsunext.yuki.YukiLogger
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Properties

class ImageImporter {
    private constructor() {

    }

    companion object {
        private const val BUFFER: Int = (256 * 1024)

        private const val RAW_IMAGE: String = "raw.img"

        /** Takes ownership of `pfd`. Returns a JSON object with the name of the workspace. */
        @JvmStatic
        @Throws(IOException::class, JSONException::class)
        fun run(pfd: ParcelFileDescriptor, sourceName: String?, name: String, reporter: JobReporter): String {
            val nativeError: String = WorkspaceStore.nativeError()
            if (!nativeError.isEmpty()) {
                closeQuietly(pfd)
                throw IOException(nativeError)
            }
            var workspace: File? = null
            try {
                workspace = WorkspaceStore.dir(name)
                if (workspace!!.exists()) {
                    throw IOException((("A workspace named \"" + name) + "\" already exists"))
                }
                val base: File = WorkspaceStore.baseDir()
                if ((!base.isDirectory() && !base.mkdirs())) {
                    throw IOException(("Cannot create " + WorkspaceStore.baseDir()))
                }
                if (!workspace!!.mkdirs()) {
                    throw IOException(("Cannot create " + workspace))
                }
                WorkspaceStore.markBusy(workspace!!)
            } catch (e: IOException) {
                closeQuietly(pfd)
                throw e
            }
            var success: Boolean = false
            try {
                ImageSource(pfd, sourceName!!).use { source ->
                    reporter.log(("- Reading " + sourceName))
                    val staging: File = File(workspace, WorkspaceStore.STAGING_DIR)
                    val image: File = prepareImage(source, staging, reporter)
                    val imageSize: Long = (image.length()).toLong()
                    val free: Long = WorkspaceStore.baseDir().getUsableSpace()
                    if ((free < imageSize)) {
                        throw IOException((((("Not enough free space in /data: the image needs about " + mb(imageSize)) + " MB, ") + mb(free)) + " MB are free"))
                    }
                    reporter.progress(-1)
                    val result: ExtractResult = Yuki.extractImage(image, workspace!!, WorkspaceStore.PARTITION, YukiLogger(reporter::log))
                    for (warning in result.warnings) {
                        reporter.log(("! " + warning))
                    }
                    if (staging.exists()) {
                        WorkspaceStore.deleteTree(staging.toPath())
                    }
                    val props: Properties = Properties()
                    props.setProperty(WorkspaceStore.K_PARTITION, WorkspaceStore.PARTITION)
                    props.setProperty(WorkspaceStore.K_SOURCE, sourceName)
                    props.setProperty(WorkspaceStore.K_FS_TYPE, result.fileSystem)
                    props.setProperty(WorkspaceStore.K_MOUNT_POINT, result.mountPoint)
                    props.setProperty(WorkspaceStore.K_IMAGE_SIZE, java.lang.Long.toString(imageSize))
                    props.setProperty(WorkspaceStore.K_IMPORTED_AT, java.lang.Long.toString(System.currentTimeMillis()))
                    props.setProperty(WorkspaceStore.K_DIRS, java.lang.Long.toString(result.directories))
                    props.setProperty(WorkspaceStore.K_FILES, java.lang.Long.toString(result.files))
                    props.setProperty(WorkspaceStore.K_SYMLINKS, java.lang.Long.toString(result.symlinks))
                    props.setProperty(WorkspaceStore.K_BYTES, java.lang.Long.toString(result.bytes))
                    props.putAll(WorkspaceStore.readBuildInfo(workspace!!, WorkspaceStore.PARTITION))
                    WorkspaceStore.writeProps(workspace!!, props)
                    WorkspaceStore.clearBusy(workspace!!)
                    reporter.progress(100)
                    reporter.log(("- Workspace ready: " + workspace))
                    success = true
                    return JSONObject().put("name", name).toString()
                }
            } finally {
                if (!success) {
                    try {
                        WorkspaceStore.deleteTree(workspace!!.toPath())
                    } catch (ignored: IOException) {

                    }
                }
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun prepareImage(source: ImageSource, staging: File, reporter: JobReporter): File {
            val kind: ImageFormat.Container = ImageFormat.detect(source)
            if (kind.isRawImage()) {
                reporter.log((("- Raw " + kind) + " image"))
                if ((source.localFile() != null)) {
                    return source.localFile()!!
                }
                return stageRawImage(source, staging, reporter)
            }
            if ((kind == ImageFormat.Container.UNKNOWN)) {
                throw IOException(("Unsupported file: expected an ext4 or EROFS image, or a zip, " + "7z, tar, gz or xz archive that contains one"))
            }
            if ((!staging.isDirectory() && !staging.mkdirs())) {
                throw IOException(("Cannot create " + staging))
            }
            if ((kind == ImageFormat.Container.SPARSE)) {
                reporter.log("- Sparse image, converting to a raw image")
                val raw: File = File(staging, RAW_IMAGE)
                unsparse(source.openStream(), raw, reporter)
                return requireRaw(raw)
            }
            val extracted: File = ArchiveExtractor.extract(source, kind, staging, reporter)
            ImageSource(extracted).use { inner ->
                val innerKind: ImageFormat.Container = ImageFormat.detect(inner)
                if (innerKind.isRawImage()) {
                    return extracted
                }
                if ((innerKind == ImageFormat.Container.SPARSE)) {
                    reporter.log("- Sparse image, converting to a raw image")
                    val raw: File = File(staging, RAW_IMAGE)
                    unsparse(inner.openStream(), raw, reporter)
                    extracted.delete()
                    return requireRaw(raw)
                }
            }
            throw IOException("The archive does not contain an ext4 or EROFS image")
        }

        @Throws(IOException::class)
        private fun stageRawImage(source: ImageSource, staging: File, reporter: JobReporter): File {
            val expected: Long = source.size()
            val free: Long = staging.getParentFile()!!.getUsableSpace()
            if ((expected > free)) {
                throw IOException((((("Not enough free space in /data to copy the selected image: " + mb(expected)) + " MB needed, ") + mb(free)) + " MB are free"))
            }
            if ((!staging.isDirectory() && !staging.mkdirs())) {
                throw IOException(("Cannot create " + staging))
            }
            reporter.log("- Copying the selected image into the workspace")
            val raw: File = File(staging, RAW_IMAGE)
            val created: OutputStream = Files.newOutputStream(raw.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            try {
                created.use { output ->
                    source.openStream().use { input ->
                        val buffer: ByteArray = ByteArray(BUFFER)
                        var written: Long = 0L
                        var lastPercent: Int = -1
                        var read: Int = 0
                        while (((input.read(buffer)).also { read = it } != -1)) {
                            output.write(buffer, 0, read)
                            written += (read).toLong()
                            val percent: Int = (if ((expected > 0L)) (Math.min(99L, ((written * 100L) / expected))).toInt() else -1)
                            if ((percent != lastPercent)) {
                                lastPercent = percent
                                reporter.progress(percent)
                            }
                        }
                        if (((expected > 0L) && (written != expected))) {
                            throw IOException(((("The selected image changed while copying: expected " + expected) + " bytes, read ") + written))
                        }
                    }
                }
                return requireRaw(raw)
            } catch (failure: IOException) {
                try {
                    Files.deleteIfExists(raw.toPath())
                } catch (cleanup: IOException) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            } catch (failure: RuntimeException) {
                try {
                    Files.deleteIfExists(raw.toPath())
                } catch (cleanup: IOException) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        @Throws(IOException::class)
        private fun requireRaw(raw: File): File {
            ImageSource(raw).use { check ->
                if (!ImageFormat.detect(check).isRawImage()) {
                    throw IOException("The image is neither ext4 nor EROFS")
                }
            }
            return raw
        }

        @Throws(IOException::class)
        private fun unsparse(`in`: InputStream, `out`: File, reporter: JobReporter) {
            SparseInputStream(BufferedInputStream(`in`, BUFFER)).use { sparse ->
                FileOutputStream(`out`).use { output ->
                    val total: Long = sparse.getUnsparseSize()
                    var written: Long = 0L
                    var lastPercent: Int = -1
                    val buffer: ByteArray = ByteArray(BUFFER)
                    var read: Int = 0
                    while (((sparse.read(buffer)).also { read = it } != -1)) {
                        output.write(buffer, 0, read)
                        written += (read).toLong()
                        val percent: Int = (if ((total > 0L)) (Math.min(99L, ((written * 100L) / total))).toInt() else -1)
                        if ((percent != lastPercent)) {
                            lastPercent = percent
                            reporter.progress(percent)
                        }
                    }
                }
            }
        }

        private fun mb(bytes: Long): Long {
            return (bytes / (1024L * 1024L))
        }

        private fun closeQuietly(pfd: ParcelFileDescriptor) {
            try {
                pfd.close()
            } catch (ignored: IOException) {

            }
        }
    }
}
