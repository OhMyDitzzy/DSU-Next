package com.ditzzy.dsunext.filemanager.workspace

import com.ditzzy.dsunext.filemanager.api.FileErrorCode
import com.ditzzy.dsunext.filemanager.core.fail
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal class WorkspacePaths(directory: File) {
    val root: Path = directory.toPath().toAbsolutePath().normalize().toRealPath()
    private val rootKey: Any?

    init {
        val info = attributes(root)
        require(info.isDirectory) { "The workspace must be an existing directory" }
        rootKey = info.fileKey()
    }

    fun relative(path: Path): String = root.relativize(path).toString()

    fun resolve(input: String, writable: Boolean = false, missingParents: Boolean = false): Path {
        val parsed = root.fileSystem.getPath(input)
        if (parsed.isAbsolute) fail(FileErrorCode.OUTSIDE_WORKSPACE, input, "Use a workspace-relative path")
        if (parsed.any { it.toString() == ".." }) {
            fail(FileErrorCode.OUTSIDE_WORKSPACE, input, "Parent traversal is not allowed")
        }
        return check(root.resolve(parsed).normalize(), writable, missingParents)
    }

    fun check(path: Path, writable: Boolean = false, missingParents: Boolean = false): Path {
        if (!path.startsWith(root)) {
            fail(FileErrorCode.OUTSIDE_WORKSPACE, null, "The path is outside the workspace")
        }
        val relativePath = relative(path)
        if (writable && path == root) {
            fail(FileErrorCode.INVALID_PATH, relativePath, "The workspace root cannot be modified")
        }
        val currentRoot = attributes(root)
        if (!currentRoot.isDirectory || currentRoot.isSymbolicLink ||
            (rootKey != null && currentRoot.fileKey() != rootKey)) {
            fail(FileErrorCode.SOURCE_CHANGED, "", "The workspace root was replaced")
        }
        if (path == root) return path
        var parent = root
        for (part in root.relativize(path.parent ?: root)) {
            if (part.toString().isEmpty()) continue
            parent = parent.resolve(part)
            val info = attributesOrNull(parent)
            if (info == null && missingParents) break
            if (info == null) throw NoSuchFileException(parent.toString())
            if (info.isSymbolicLink) {
                fail(FileErrorCode.SYMLINK_NOT_ALLOWED, relative(parent), "A parent directory is a symlink")
            }
            if (!info.isDirectory) {
                fail(FileErrorCode.NOT_DIRECTORY, relative(parent), "A parent is not a directory")
            }
        }
        return path
    }

    fun name(input: String) {
        val path = root.fileSystem.getPath(input)
        if (input.isEmpty() || input == "." || input == ".." || path.isAbsolute || path.nameCount != 1 ||
            input.contains('/')) {
            fail(FileErrorCode.INVALID_PATH, input, "A rename needs one file name")
        }
    }

    companion object {
        fun attributes(path: Path): BasicFileAttributes =
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

        fun attributesOrNull(path: Path): BasicFileAttributes? = try {
            attributes(path)
        } catch (_: NoSuchFileException) {
            null
        }
    }
}
