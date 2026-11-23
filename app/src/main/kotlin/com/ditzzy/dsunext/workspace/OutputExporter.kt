package com.ditzzy.dsunext.workspace

import android.os.ParcelFileDescriptor
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.regex.Pattern

class OutputExporter {
    private constructor() {

    }

    companion object {
        private val OUTPUT_FILE: Pattern = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.img\$")

        private const val CHUNK: Long = ((4L * 1024L) * 1024L)

        /** Takes ownership of `target`. */
        @JvmStatic
        @Throws(IOException::class, JSONException::class)
        fun run(name: String, fileName: String?, target: ParcelFileDescriptor, reporter: JobReporter): String {
            ParcelFileDescriptor.AutoCloseOutputStream(target).use { `out` ->
                val workspace: File = WorkspaceStore.existing(name)
                if (fileName == null || !OUTPUT_FILE.matcher(fileName).matches()) {
                    throw IOException(("Invalid file name: " + fileName))
                }
                val file: File = File(File(workspace, WorkspaceStore.OUTPUT_DIR), fileName)
                if ((!file.isFile() || Files.isSymbolicLink(file.toPath()))) {
                    throw IOException("There is nothing to save, repack the workspace first")
                }
                val total: Long = (file.length()).toLong()
                reporter.log(("- Saving " + fileName))
                FileInputStream(file).use { `in` ->
                    val from: FileChannel = `in`.getChannel()
                    val to: FileChannel = `out`.getChannel()
                    var position: Long = 0L
                    var lastPercent: Int = -1
                    while ((position < total)) {
                        val moved: Long = from.transferTo(position, Math.min(CHUNK, (total - position)), to)
                        if ((moved <= 0L)) {
                            throw IOException((("The copy stopped after " + position) + " bytes"))
                        }
                        position += moved
                        val percent: Int = (Math.min(99L, ((position * 100L) / total))).toInt()
                        if ((percent != lastPercent)) {
                            lastPercent = percent
                            reporter.progress(percent)
                        }
                    }
                }
                `out`.flush()
                reporter.progress(100)
                return JSONObject().put("name", name).put("file", fileName).toString()
            }
        }
    }
}
