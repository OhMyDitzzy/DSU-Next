package com.ditzzy.dsunext.filemanager.api

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Opened on the operation thread. The backend owns and closes the returned stream. */
fun interface InputSource {
    @Throws(IOException::class)
    fun open(): InputStream

    companion object {
        @JvmStatic
        fun empty(): InputSource = InputSource { ByteArrayInputStream(byteArrayOf()) }
    }
}

/** Opened on the operation thread. The backend owns and closes the returned stream. */
fun interface OutputTarget {
    @Throws(IOException::class)
    fun open(): OutputStream
}

enum class ConflictPolicy { FAIL, SKIP, REPLACE, KEEP_BOTH }

/** MERGE only merges directories; child conflicts still use [ConflictPolicy]. */
enum class DirectoryPolicy { FAIL, MERGE }

enum class CollisionNaming { NUMBERED, DASH_NUMBER }

/** All paths are workspace-relative. A destination is the complete new path, not its parent. */
sealed class FileRequest {
    data class Stat(val path: String) : FileRequest()

    data class ListDirectory @JvmOverloads constructor(val path: String = "") : FileRequest()

    data class CreateFile @JvmOverloads constructor(
        val path: String,
        val content: InputSource = InputSource.empty(),
        val createParents: Boolean = false,
        val conflict: ConflictPolicy = ConflictPolicy.FAIL,
    ) : FileRequest()

    data class CreateDirectory @JvmOverloads constructor(
        val path: String,
        val createParents: Boolean = false,
        val conflict: ConflictPolicy = ConflictPolicy.FAIL,
    ) : FileRequest()

    data class ReadFile(val path: String, val output: OutputTarget) : FileRequest()

    /** Replaces an existing regular file's content, keeping its permission mode. */
    data class WriteFile(val path: String, val content: InputSource) : FileRequest()

    data class Copy @JvmOverloads constructor(
        val source: String,
        val destination: String,
        val conflict: ConflictPolicy = ConflictPolicy.FAIL,
        val directories: DirectoryPolicy = DirectoryPolicy.FAIL,
        val createParents: Boolean = false,
        val preserveAttributes: Boolean = true,
    ) : FileRequest()

    data class Move @JvmOverloads constructor(
        val source: String,
        val destination: String,
        val conflict: ConflictPolicy = ConflictPolicy.FAIL,
        val directories: DirectoryPolicy = DirectoryPolicy.FAIL,
        val createParents: Boolean = false,
    ) : FileRequest()

    data class Rename @JvmOverloads constructor(
        val path: String,
        val newName: String,
        val conflict: ConflictPolicy = ConflictPolicy.FAIL,
    ) : FileRequest()

    data class Delete @JvmOverloads constructor(
        val path: String,
        val recursive: Boolean = false,
    ) : FileRequest()

    /**
     * Numeric POSIX mode, e.g. 420 (0644) or 493 (0755). Symlinks are rejected.
     *
     * When [recursive] is true the mode also reaches the entries inside a folder; [applyToFiles] and
     * [applyToDirectories] choose which kinds of entry are changed. The folder named by [path] always
     * receives the mode itself.
     */
    data class SetPermissions @JvmOverloads constructor(
        val path: String,
        val mode: Int,
        val recursive: Boolean = false,
        val applyToFiles: Boolean = true,
        val applyToDirectories: Boolean = true,
    ) : FileRequest()

    /** The target is stored verbatim and may be absolute or dangling. It is never followed. */
    data class CreateSymlink @JvmOverloads constructor(
        val path: String,
        val target: String,
        val createParents: Boolean = false,
        val conflict: ConflictPolicy = ConflictPolicy.FAIL,
    ) : FileRequest()
}
