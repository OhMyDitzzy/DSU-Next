package com.ditzzy.dsunext.workspace

import com.ditzzy.dsunext.filemanager.api.ChangeKind
import com.ditzzy.dsunext.filemanager.api.FileChange
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.ArrayList
import java.util.Properties
import java.util.TreeMap

class GsiMetadata {
    private val config: Path

    private val marker: Path

    private val root: Path

    private val partition: String

    private val mount: String

    private val modes: TreeMap<String, String> = TreeMap<String, String>()

    private val labels: TreeMap<String, String> = TreeMap<String, String>()

    private val links: TreeMap<String, String> = TreeMap<String, String>()

    @Throws(IOException::class)
    constructor(workspace: File, partition: String) {
        this.partition = partition
        root = File(workspace, partition).toPath()
        config = File(workspace, "config").toPath()
        marker = File(workspace, ".metadata-pending").toPath()
        val info: Properties = Properties()
        Files.newInputStream(file("info")).use { input ->
            info.load(input)
        }
        mount = info.getProperty("mount_point", ("/" + partition))
        if ((!mount.startsWith("/") || mount.contains(".."))) {
            throw IOException("Invalid image mount point")
        }
        load(file("fs_config"), modes, false, true)
        load(file("file_contexts"), labels, true, false)
        load(file("symlinks"), links, false, true)
    }

    @Throws(IOException::class)
    fun begin() {
        Files.write(marker, byteArrayOf(1.toByte()), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        FileChannel.`open`(marker, StandardOpenOption.WRITE).use { channel ->
            channel.force(true)
        }
    }

    @Throws(IOException::class)
    fun abort() {
        Files.deleteIfExists(marker)
    }

    @Throws(IOException::class)
    fun save() {
        write(file("fs_config"), modes, false, true)
        write(file("file_contexts"), labels, true, false)
        write(file("symlinks"), links, false, true)
        Files.delete(marker)
    }

    fun mode(path: String, directory: Boolean, symlink: Boolean): Int {
        val value: String? = modes.`get`(device(path))
        if ((value != null)) {
            val fields: Array<String> = java.util.regex.Pattern.compile("\\s+").split(value!!)
            if ((fields.size >= 3)) {
                return (java.lang.Integer.parseInt(fields[2], 8) and 4095)
            }
        }
        return (if (symlink) SYMLINK_MODE else (if (directory) 493 else (if (executable(path)) 493 else 420)))
    }

    @Throws(IOException::class)
    fun setMode(path: String, mode: Int) {
        val key: String = device(path)
        ensure(key, path)
        var fields: Array<String> = java.util.regex.Pattern.compile("\\s+").split(modes.`get`(key)!!)
        fields[2] = java.lang.String.format(java.util.Locale.ROOT, "%04o", mode)
        modes.put(key, java.lang.String.join(" ", *fields))
    }

    @Throws(IOException::class)
    fun apply(change: FileChange) {
        val path: String = change.path
        validatePath(path)
        val target: String = device(path)
        val source: String? = (if ((change.source == null)) null else device(change.source!!))
        if ((change.kind == ChangeKind.MOVED)) {
            if (modes.containsKey(source)) {
                relocate(modes, source!!, target)
                relocate(labels, source!!, target)
                relocate(links, source!!, target)
            }
            ensure(target, path)
            return
        }
        if ((change.kind == ChangeKind.DELETED)) {
            remove(modes, target)
            remove(labels, target)
            remove(links, target)
            return
        }
        if ((source != null)) {
            copy(modes, source!!, target)
            copy(labels, source!!, target)
            copy(links, source!!, target)
        }
        ensure(target, path)
        val actual: Path = root.resolve(path)
        if (Files.isSymbolicLink(actual)) {
            links.put(target, Files.readSymbolicLink(actual).toString())
        } else {
            links.remove(target)
        }
    }

    @Throws(IOException::class)
    private fun ensure(key: String, path: String) {
        validatePath(path)
        val actual: Path = root.resolve(path)
        val directory: Boolean = Files.isDirectory(actual, LinkOption.NOFOLLOW_LINKS)
        val link: Boolean = Files.isSymbolicLink(actual)
        val newEntry: Boolean = !modes.containsKey(key)
        if (newEntry) {
            val mode: Int = (if (link) SYMLINK_MODE else (if (directory) 493 else (if (executable(path)) 493 else 420)))
            modes.put(key, ((("0 " + (if (((!directory && !link) && executable(path))) "2000" else "0")) + " ") + java.lang.String.format(java.util.Locale.ROOT, "%04o", mode)))
        }
        if ((newEntry && !labels.containsKey(key))) {
            var parent: String = key
            while ((parent.lastIndexOf('/') > 0)) {
                parent = parent.substring(0, parent.lastIndexOf('/'))
                if (labels.containsKey(parent)) {
                    labels.put(key, labels.`get`(parent))
                    return
                }
            }
            if (labels.containsKey("/")) {
                labels.put(key, labels.`get`("/"))
            }
        }
    }

    private fun device(path: String): String {
        if (path.isEmpty()) {
            return mount
        }
        return (((if (mount.equals("/")) "" else mount) + "/") + path)
    }

    private fun file(suffix: String): Path {
        return config.resolve(((partition + "_") + suffix))
    }

    companion object {
        const val SYMLINK_MODE: Int = 420

        @JvmStatic
        @Throws(IOException::class)
        fun checkHealthy(workspace: File) {
            if (Files.exists(File(workspace, ".metadata-pending").toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("An interrupted edit needs recovery. Import this workspace again before editing or repacking.")
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun validatePath(path: String) {
            if (path.codePoints().anyMatch({ c ->
                (java.lang.Character.isWhitespace(c) || java.lang.Character.isISOControl(c))
            })) {
                throw IOException("GSI file names cannot contain whitespace or control characters")
            }
        }

        private fun executable(path: String): Boolean {
            val slash: Int = path.lastIndexOf('/')
            val parent: String = (if ((slash < 0)) "" else path.substring(0, slash))
            val name: String = parent.substring((parent.lastIndexOf('/') + 1))
            return (name.equals("bin") || name.equals("xbin"))
        }

        private fun copy(map: TreeMap<String, String>, source: String, target: String) {
            val value: String? = map.`get`(source)
            if ((value == null)) {
                map.remove(target)
            } else {
                map.put(target, value)
            }
        }

        private fun remove(map: TreeMap<String, String>, root: String) {
            map.keys.removeIf({ key ->
                (key.equals(root) || key.startsWith((root + "/")))
            })
        }

        private fun relocate(map: TreeMap<String, String>, source: String, target: String) {
            val moved: TreeMap<String, String> = TreeMap<String, String>()
            for (entry in map.entries) {
                if ((entry.key.equals(source) || entry.key.startsWith((source + "/")))) {
                    moved.put((target + entry.key.substring(source.length)), entry.value)
                }
            }
            remove(map, target)
            remove(map, source)
            map.putAll(moved)
        }

        @Throws(IOException::class)
        private fun load(file: Path, map: TreeMap<String, String>, regex: Boolean, relative: Boolean) {
            for (line in Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) {
                    continue
                }
                val separator: Int = line.indexOf(' ')
                if ((separator <= 0)) {
                    throw IOException(("Unreadable image metadata: " + file.getFileName()))
                }
                var key: String = line.substring(0, separator)
                if (regex) {
                    key = unescape(key)
                }
                if ((relative && !key.equals("/"))) {
                    key = ("/" + key)
                }
                var value: String = line.substring((separator + 1))
                if (!file.toString().endsWith("_symlinks")) {
                    value = value.trim()
                }
                if (file.toString().endsWith("_fs_config")) {
                    val fields: Array<String> = java.util.regex.Pattern.compile("\\s+").split(value)
                    try {
                        if ((fields.size < 3)) {
                            throw NumberFormatException()
                        }
                        java.lang.Long.parseLong(fields[0])
                        java.lang.Long.parseLong(fields[1])
                        java.lang.Integer.parseInt(fields[2], 8)
                    } catch (error: NumberFormatException) {
                        throw IOException("Unreadable file permissions", error)
                    }
                }
                map.put(key, value)
            }
        }

        private fun unescape(value: String): String {
            val `out`: StringBuilder = StringBuilder()
            run {
                var i: Int = 0
                while ((i < value.length)) {
                    var c: Char = value[i]
                    if (((c == '\\') && ((i + 1) < value.length))) {
                        c = value[++i]
                    }
                    `out`.append(c)
                    i++
                }
            }
            return `out`.toString()
        }

        private fun escape(value: String): String {
            val `out`: StringBuilder = StringBuilder()
            for (c in value.toCharArray()) {
                if ((".^\$*+?()[]{}|\\".indexOf(c) >= 0)) {
                    `out`.append('\\')
                }
                `out`.append(c)
            }
            return `out`.toString()
        }

        @Throws(IOException::class)
        private fun write(file: Path, values: TreeMap<String, String>, regex: Boolean, relative: Boolean) {
            val lines: ArrayList<String> = ArrayList<String>(values.size)
            for (entry in values.entries) {
                var key: String = entry.key
                if ((relative && !key.equals("/"))) {
                    key = key.substring(1)
                }
                if (regex) {
                    key = escape(key)
                }
                lines.add(((key + " ") + entry.value))
            }
            val temp: Path = Files.createTempFile(file.getParent(), ".metadata-", ".tmp")
            try {
                Files.write(temp, lines, StandardCharsets.UTF_8)
                FileChannel.`open`(temp, StandardOpenOption.WRITE).use { channel ->
                    channel.force(true)
                }
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }
}
