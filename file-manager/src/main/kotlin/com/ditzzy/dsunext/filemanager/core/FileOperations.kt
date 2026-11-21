package com.ditzzy.dsunext.filemanager.core

import com.ditzzy.dsunext.filemanager.api.*
import com.ditzzy.dsunext.filemanager.fs.LocalFiles
import com.ditzzy.dsunext.filemanager.fs.PosixModes
import com.ditzzy.dsunext.filemanager.workspace.WorkspacePaths
import com.ditzzy.dsunext.filemanager.workspace.WorkspacePaths.Companion.attributes
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class FileOperations(private val paths: WorkspacePaths, naming: CollisionNaming) {
    private val files = LocalFiles(paths, naming)
    private val transfers = FileTransfers(files)

    fun execute(request: FileRequest, context: OperationContext): FileValue {
        context.check()
        return when (request) {
            is FileRequest.Stat -> FileValue.Entry(files.entry(resolve(request.path, context)))
            is FileRequest.ListDirectory -> list(resolve(request.path, context), context)
            is FileRequest.CreateFile -> {
                val path = resolve(request.path, context, writable = true, parents = request.createParents)
                files.parents(path, request.createParents, context)
                FileValue.Destination(files.write(path, request.content, request.conflict, context)?.let(paths::relative))
            }
            is FileRequest.CreateDirectory -> {
                val path = resolve(request.path, context, writable = true, parents = request.createParents)
                files.parents(path, request.createParents, context)
                FileValue.Destination(files.directory(path, request.conflict, context)?.first?.let(paths::relative))
            }
            is FileRequest.ReadFile -> read(resolve(request.path, context), request.output, context)
            is FileRequest.WriteFile -> write(request, context)
            is FileRequest.Copy -> transfers.copy(request, context)
            is FileRequest.Move -> transfers.move(request, context)
            is FileRequest.Rename -> {
                val source = resolve(request.path, context, writable = true)
                paths.name(request.newName)
                transfers.move(FileRequest.Move(request.path,
                    paths.relative(source.resolveSibling(request.newName)), request.conflict), context)
            }
            is FileRequest.Delete -> {
                delete(resolve(request.path, context, writable = true), request.recursive, context)
                FileValue.Done
            }
            is FileRequest.SetPermissions -> permissions(request, context)
            is FileRequest.CreateSymlink -> {
                val path = resolve(request.path, context, writable = true, parents = request.createParents)
                val target = paths.root.fileSystem.getPath(request.target)
                if (request.target.isEmpty()) fail(FileErrorCode.INVALID_PATH, request.path, "A symlink target cannot be empty")
                files.parents(path, request.createParents, context)
                FileValue.Destination(files.symlink(path, target, request.conflict, context)?.let(paths::relative))
            }
        }
    }

    private fun resolve(input: String, context: OperationContext, writable: Boolean = false, parents: Boolean = false): Path {
        context.path = input
        context.check()
        return paths.resolve(input, writable, parents)
    }

    private fun list(path: Path, context: OperationContext): FileValue {
        val info = attributes(path)
        if (info.isSymbolicLink) fail(FileErrorCode.SYMLINK_NOT_ALLOWED, paths.relative(path), "The folder is a symlink")
        if (!info.isDirectory) fail(FileErrorCode.NOT_DIRECTORY, paths.relative(path), "The path is not a directory")
        val entries = ArrayList<FileEntry>()
        Files.newDirectoryStream(path).use { children ->
            for (child in children) {
                context.check()
                context.path = paths.relative(child)
                entries += files.entry(child)
                context.progress()
            }
        }
        entries.sortWith(compareBy<FileEntry> { it.kind != FileKind.DIRECTORY }.thenBy { it.name })
        return FileValue.Listing(entries.toList())
    }

    private fun read(path: Path, target: OutputTarget, context: OperationContext): FileValue {
        val expected = files.regular(path)
        files.open(path).use { input ->
            context.check()
            context.outputOpened = true
            target.open().use { output -> files.stream(input, output, context, external = true) }
        }
        files.unchanged(path, expected)
        return FileValue.Done
    }

    private fun write(request: FileRequest.WriteFile, context: OperationContext): FileValue {
        val path = resolve(request.path, context, writable = true)
        val expected = files.regular(path)
        val mode = PosixModes.read(path)
        val destination = files.write(path, request.content, ConflictPolicy.REPLACE, context) { temporary ->
            files.unchanged(path, expected)
            mode?.let { PosixModes.write(temporary, it) }
        }
        return FileValue.Destination(destination?.let(paths::relative))
    }

    private fun permissions(request: FileRequest.SetPermissions, context: OperationContext): FileValue {
        if (request.mode !in 0..4095) fail(FileErrorCode.INVALID_MODE, request.path, "A POSIX mode must be between 0000 and 07777")
        val root = resolve(request.path, context, writable = true)
        val info = attributes(root)
        if (info.isSymbolicLink) fail(FileErrorCode.SYMLINK_NOT_ALLOWED, request.path, "Symlink permissions cannot be changed")
        if (!request.recursive || !info.isDirectory) {
            setMode(root, request.mode, context)
            return FileValue.Done
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                visit(dir, context)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                visit(file, context)
                if (attrs.isSymbolicLink) context.skip(paths.relative(file)) else setMode(file, request.mode, context)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                setMode(dir, request.mode, context)
                return FileVisitResult.CONTINUE
            }
        })
        return FileValue.Done
    }

    private fun setMode(path: Path, mode: Int, context: OperationContext) {
        visit(path, context)
        val info = attributes(path)
        if (info.isSymbolicLink) fail(FileErrorCode.SYMLINK_NOT_ALLOWED, paths.relative(path), "The file became a symlink")
        if (!info.isRegularFile && !info.isDirectory) fail(FileErrorCode.UNSUPPORTED, paths.relative(path), "Special files are not supported")
        if (PosixModes.read(path) == mode) return
        PosixModes.write(path, mode)
        context.change(ChangeKind.PERMISSIONS_CHANGED, paths.relative(path))
        if (PosixModes.read(path) != mode) {
            fail(FileErrorCode.ACCESS_DENIED, paths.relative(path), "The filesystem did not retain the requested mode")
        }
        context.progress()
    }

    private fun visit(path: Path, context: OperationContext) {
        context.path = paths.relative(path)
        context.check()
        paths.check(path, writable = true)
    }

    internal fun delete(root: Path, recursive: Boolean, context: OperationContext) {
        visit(root, context)
        if (!recursive) {
            Files.delete(root)
            context.change(ChangeKind.DELETED, paths.relative(root))
            return
        }
        deleteTree(paths, root, context)
    }

    companion object {
        internal fun deleteTree(paths: WorkspacePaths, root: Path, context: OperationContext) {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    check(dir)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    remove(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    remove(dir)
                    return FileVisitResult.CONTINUE
                }

                private fun check(path: Path) {
                    context.path = paths.relative(path)
                    context.check()
                    paths.check(path, writable = true)
                }

                private fun remove(path: Path) {
                    check(path)
                    Files.delete(path)
                    context.change(ChangeKind.DELETED, paths.relative(path))
                    context.progress()
                }
            })
        }
    }
}
