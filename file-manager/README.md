# File Manager

`:file-manager` provides workspace-scoped file operations in Kotlin. Its runtime
uses Java NIO and the caller's filesystem permissions. It has no UI, root shell,
Android `Context`, or dependency on Yuki. The app supplies an existing directory;
an internal GSI workspace can live under `Context.filesDir/<workspace-id>`.

## Calling the backend

`execute` blocks and is intended for a caller-owned I/O thread. `submit` queues
work on the manager's worker and immediately returns an `OperationHandle`.

```kotlin
val workspace = File(context.filesDir, workspaceId)
check(workspace.isDirectory || workspace.mkdirs())
val manager = FileManager(workspace)

val handle = manager.submit(
    FileRequest.Copy(
        source = "system/etc/example.conf",
        destination = "system/etc/backup/example.conf",
        createParents = true,
    ),
    ProgressListener { progress -> /* Forward to the UI thread. */ },
)

handle.onComplete { result ->
    when (result) {
        is FileResult.Success -> { /* Refresh the destination. */ }
        is FileResult.Failure -> { /* Show a message using result.error.code. */ }
        is FileResult.Partial -> { /* Refresh and show what remains to be done. */ }
        is FileResult.Cancelled -> { /* Refresh using result.report. */ }
    }
}
```

Imports for this example are `java.io.File`,
`com.ditzzy.dsunext.filemanager.FileManager`, and
`com.ditzzy.dsunext.filemanager.api.*`.

Java callers can use the same API:

```java
FileManager manager = new FileManager(workspace);
OperationHandle handle = manager.submit(new FileRequest.CreateFile(
        "system/etc/new.conf",
        () -> new ByteArrayInputStream("enabled=true\n".getBytes(StandardCharsets.UTF_8)),
        true,
        ConflictPolicy.FAIL));
handle.onComplete(result -> activity.runOnUiThread(() -> showResult(result)));
```

Keep the manager alive while its operations run. `close()` requests cancellation
of its active and queued operations, rejects new work with `CLOSED`, and lets
queued handles receive their final results. It returns without joining threads.

Listeners run on the operation thread, or the registering thread if completion
was already available. Dispatch UI work to the main thread. Keep callbacks short;
waiting for another operation from a callback can block the worker. Synchronous
reentry during an active operation returns `BUSY`.

## Requests

Paths are relative to the supplied workspace. A destination includes its full
new name: copying `a.txt` into `backup/` uses `backup/a.txt`. `""` and `"."` address
the workspace root for `Stat` and `ListDirectory`; mutations of the root are
rejected.

| Request | Behavior |
| --- | --- |
| `Stat` | Reads type, size, modification time, POSIX mode, and a symlink's raw target. |
| `ListDirectory` | Lists immediate children, with directories first and then names in lexical order. |
| `CreateFile` | Creates an empty file by default, or streams initial content from an `InputSource`. |
| `CreateDirectory` | Creates a directory, optionally creating missing parents. |
| `ReadFile` | Streams a regular file to an `OutputTarget`. |
| `WriteFile` | Replaces an existing regular file's content while keeping its mode. |
| `Copy` | Copies files, directory trees, and symlinks. |
| `Move` | Tries a filesystem move; merges and cross-filesystem moves use copy followed by source deletion. |
| `Rename` | Moves an entry to a single new name in the same parent. |
| `Delete` | Deletes an entry; nonempty directories require `recursive = true`. |
| `SetPermissions` | Sets a physical POSIX mode, optionally recursively. Recursive traversal skips symlinks. |
| `CreateSymlink` | Stores the target verbatim, including dangling, relative, and absolute targets. |

Stream factories open on the operation thread. The backend closes each returned
stream, including on failure. For Android document URIs, the app can provide a
factory backed by `ContentResolver`. Factories are trusted caller code and must
avoid mutating the managed workspace or using the input file as an output sink.

File streaming uses a 64 KiB buffer. Directory traversal uses NIO's tree walker;
`ListDirectory` materializes one directory's entries to sort them. Files, folders,
and symlinks are supported; device nodes, sockets, and FIFOs are rejected for
streaming and transfer.

## Conflicts

`FAIL` is the default. `SKIP` leaves an existing entry alone; `KEEP_BOTH` picks
names such as `file (1).txt`. `REPLACE` replaces a file or symlink of the same
type. Replacing a directory requires an explicit merge rather than deleting its
existing contents.

For `Copy` and `Move`, `DirectoryPolicy.MERGE` merges directory pairs and applies
the conflict policy to their children. Existing destination directories keep
their own attributes. With the default `DirectoryPolicy.FAIL`, existing
directories follow `FAIL`, `SKIP`, or `KEEP_BOTH`; `REPLACE` returns a conflict.

A move with skipped entries keeps the source. If other entries were already
copied, the result is `Partial`, with `ALREADY_EXISTS` and a change report. If
everything was skipped, the result is `Success` with a null destination and
skipped entries in the report. The frontend can use those details to offer a
follow-up operation.

## Results and progress

`Success` carries a `FileValue`: an entry, a listing, the actual destination path,
or `Done`. A skipped create/copy/move has a null destination.

`Failure` means the backend recorded no published filesystem changes or external
output writes. `Partial` means some changes, an output-open attempt, or an
unremoved temporary file were recorded before failure. `Cancelled` always
includes its report; cancellation keeps completed changes.

All results carry an operation ID and an `OperationReport`. Change and skip
samples are capped at 256 entries each; their counts remain complete. The report
also contains processed bytes, known output bytes, and temporary-file cleanup
errors. A failing output write can have written more bytes than it reported, so
`outputOpened` conservatively marks the external sink as potentially changed.

Errors include `ALREADY_EXISTS`, `NOT_FOUND`, `ACCESS_DENIED`, `NO_SPACE`,
`TYPE_MISMATCH`, `SOURCE_CHANGED`, and `UNSUPPORTED`. Localize by error code in
the app. Messages and causes are available for diagnostics; filesystem reasons
that do not map to a known code use `IO_ERROR`.

Progress has `STARTED`, throttled `PROCESSING` updates, and `FINISHED`, with byte
and change counts. It does not prescan a tree to calculate a percentage. Callback
exceptions are isolated from the operation.

Use `OperationHandle.cancel()` for submitted work, or pass a `CancellationToken`
to `execute`. Cancellation is checked between entries and stream chunks; a
blocking provider's `read`, `write`, or `open` must return before work can stop.
A cancellation request arriving after the final commit can still yield success.
`await()` and its timed overload are available for background callers.

## Filesystem behavior

Files are written to temporary siblings, flushed, and published after the input
has closed successfully. A new file is published through a hard link to avoid
clobbering an existing name. Replacement requires an atomic filesystem move.
Filesystem capability failures return errors while preserving the existing
destination. Temporary files are cleaned in `finally`; cleanup failures are
included in the result.

Recursive operations commit entries individually and report partial completion.
Directory trees are not transactional. A process crash can leave temporary
siblings named `.file-manager-*.tmp`; the workspace lifecycle can remove them
after establishing that no operation is using them.

Managers sharing the same canonical root serialize operations within the
process. Use one workspace root per tree and coordinate all mutations through
the backend. Other processes, direct filesystem writes, or managers using
overlapping roots require caller-side coordination. Path validation and
no-follow opens assume that ownership model; the Android app sandbox remains
the security boundary.

Absolute request paths and `..` traversal are rejected. Parent symlinks are
rejected, and copy/delete operate on the link itself. Opening or listing a link
returns `SYMLINK_NOT_ALLOWED`. GSI-aware navigation of image-absolute links can be
added with the image metadata adapter.

Copy preserves physical permission modes and access/modification times for
regular files and newly created directories by default. Symlinks retain their
raw target. Ownership, SELinux labels, and image metadata are outside this layer.
Modes are integers: `420` is `0644`, `493` is `0755`; valid modes are `0..4095`
(`0000..07777`). Filesystems exposing only basic POSIX permissions reject
special mode bits. Symlink modes are never changed.

## Development

The module uses compile SDK 36, minimum SDK 29, and JVM target 17. JUnit 4 is a
test dependency only. Regression tests cover conflicts, streaming failures,
cancellation, workspace boundaries, symlinks, recursive operations, permissions,
and result reporting.

The app can add `implementation project(':file-manager')` when integration starts.
Moving the extraction flow out of its current root service and synchronizing
Yuki's `fs_config`, `file_contexts`, and symlink metadata are separate steps.
