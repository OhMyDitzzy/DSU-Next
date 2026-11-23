package com.ditzzy.dsunext.workspace

import android.content.Context
import com.ditzzy.dsunext.yuki.Yuki
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.Properties

class WorkspaceStore {
    private constructor() {

    }

    companion object {
        private var baseDirectory: File? = null

        const val PARTITION: String = "system"

        const val PROP_FILE: String = "workspace.prop"

        const val BUSY_FILE: String = ".busy"

        const val STAGING_DIR: String = ".staging"

        const val OUTPUT_DIR: String = "output"

        const val CONFIG_DIR: String = "config"

        const val K_PARTITION: String = "partition"

        const val K_SOURCE: String = "source"

        const val K_FS_TYPE: String = "fs_type"

        const val K_MOUNT_POINT: String = "mount_point"

        const val K_IMAGE_SIZE: String = "image_size"

        const val K_IMPORTED_AT: String = "imported_at"

        const val K_DIRS: String = "dirs"

        const val K_FILES: String = "files"

        const val K_SYMLINKS: String = "symlinks"

        const val K_BYTES: String = "bytes"

        const val K_REPACK_FILE: String = "repack_file"

        const val K_REPACK_SIZE: String = "repack_size"

        const val K_REPACK_AT: String = "repack_at"

        const val K_ANDROID_RELEASE: String = "android_release"

        const val K_ANDROID_SDK: String = "android_sdk"

        const val K_BUILD_ID: String = "build_id"

        const val K_FINGERPRINT: String = "fingerprint"

        private val INFO_KEYS: Array<String> = arrayOf(K_PARTITION, K_SOURCE, K_FS_TYPE, K_MOUNT_POINT, K_IMAGE_SIZE, K_IMPORTED_AT, K_DIRS, K_FILES, K_SYMLINKS, K_BYTES, K_REPACK_FILE, K_REPACK_SIZE, K_REPACK_AT)

        private val CONFIG_KEYS: Array<String> = arrayOf("block_size", "uuid", "volume_name")

        @JvmStatic
        @Synchronized
        fun initialize(context: Context) {
            baseDirectory = File(context.getFilesDir(), "gsi-workspaces")
        }

        @JvmStatic
        @Synchronized
        fun baseDir(): File {
            if ((baseDirectory == null)) {
                throw IllegalStateException("Workspace storage has not been initialized")
            }
            return baseDirectory!!
        }

        /** Empty when libyuki is usable in this process, the reason otherwise. */
        @JvmStatic
        fun nativeError(): String {
            if (Yuki.isLoaded) {
                return ""
            }
            return ("libyuki.so could not be loaded in the app process: " + Yuki.loadError)
        }

        @JvmStatic
        @Throws(IOException::class)
        fun dir(name: String): File {
            if (!WorkspaceNames.isValid(name)) {
                throw IOException(("Invalid workspace name: " + name))
            }
            return File(baseDir(), name)
        }

        @JvmStatic
        @Throws(IOException::class)
        fun existing(name: String): File {
            val dir: File = dir(name)
            if ((Files.isSymbolicLink(dir.toPath()) || !dir.isDirectory())) {
                throw IOException(("Workspace not found: " + name))
            }
            return dir
        }

        @JvmStatic
        fun isReady(workspace: File): Boolean {
            return (File(workspace, PROP_FILE).isFile() && !File(workspace, BUSY_FILE).exists())
        }

        @JvmStatic
        @Throws(IOException::class)
        fun markBusy(workspace: File) {
            Files.write(File(workspace, BUSY_FILE).toPath(), ByteArray(0))
        }

        @JvmStatic
        fun clearBusy(workspace: File) {
            //noinspection ResultOfMethodCallIgnored
            File(workspace, BUSY_FILE).delete()
        }

        @JvmStatic
        fun readProps(workspace: File): Properties {
            val props: Properties = Properties()
            val file: File = File(workspace, PROP_FILE)
            if (file.isFile()) {
                try {
                    FileInputStream(file).use { `in` ->
                        props.load(`in`)
                    }
                } catch (ignored: IOException) {

                }
            }
            // An unreadable file is treated like an empty one
            return props
        }

        @JvmStatic
        @Throws(IOException::class)
        fun writeProps(workspace: File, props: Properties) {
            FileOutputStream(File(workspace, PROP_FILE)).use { `out` ->
                props.store(`out`, null)
            }
        }

        /** Removes a folder tree without ever following a symlink, a link is deleted as a link. */
        @JvmStatic
        @Throws(IOException::class)
        fun deleteTree(root: Path) {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                @Throws(IOException::class)
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                @Throws(IOException::class)
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if ((exc != null)) {
                        throw exc
                    }
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }

        @JvmStatic
        @Throws(IOException::class)
        fun delete(name: String): Boolean {
            val path: Path = dir(name).toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return true
            }
            deleteTree(path)
            return true
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun listJson(): String {
            val array: JSONArray = JSONArray()
            val children: Array<File>? = baseDir().listFiles()
            if ((children != null)) {
                Arrays.sort(children, { a, b ->
                    a.getName().compareTo(b.getName(), ignoreCase = true)
                })
                for (child in children!!) {
                    if (((!WorkspaceNames.isValid(child.getName()) || Files.isSymbolicLink(child.toPath())) || !child.isDirectory())) {
                        continue
                    }
                    array.put(summary(child))
                }
            }
            return array.toString()
        }

        @Throws(JSONException::class)
        private fun summary(workspace: File): JSONObject {
            val props: Properties = readProps(workspace)
            val json: JSONObject = JSONObject()
            json.put("name", workspace.getName())
            json.put("state", (if (isReady(workspace)) "ready" else "incomplete"))
            json.put(K_FS_TYPE, props.getProperty(K_FS_TYPE, ""))
            json.put(K_IMAGE_SIZE, props.getProperty(K_IMAGE_SIZE, "0"))
            json.put(K_IMPORTED_AT, props.getProperty(K_IMPORTED_AT, "0"))
            json.put(K_SOURCE, props.getProperty(K_SOURCE, ""))
            json.put(K_ANDROID_RELEASE, props.getProperty(K_ANDROID_RELEASE, ""))
            return json
        }

        @JvmStatic
        @Throws(IOException::class, JSONException::class)
        fun infoJson(name: String): String {
            val workspace: File = existing(name)
            val props: Properties = readProps(workspace)
            val partition: String = props.getProperty(K_PARTITION, PARTITION)
            val json: JSONObject = JSONObject()
            json.put("name", name)
            json.put("path", workspace.getAbsolutePath())
            json.put("state", (if (isReady(workspace)) "ready" else "incomplete"))
            if (File(workspace, ".metadata-pending").exists()) {
                json.put("state", "recovery")
            }
            for (key in INFO_KEYS) {
                val value: String? = props.getProperty(key)
                if ((value != null)) {
                    json.put(key, value)
                }
            }
            val config: MutableMap<String, String> = readKeyValues(File(workspace, (((CONFIG_DIR + "/") + partition) + "_info")))
            for (key in CONFIG_KEYS) {
                val value: String? = config.`get`(key)
                if (((value != null) && !value!!.isEmpty())) {
                    json.put(key, value)
                }
            }
            for (entry in readBuildInfo(workspace, partition).entries) {
                json.put(entry.key, entry.value)
            }
            return json.toString()
        }

        /** Android release, SDK, build id and fingerprint from the unpacked build.prop. */
        @JvmStatic
        fun readBuildInfo(workspace: File, partition: String): MutableMap<String, String> {
            val result: MutableMap<String, String> = LinkedHashMap<String, String>()
            var file: File = File(workspace, (partition + "/system/build.prop"))
            if (!file.isFile()) {
                file = File(workspace, (partition + "/build.prop"))
            }
            if ((!file.isFile() || Files.isSymbolicLink(file.toPath()))) {
                return result
            }
            val prop: MutableMap<String, String> = readKeyValues(file)
            putFirst(result, K_ANDROID_RELEASE, prop, "ro.system.build.version.release", "ro.build.version.release")
            putFirst(result, K_ANDROID_SDK, prop, "ro.system.build.version.sdk", "ro.build.version.sdk")
            putFirst(result, K_BUILD_ID, prop, "ro.build.display.id", "ro.system.build.id")
            putFirst(result, K_FINGERPRINT, prop, "ro.system.build.fingerprint", "ro.build.fingerprint")
            return result
        }

        private fun putFirst(into: MutableMap<String, String>, key: String, from: MutableMap<String, String>, vararg candidates: String) {
            for (candidate in candidates) {
                val value: String? = from.`get`(candidate)
                if (((value != null) && !value!!.isEmpty())) {
                    into.put(key, value)
                    return
                }
            }
        }

        private fun readKeyValues(file: File): MutableMap<String, String> {
            val values: MutableMap<String, String> = LinkedHashMap<String, String>()
            if (!file.isFile()) {
                return values
            }
            try {
                BufferedReader(InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8)).use { reader ->
                    var line: String? = null
                    while (((reader.readLine()).also { line = it } != null)) {
                        val eq: Int = line!!.indexOf('=')
                        if (((eq <= 0) || line!!.startsWith("#"))) {
                            continue
                        }
                        values.put(line!!.substring(0, eq).trim(), line!!.substring((eq + 1)).trim())
                    }
                }
            } catch (ignored: IOException) {

            }
            return values
        }
    }
}
