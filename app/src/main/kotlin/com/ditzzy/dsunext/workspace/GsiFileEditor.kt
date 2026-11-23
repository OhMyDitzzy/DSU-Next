package com.ditzzy.dsunext.workspace

import com.ditzzy.dsunext.filemanager.FileManager
import com.ditzzy.dsunext.filemanager.api.*
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayList
import java.util.Collections
import java.util.UUID

class GsiFileEditor : AutoCloseable {
    private val workspace: File

    private val partition: String

    private val manager: FileManager

    private val root: Path

    @Throws(IOException::class)
    constructor(name: String) {
        workspace = WorkspaceStore.existing(name)
        if (!WorkspaceStore.isReady(workspace)) {
            throw IOException("This workspace is incomplete")
        }
        partition = WorkspaceStore.readProps(workspace).getProperty("partition", WorkspaceStore.PARTITION)
        if (!WorkspaceNames.isValid(partition)) {
            throw IOException("Invalid partition name")
        }
        root = File(workspace, partition).toPath().toRealPath()
        if (!root.startsWith(workspace.toPath().toRealPath())) {
            throw IOException("Invalid partition directory")
        }
        manager = FileManager(root.toFile(), CollisionNaming.DASH_NUMBER)
    }

    @Throws(IOException::class)
    fun execute(request: FileRequest, cancellation: CancellationToken, progress: ProgressListener?): FileResult {
        if ((request is FileRequest.ReadFile)) {
            return manager.execute(request, cancellation, progress)
        }
        val mutation: Boolean = !(((request is FileRequest.Stat) || (request is FileRequest.ListDirectory)) || (request is FileRequest.ReadFile))
        if (mutation) {
            GsiMetadata.checkHealthy(workspace)
        }
        val metadata: GsiMetadata = GsiMetadata(workspace, partition)
        if (!mutation) {
            return decorate(manager.execute(request, cancellation, progress), metadata)
        }
        validate(request)
        metadata.begin()
        var callbackFailed: BooleanArray = booleanArrayOf(false)
        var result: FileResult? = null
        var committing: Boolean = false
        try {
            if ((request is FileRequest.SetPermissions)) {
                result = permissions((request as FileRequest.SetPermissions), metadata, cancellation, progress)
            } else {
                result = manager.execute(request, cancellation, progress, { change ->
                    try {
                        metadata.apply(change)
                    } catch (error: IOException) {
                        callbackFailed[0] = true
                        throw error
                    } catch (error: RuntimeException) {
                        callbackFailed[0] = true
                        throw error
                    }
                })
            }
            if (callbackFailed[0]) {
                throw IOException("The edit could not finish safely. Import this workspace again before repacking.")
            }
            committing = true
            if ((result!!.report.changeCount == 0L)) {
                metadata.abort()
            } else {
                metadata.save()
            }
        } catch (error: IOException) {
            if (((request is FileRequest.SetPermissions) && !committing)) {
                metadata.abort()
            }
            throw error
        } catch (error: RuntimeException) {
            if (((request is FileRequest.SetPermissions) && !committing)) {
                metadata.abort()
            }
            throw error
        }
        return result!!
    }

    private fun decorate(result: FileResult, metadata: GsiMetadata): FileResult {
        if (!(result is FileResult.Success)) {
            return result
        }
        val success: FileResult.Success = (result as FileResult.Success)
        var value: FileValue = success.value
        if ((value is FileValue.Listing)) {
            val entries: ArrayList<FileEntry> = ArrayList<FileEntry>()
            for (entry in (value as FileValue.Listing).entries) {
                entries.add(decorate(entry, metadata))
            }
            value = FileValue.Listing(entries)
        } else if ((value is FileValue.Entry)) {
            value = FileValue.Entry(decorate((value as FileValue.Entry).entry, metadata))
        }
        return FileResult.Success(result.operationId, value, result.report)
    }

    private fun decorate(entry: FileEntry, metadata: GsiMetadata): FileEntry {
        return FileEntry(entry.path, entry.name, entry.kind, entry.size, entry.modifiedAtMillis, metadata.mode(entry.path, (entry.kind == FileKind.DIRECTORY), (entry.kind == FileKind.SYMLINK)), entry.symlinkTarget)
    }

    @Throws(IOException::class)
    private fun permissions(request: FileRequest.SetPermissions, metadata: GsiMetadata, cancellation: CancellationToken, progress: ProgressListener?): FileResult {
        if (((request.mode < 0) || (request.mode > 4095))) {
            throw IOException("Use an octal mode from 0000 to 7777")
        }
        val stat: FileResult = manager.execute(FileRequest.Stat(request.path), cancellation)
        if (!(stat is FileResult.Success)) {
            return stat
        }
        val entry: FileEntry = ((stat as FileResult.Success).value as FileValue.Entry).entry
        val id: String = UUID.randomUUID().toString()
        if ((entry.kind == FileKind.SYMLINK)) {
            // A symlink's mode is only an entry in the image's fs_config, so it can be edited directly.
            // The link is never followed and nothing is applied below it.
            val single: ArrayList<FileChange> = ArrayList<FileChange>()
            var changed: Long = 0L
            if ((metadata.mode(entry.path, false, true) != request.mode)) {
                metadata.setMode(entry.path, request.mode)
                single.add(FileChange(ChangeKind.PERMISSIONS_CHANGED, entry.path, null))
                changed = 1L
            }
            val linkReport: OperationReport = OperationReport(single, changed, Collections.emptyList(), 0L, 0L, 0L, false, Collections.emptyList())
            return FileResult.Success(id, FileValue.Done, linkReport)
        }
        val changes: ArrayList<FileChange> = ArrayList<FileChange>()
        val skipped: ArrayList<String> = ArrayList<String>()
        var counts: LongArray = longArrayOf(0L, 0L)
        var stopped: BooleanArray = booleanArrayOf(false)
        val start: Path = root.resolve(entry.path)
        emit(progress, OperationProgress(id, OperationStage.STARTED, entry.path, 0L, 0L))
        Files.walkFileTree(start, Collections.emptySet(), (if (request.recursive) java.lang.Integer.MAX_VALUE else 0), object : SimpleFileVisitor<Path>() {
            @Throws(IOException::class)
            private fun update(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (cancellation.isCancelled) {
                    stopped[0] = true
                    return FileVisitResult.TERMINATE
                }
                val relative: String = root.relativize(path).toString()
                if (attrs.isSymbolicLink()) {
                    counts[1]++
                    if ((skipped.size < 256)) {
                        skipped.add(relative)
                    }
                } else {
                    val top: Boolean = path.equals(start)
                    if (((!top && attrs.isDirectory()) && !request.applyToDirectories)) {
                        return FileVisitResult.CONTINUE
                    }
                    if (((!top && !attrs.isDirectory()) && !request.applyToFiles)) {
                        return FileVisitResult.CONTINUE
                    }
                    if ((metadata.mode(relative, attrs.isDirectory(), false) == request.mode)) {
                        return FileVisitResult.CONTINUE
                    }
                    metadata.setMode(relative, request.mode)
                    counts[0]++
                    if ((changes.size < 256)) {
                        changes.add(FileChange(ChangeKind.PERMISSIONS_CHANGED, relative, null))
                    }
                }
                if ((((counts[0] + counts[1]) % 128L) == 0L)) {
                    emit(progress, OperationProgress(id, OperationStage.PROCESSING, relative, 0L, counts[0]))
                }
                return FileVisitResult.CONTINUE
            }

            @Throws(IOException::class)
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                return update(dir, attrs)
            }

            @Throws(IOException::class)
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                return update(file, attrs)
            }
        })
        val report: OperationReport = OperationReport(changes, counts[0], skipped, counts[1], 0L, 0L, false, Collections.emptyList())
        emit(progress, OperationProgress(id, OperationStage.FINISHED, entry.path, 0L, counts[0]))
        return (if (stopped[0]) FileResult.Cancelled(id, report) else FileResult.Success(id, FileValue.Done, report))
    }

    @Throws(IOException::class)
    private fun validate(request: FileRequest) {
        if ((request is FileRequest.CreateFile)) {
            GsiMetadata.validatePath((request as FileRequest.CreateFile).path)
        }
        if ((request is FileRequest.CreateDirectory)) {
            GsiMetadata.validatePath((request as FileRequest.CreateDirectory).path)
        }
        if ((request is FileRequest.CreateSymlink)) {
            val link: FileRequest.CreateSymlink = (request as FileRequest.CreateSymlink)
            GsiMetadata.validatePath(link.path)
            if (link.target.codePoints().anyMatch(java.lang.Character::isISOControl)) {
                throw IOException("Link targets cannot contain control characters")
            }
        }
        if ((request is FileRequest.Copy)) {
            GsiMetadata.validatePath((request as FileRequest.Copy).destination)
        }
        if ((request is FileRequest.Move)) {
            GsiMetadata.validatePath((request as FileRequest.Move).destination)
        }
        if ((request is FileRequest.Rename)) {
            GsiMetadata.validatePath((request as FileRequest.Rename).newName)
        }
    }

    override fun close() {
        manager.close()
    }

    companion object {
        private fun emit(listener: ProgressListener?, progress: OperationProgress) {
            if ((listener != null)) {
                try {
                    listener!!.onProgress(progress)
                } catch (ignored: RuntimeException) {

                }
            }
        }
    }
}
