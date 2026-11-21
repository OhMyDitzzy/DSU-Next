package com.ditzzy.dsunext.filemanager.core

import com.ditzzy.dsunext.filemanager.api.*
import com.ditzzy.dsunext.filemanager.fs.LocalFiles
import com.ditzzy.dsunext.filemanager.workspace.WorkspacePaths.Companion.attributes
import com.ditzzy.dsunext.filemanager.workspace.WorkspacePaths.Companion.attributesOrNull
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque

internal class FileTransfers(private val files: LocalFiles) {
    private val paths get() = files.paths

    fun copy(request: FileRequest.Copy, context: OperationContext): FileValue.Destination {
        context.path = request.source
        val source = paths.resolve(request.source)
        val info = attributes(source)
        context.path = request.destination
        val destination = paths.resolve(request.destination, writable = true, missingParents = request.createParents)
        validate(source, destination, info)
        files.parents(destination, request.createParents, context)
        val copied = copyTree(source, destination, request.conflict, request.directories,
            request.preserveAttributes, context)
        return FileValue.Destination(copied?.let(paths::relative))
    }

    fun move(request: FileRequest.Move, context: OperationContext): FileValue.Destination {
        context.path = request.source
        val source = paths.resolve(request.source, writable = true)
        val info = attributes(source)
        context.path = request.destination
        val requested = paths.resolve(request.destination, writable = true, missingParents = request.createParents)
        validate(source, requested, info)
        files.parents(requested, request.createParents, context)
        val kind = files.kind(info)
        if (kind == FileKind.OTHER) fail(FileErrorCode.UNSUPPORTED, request.source, "Special files cannot be moved")
        val destination = files.target(requested, kind, request.conflict, context,
            merge = request.directories == DirectoryPolicy.MERGE) ?: return FileValue.Destination(null)
        val existing = attributesOrNull(destination)
        val merging = kind == FileKind.DIRECTORY && existing?.isDirectory == true
        val sameStore = Files.getFileStore(source.parent) == Files.getFileStore(destination.parent)
        if (!merging && sameStore) {
            context.check()
            paths.check(source, writable = true)
            paths.check(destination, writable = true)
            try {
                if (request.conflict == ConflictPolicy.REPLACE) {
                    Files.move(source, destination, ATOMIC_MOVE, REPLACE_EXISTING)
                } else {
                    Files.move(source, destination)
                }
                context.change(ChangeKind.MOVED, paths.relative(destination), request.source)
                return FileValue.Destination(paths.relative(destination))
            } catch (_: AtomicMoveNotSupportedException) {
                // Cross-device replacement needs the same copy-then-delete path as a merge.
            } catch (error: FileAlreadyExistsException) {
                if (request.conflict == ConflictPolicy.SKIP) {
                    context.skip(paths.relative(destination))
                    return FileValue.Destination(null)
                }
                if (request.conflict != ConflictPolicy.KEEP_BOTH) throw error
            }
        }
        val skippedBefore = context.skippedCount
        val changesBefore = context.changeCount
        val copied = copyTree(source, requested, request.conflict, request.directories, true, context)
            ?: return FileValue.Destination(null)
        if (context.skippedCount != skippedBefore) {
            // Keeping the whole source is safer than deleting a tree with skipped children.
            if (context.changeCount != changesBefore) {
                fail(FileErrorCode.ALREADY_EXISTS, request.destination,
                    "Some entries were skipped; copied entries remain at the destination and the source was kept")
            }
            return FileValue.Destination(null)
        }
        context.check()
        context.path = request.source
        files.unchanged(source, info)
        FileOperations.deleteTree(paths, source, context)
        context.change(ChangeKind.MOVED, paths.relative(copied), request.source)
        return FileValue.Destination(paths.relative(copied))
    }

    private fun validate(source: Path, destination: Path, info: BasicFileAttributes) {
        if (source == destination) fail(FileErrorCode.SAME_FILE, paths.relative(destination), "Source and destination are the same")
        if (info.isDirectory && destination.startsWith(source)) {
            fail(FileErrorCode.DESCENDANT_TARGET, paths.relative(destination), "A directory cannot be placed inside itself")
        }
        if (info.isDirectory && source.startsWith(destination)) {
            fail(FileErrorCode.DESCENDANT_TARGET, paths.relative(destination), "Source and destination directories overlap")
        }
        val target = attributesOrNull(destination)
        if (target != null && !info.isSymbolicLink && !target.isSymbolicLink && Files.isSameFile(source, destination)) {
            fail(FileErrorCode.SAME_FILE, paths.relative(destination), "Source and destination refer to the same file")
        }
    }

    private data class DirectoryFrame(
        val target: Path,
        val created: Boolean,
        val expected: BasicFileAttributes,
    )

    private fun copyTree(
        source: Path,
        destination: Path,
        conflict: ConflictPolicy,
        directories: DirectoryPolicy,
        preserve: Boolean,
        context: OperationContext,
    ): Path? {
        val info = attributes(source)
        if (!info.isDirectory) return copyFile(source, destination, info, conflict, preserve, context)
        val stack = ArrayDeque<DirectoryFrame>()
        var rootDestination: Path? = null
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                context.check()
                context.path = paths.relative(dir)
                paths.check(dir)
                val requested = if (dir == source) destination else stack.peekLast().target.resolve(dir.fileName)
                val created = files.directory(requested, conflict, context, directories == DirectoryPolicy.MERGE)
                    ?: return FileVisitResult.SKIP_SUBTREE
                if (dir == source) rootDestination = created.first
                stack.addLast(DirectoryFrame(created.first, created.second, attrs))
                context.progress()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                context.check()
                val target = stack.peekLast().target.resolve(file.fileName)
                copyFile(file, target, attrs, conflict, preserve, context)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                context.check()
                context.path = paths.relative(dir)
                val frame = stack.removeLast()
                files.unchanged(dir, frame.expected)
                if (preserve && frame.created) {
                    paths.check(frame.target, writable = true)
                    files.copyAttributes(dir, frame.target, frame.expected)
                }
                return FileVisitResult.CONTINUE
            }
        })
        return rootDestination
    }

    private fun copyFile(
        source: Path,
        destination: Path,
        expected: BasicFileAttributes,
        conflict: ConflictPolicy,
        preserve: Boolean,
        context: OperationContext,
    ): Path? {
        context.check()
        context.path = paths.relative(source)
        paths.check(source)
        paths.check(destination, writable = true)
        validate(source, destination, expected)
        return when (files.kind(expected)) {
            FileKind.FILE -> files.write(destination, InputSource {
                context.path = paths.relative(source)
                files.open(source)
            }, conflict, context,
                ChangeKind.COPIED, source) { temporary ->
                files.unchanged(source, expected)
                if (preserve) files.copyAttributes(source, temporary, expected)
            }
            FileKind.SYMLINK -> {
                val target = Files.readSymbolicLink(source)
                files.unchanged(source, expected)
                files.symlink(destination, target, conflict, context, source)
            }
            else -> fail(FileErrorCode.UNSUPPORTED, paths.relative(source), "Special files cannot be copied")
        }
    }
}
