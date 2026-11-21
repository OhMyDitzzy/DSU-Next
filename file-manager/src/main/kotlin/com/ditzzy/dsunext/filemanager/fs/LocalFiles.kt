package com.ditzzy.dsunext.filemanager.fs

import com.ditzzy.dsunext.filemanager.api.*
import com.ditzzy.dsunext.filemanager.core.OperationContext
import com.ditzzy.dsunext.filemanager.core.fail
import com.ditzzy.dsunext.filemanager.workspace.WorkspacePaths
import com.ditzzy.dsunext.filemanager.workspace.WorkspacePaths.Companion.attributes
import com.ditzzy.dsunext.filemanager.workspace.WorkspacePaths.Companion.attributesOrNull
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal class LocalFiles(val paths: WorkspacePaths) {
    fun kind(attributes: BasicFileAttributes): FileKind = when {
        attributes.isSymbolicLink -> FileKind.SYMLINK
        attributes.isDirectory -> FileKind.DIRECTORY
        attributes.isRegularFile -> FileKind.FILE
        else -> FileKind.OTHER
    }

    fun entry(path: Path): FileEntry {
        paths.check(path)
        val info = attributes(path)
        return FileEntry(paths.relative(path), path.fileName?.toString().orEmpty(), kind(info), info.size(),
            info.lastModifiedTime().toMillis(), PosixModes.read(path),
            if (info.isSymbolicLink) Files.readSymbolicLink(path).toString() else null)
    }

    fun regular(path: Path): BasicFileAttributes {
        paths.check(path)
        val info = attributes(path)
        when {
            info.isSymbolicLink -> fail(FileErrorCode.SYMLINK_NOT_ALLOWED, paths.relative(path), "The file is a symlink")
            info.isDirectory -> fail(FileErrorCode.IS_DIRECTORY, paths.relative(path), "The path is a directory")
            !info.isRegularFile -> fail(FileErrorCode.UNSUPPORTED, paths.relative(path), "Only regular files can be streamed")
        }
        return info
    }

    fun unchanged(path: Path, expected: BasicFileAttributes) {
        paths.check(path)
        val current = attributes(path)
        if (kind(current) != kind(expected) || current.fileKey() != expected.fileKey() ||
            current.size() != expected.size() || current.lastModifiedTime() != expected.lastModifiedTime()) {
            fail(FileErrorCode.SOURCE_CHANGED, paths.relative(path), "The source changed during the operation")
        }
    }

    fun parents(path: Path, create: Boolean, context: OperationContext) {
        paths.check(path, writable = true, missingParents = create)
        if (!create) return
        var current = paths.root
        for (part in paths.root.relativize(path.parent)) {
            if (part.toString().isEmpty()) continue
            context.check()
            current = current.resolve(part)
            paths.check(current, writable = true)
            try {
                Files.createDirectory(current)
                context.change(ChangeKind.CREATED, paths.relative(current))
            } catch (error: FileAlreadyExistsException) {
                val info = attributes(current)
                if (info.isSymbolicLink || !info.isDirectory) throw error
            }
        }
    }

    fun target(
        requested: Path,
        expectedKind: FileKind,
        policy: ConflictPolicy,
        context: OperationContext,
        merge: Boolean = false,
    ): Path? {
        paths.check(requested, writable = true)
        val existing = attributesOrNull(requested) ?: return requested
        if (merge && expectedKind == FileKind.DIRECTORY && existing.isDirectory) return requested
        if (policy == ConflictPolicy.SKIP) {
            context.skip(paths.relative(requested))
            return null
        }
        if (policy == ConflictPolicy.KEEP_BOTH) {
            for (index in 1..10_000) {
                context.check()
                val candidate = numbered(requested, index, expectedKind == FileKind.DIRECTORY)
                if (attributesOrNull(candidate) == null) return candidate
            }
            fail(FileErrorCode.ALREADY_EXISTS, paths.relative(requested), "No unused name was found")
        }
        if (policy == ConflictPolicy.FAIL) throw FileAlreadyExistsException(requested.toString())
        if (kind(existing) != expectedKind) {
            fail(FileErrorCode.TYPE_MISMATCH, paths.relative(requested), "The destination has a different file type")
        }
        if (expectedKind == FileKind.DIRECTORY) {
            fail(FileErrorCode.ALREADY_EXISTS, paths.relative(requested), "Use an explicit directory merge")
        }
        return requested
    }

    fun directory(
        requested: Path,
        policy: ConflictPolicy,
        context: OperationContext,
        merge: Boolean = false,
    ): Pair<Path, Boolean>? {
        while (true) {
            context.path = paths.relative(requested)
            context.check()
            val destination = target(requested, FileKind.DIRECTORY, policy, context, merge) ?: return null
            if (merge && attributesOrNull(destination)?.isDirectory == true) return destination to false
            try {
                Files.createDirectory(destination)
                context.change(ChangeKind.CREATED, paths.relative(destination))
                return destination to true
            } catch (error: FileAlreadyExistsException) {
                if (policy != ConflictPolicy.KEEP_BOTH && policy != ConflictPolicy.SKIP && !merge) throw error
            }
        }
    }

    fun stream(input: InputStream, output: OutputStream, context: OperationContext, external: Boolean = false) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            context.check()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) break
                context.check()
                output.write(byte)
                context.bytesProcessed++
                if (external) context.outputBytes++
            } else {
                context.check()
                output.write(buffer, 0, count)
                context.bytesProcessed += count
                if (external) context.outputBytes += count
            }
            context.progress()
        }
        context.check()
    }

    fun write(
        requested: Path,
        content: InputSource,
        policy: ConflictPolicy,
        context: OperationContext,
        change: ChangeKind = ChangeKind.CREATED,
        source: Path? = null,
        prepare: (Path) -> Unit = {},
    ): Path? {
        // Resolve simple conflicts before opening a caller's potentially one-shot stream.
        context.path = paths.relative(requested)
        context.check()
        if (target(requested, FileKind.FILE, policy, context) == null) return null
        val temp = Files.createTempFile(requested.parent, ".file-manager-", ".tmp")
        try {
            FileChannel.open(temp, WRITE, NOFOLLOW_LINKS).use { channel ->
                context.check()
                content.open().use { input -> stream(input, Channels.newOutputStream(channel), context) }
                channel.force(true)
            }
            prepare(temp)
            while (true) {
                context.check()
                context.path = paths.relative(requested)
                val destination = target(requested, FileKind.FILE, policy, context) ?: return null
                val replacing = attributesOrNull(destination) != null
                try {
                    if (policy == ConflictPolicy.REPLACE) {
                        Files.move(temp, destination, ATOMIC_MOVE, REPLACE_EXISTING)
                    } else {
                        // Publishing a completed inode through a new link cannot clobber an existing name.
                        Files.createLink(destination, temp)
                    }
                    context.change(if (replacing) ChangeKind.REPLACED else change,
                        paths.relative(destination), source?.let(paths::relative))
                    return destination
                } catch (error: FileAlreadyExistsException) {
                    if (policy != ConflictPolicy.KEEP_BOTH && policy != ConflictPolicy.SKIP) throw error
                }
            }
        } finally {
            cleanup(temp, context)
        }
    }

    fun symlink(
        requested: Path,
        linkTarget: Path,
        policy: ConflictPolicy,
        context: OperationContext,
        source: Path? = null,
    ): Path? {
        while (true) {
            context.check()
            context.path = paths.relative(requested)
            val destination = target(requested, FileKind.SYMLINK, policy, context) ?: return null
            try {
                val replacing = attributesOrNull(destination) != null
                if (policy == ConflictPolicy.REPLACE) {
                    val temp = destination.resolveSibling(".file-manager-${UUID.randomUUID()}.tmp")
                    Files.createSymbolicLink(temp, linkTarget)
                    try {
                        context.check()
                        Files.move(temp, destination, ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally {
                        cleanup(temp, context)
                    }
                } else {
                    Files.createSymbolicLink(destination, linkTarget)
                }
                context.change(if (replacing) ChangeKind.REPLACED else if (source == null) ChangeKind.CREATED else ChangeKind.COPIED,
                    paths.relative(destination), source?.let(paths::relative))
                return destination
            } catch (error: FileAlreadyExistsException) {
                if (policy != ConflictPolicy.KEEP_BOTH && policy != ConflictPolicy.SKIP) throw error
            }
        }
    }

    fun copyAttributes(source: Path, destination: Path, expected: BasicFileAttributes) {
        PosixModes.read(source)?.let { PosixModes.write(destination, it) }
        val view = Files.getFileAttributeView(destination, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: throw UnsupportedOperationException("Basic file attributes are unavailable")
        view.setTimes(expected.lastModifiedTime(), expected.lastAccessTime(), null)
    }

    fun open(path: Path): InputStream {
        regular(path)
        return Files.newInputStream(path, READ, NOFOLLOW_LINKS)
    }

    private fun cleanup(temp: Path, context: OperationContext) {
        try {
            Files.deleteIfExists(temp)
        } catch (error: Exception) {
            context.cleanupFailed(error, paths.relative(temp))
        }
    }

    private fun numbered(path: Path, index: Int, directory: Boolean): Path {
        val name = path.fileName.toString()
        val dot = if (directory) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val updated = if (dot < 0) "$name ($index)" else "${name.substring(0, dot)} ($index)${name.substring(dot)}"
        return path.resolveSibling(updated)
    }
}
