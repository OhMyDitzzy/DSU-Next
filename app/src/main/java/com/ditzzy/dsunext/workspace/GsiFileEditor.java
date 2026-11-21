package com.ditzzy.dsunext.workspace;

import com.ditzzy.dsunext.filemanager.FileManager;
import com.ditzzy.dsunext.filemanager.api.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

/* All calls run on WorkspaceJobs, including the metadata commit after a partial operation. */
public final class GsiFileEditor implements AutoCloseable {
    private final File workspace;
    private final String partition;
    private final FileManager manager;
    private final Path root;

    public GsiFileEditor(String name) throws IOException {
        workspace = WorkspaceStore.existing(name);
        if (!WorkspaceStore.isReady(workspace)) throw new IOException("This workspace is incomplete");
        partition = WorkspaceStore.readProps(workspace).getProperty("partition", WorkspaceStore.PARTITION);
        if (!WorkspaceNames.isValid(partition)) throw new IOException("Invalid partition name");
        root = new File(workspace, partition).toPath().toRealPath();
        if (!root.startsWith(workspace.toPath().toRealPath())) throw new IOException("Invalid partition directory");
        manager = new FileManager(root.toFile(), CollisionNaming.DASH_NUMBER);
    }

    public FileResult execute(FileRequest request, CancellationToken cancellation, ProgressListener progress) throws IOException {
        if (request instanceof FileRequest.ReadFile) return manager.execute(request, cancellation, progress);
        boolean mutation = !(request instanceof FileRequest.Stat || request instanceof FileRequest.ListDirectory
                || request instanceof FileRequest.ReadFile);
        if (mutation) GsiMetadata.checkHealthy(workspace);
        GsiMetadata metadata = new GsiMetadata(workspace, partition);
        if (!mutation) return decorate(manager.execute(request, cancellation, progress), metadata);
        validate(request);
        metadata.begin();
        boolean[] callbackFailed = {false};
        FileResult result;
        boolean committing = false;
        try {
            if (request instanceof FileRequest.SetPermissions) {
                result = permissions((FileRequest.SetPermissions) request, metadata, cancellation, progress);
            } else {
                result = manager.execute(request, cancellation, progress, change -> {
                    try { metadata.apply(change); }
                    catch (IOException | RuntimeException error) { callbackFailed[0] = true; throw error; }
                });
            }
            if (callbackFailed[0]) throw new IOException("The edit could not finish safely. Import this workspace again before repacking.");
            committing = true;
            if (result.getReport().getChangeCount() == 0) metadata.abort();
            else metadata.save();
        } catch (IOException | RuntimeException error) {
            // A virtual permission edit can be discarded until its sidecars start committing.
            if (request instanceof FileRequest.SetPermissions && !committing) metadata.abort();
            throw error;
        }
        return result;
    }

    private FileResult decorate(FileResult result, GsiMetadata metadata) {
        if (!(result instanceof FileResult.Success)) return result;
        FileResult.Success success = (FileResult.Success) result;
        FileValue value = success.getValue();
        if (value instanceof FileValue.Listing) {
            ArrayList<FileEntry> entries = new ArrayList<>();
            for (FileEntry entry : ((FileValue.Listing) value).getEntries()) entries.add(decorate(entry, metadata));
            value = new FileValue.Listing(entries);
        } else if (value instanceof FileValue.Entry) {
            value = new FileValue.Entry(decorate(((FileValue.Entry) value).getEntry(), metadata));
        }
        return new FileResult.Success(result.getOperationId(), value, result.getReport());
    }

    private FileEntry decorate(FileEntry entry, GsiMetadata metadata) {
        return new FileEntry(entry.getPath(), entry.getName(), entry.getKind(), entry.getSize(),
                entry.getModifiedAtMillis(), metadata.mode(entry.getPath(), entry.getKind() == FileKind.DIRECTORY,
                entry.getKind() == FileKind.SYMLINK), entry.getSymlinkTarget());
    }

    private FileResult permissions(FileRequest.SetPermissions request, GsiMetadata metadata,
            CancellationToken cancellation, ProgressListener progress) throws IOException {
        if (request.getMode() < 0 || request.getMode() > 07777) throw new IOException("Use an octal mode from 0000 to 7777");
        FileResult stat = manager.execute(new FileRequest.Stat(request.getPath()), cancellation);
        if (!(stat instanceof FileResult.Success)) return stat;
        FileEntry entry = ((FileValue.Entry) ((FileResult.Success) stat).getValue()).getEntry();
        String id = UUID.randomUUID().toString();
        if (entry.getKind() == FileKind.SYMLINK) {
            // A symlink's mode is only an entry in the image's fs_config, so it can be edited directly.
            // The link is never followed and nothing is applied below it.
            ArrayList<FileChange> single = new ArrayList<>();
            long changed = 0;
            if (metadata.mode(entry.getPath(), false, true) != request.getMode()) {
                metadata.setMode(entry.getPath(), request.getMode());
                single.add(new FileChange(ChangeKind.PERMISSIONS_CHANGED, entry.getPath(), null));
                changed = 1;
            }
            OperationReport linkReport = new OperationReport(single, changed, Collections.emptyList(), 0, 0, 0, false, Collections.emptyList());
            return new FileResult.Success(id, FileValue.Done.INSTANCE, linkReport);
        }
        ArrayList<FileChange> changes = new ArrayList<>();
        ArrayList<String> skipped = new ArrayList<>();
        long[] counts = {0, 0};
        boolean[] stopped = {false};
        Path start = root.resolve(entry.getPath());
        emit(progress, new OperationProgress(id, OperationStage.STARTED, entry.getPath(), 0, 0));
        Files.walkFileTree(start, Collections.emptySet(), request.getRecursive() ? Integer.MAX_VALUE : 0,
                new SimpleFileVisitor<Path>() {
                    private FileVisitResult update(Path path, BasicFileAttributes attrs) throws IOException {
                        if (cancellation.isCancelled()) { stopped[0] = true; return FileVisitResult.TERMINATE; }
                        String relative = root.relativize(path).toString();
                        if (attrs.isSymbolicLink()) {
                            counts[1]++;
                            if (skipped.size() < 256) skipped.add(relative);
                        } else {
                            boolean top = path.equals(start);
                            if (!top && attrs.isDirectory() && !request.getApplyToDirectories()) return FileVisitResult.CONTINUE;
                            if (!top && !attrs.isDirectory() && !request.getApplyToFiles()) return FileVisitResult.CONTINUE;
                            if (metadata.mode(relative, attrs.isDirectory(), false) == request.getMode()) return FileVisitResult.CONTINUE;
                            metadata.setMode(relative, request.getMode());
                            counts[0]++;
                            if (changes.size() < 256) changes.add(new FileChange(ChangeKind.PERMISSIONS_CHANGED, relative, null));
                        }
                        if ((counts[0] + counts[1]) % 128 == 0) emit(progress, new OperationProgress(id, OperationStage.PROCESSING, relative, 0, counts[0]));
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        return update(dir, attrs);
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        return update(file, attrs);
                    }
                });
        OperationReport report = new OperationReport(changes, counts[0], skipped, counts[1], 0, 0, false, Collections.emptyList());
        emit(progress, new OperationProgress(id, OperationStage.FINISHED, entry.getPath(), 0, counts[0]));
        return stopped[0] ? new FileResult.Cancelled(id, report) : new FileResult.Success(id, FileValue.Done.INSTANCE, report);
    }

    private static void emit(ProgressListener listener, OperationProgress progress) {
        if (listener != null) {
            try { listener.onProgress(progress); } catch (RuntimeException ignored) {}
        }
    }

    private void validate(FileRequest request) throws IOException {
        if (request instanceof FileRequest.CreateFile) GsiMetadata.validatePath(((FileRequest.CreateFile) request).getPath());
        if (request instanceof FileRequest.CreateDirectory) GsiMetadata.validatePath(((FileRequest.CreateDirectory) request).getPath());
        if (request instanceof FileRequest.CreateSymlink) {
            FileRequest.CreateSymlink link = (FileRequest.CreateSymlink) request;
            GsiMetadata.validatePath(link.getPath());
            if (link.getTarget().codePoints().anyMatch(Character::isISOControl)) throw new IOException("Link targets cannot contain control characters");
        }
        if (request instanceof FileRequest.Copy) GsiMetadata.validatePath(((FileRequest.Copy) request).getDestination());
        if (request instanceof FileRequest.Move) GsiMetadata.validatePath(((FileRequest.Move) request).getDestination());
        if (request instanceof FileRequest.Rename) GsiMetadata.validatePath(((FileRequest.Rename) request).getNewName());
    }

    @Override public void close() { manager.close(); }
}
