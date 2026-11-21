# Edit GSI

Edit GSI unpacks an image into private app storage, lets you edit it with the
built-in file manager, and rebuilds it as a normal `.img`. Extraction, editing,
repacking, and exporting work without root. Installing or booting the result
still follows the device's DSU requirements.

> [!WARNING]
> Editing a GSI carelessly can leave a device unable to boot. Change only files
> you understand and keep the original image as a backup.

## Requirements

- Free internal storage for the input, unpacked files, and rebuilt image.
- An APK containing `libyuki.so` for the device's ABI.
- An unlocked bootloader to boot a modified image.

## Import

Open **Tools > Edit your GSI**, accept the warning, and tap **Import**. Choose an
image or archive and accept the suggested workspace name or enter your own.
Workspace names use letters, digits, dot, dash, and underscore, start with a
letter or digit, and are at most 64 characters.

| Input | Handling |
| --- | --- |
| Raw ext4 or EROFS | Unpacked directly. |
| Android sparse image | Converted to a raw image before extraction. |
| `.zip`, `.7z`, `.tar` | The most likely system image is extracted. |
| `.gz`, `.xz`, `.tgz`, `.txz` | Decompressed, then processed as an image or tar. |

Formats are detected from their contents. Archives prefer `system.img`, then
other `system*.img` names, then other image candidates. Boot, vbmeta, userdata,
and other clearly unrelated partition names are skipped. The app checks free
space before extraction. Failed imports are removed when possible; an incomplete
workspace can be deleted and imported again.

## Edit files

Tap a ready workspace to open its extracted partition in the built-in browser.
Tap a folder to enter it; Back moves to its parent and exits at the image root.
The toolbar menu creates files or folders, imports a file through the system
picker, creates a symlink, refreshes the listing, and pastes copied or cut entries.

Tap a file, hold a row, or use its options button for copy, cut, rename, delete,
properties, image permissions, text editing, and export. The text editor supports
UTF-8 files up to 256 KiB. Export larger or binary files and import the edited
result. Symlinks are shown as links; their targets are inspected in Properties
and are never opened as paths on the host device.

Names containing whitespace or control characters are rejected because the
image metadata format cannot safely represent them. New regular files get
Android-like defaults; files in `bin` or `xbin` are executable. Existing UID/GID,
capabilities, modes, and SELinux labels are preserved through copy and rename.
New entries inherit their parent's label when available.

**Image permissions** edits the mode that will be stored in the rebuilt image.
It leaves the physical working files accessible to the app. Modes are octal,
for example `0644` or `0755`; recursive edits skip symlinks.

An existing destination produces a conflict dialog. Choose **Replace** for a
file, **Keep both**, **Skip**, or **Merge folders**. The GSI browser uses names
such as `file-1.txt` when keeping both, so sidecars can represent them. Folder merging is explicit;
existing destination contents are preserved. Before merging, choose whether
child conflicts stop the operation, replace files, keep both, or skip entries. A partial result reports completed changes and skipped entries. A move
that skips children keeps its source so skipped data is retained.

Operations show progress and support cancellation between entries or stream
chunks. Cancellation keeps changes that already completed. The browser's state
and active operation survive activity rotation; a running native image job lasts
for the lifetime of the app process.

## Repack and save

Return to the workspace list, open its menu, and choose **Repack**.

- **Original size** keeps an ext4 image's original capacity.
- **Smallest that fits** sizes the image for its current contents.
- **Custom** accepts bytes or a number with `K`, `M`, or `G`, such as `3G`.
- EROFS uses the smallest fitting size and is rebuilt with LZ4 compression.

If the original size fails, choose a larger size or **Smallest that fits**. When
repacking finishes, tap **Save…** to export the image with the system picker.
The output also remains in the workspace until it is deleted.

## AVB footer

After rebuilding, the app adds an unsigned AVB hashtree footer equivalent to:

```text
avbtool add_hashtree_footer --image <image> --partition_name system --hash_algorithm sha256 --do_not_generate_fec
```

There is no FEC or signing key. The output is deleted if footer generation fails.
See [troubleshooting](troubleshooting.md) if the rebuilt image does not boot.

## Storage and recovery

```text
Context.filesDir/gsi-workspaces/<name>/
  system/             extracted partition and browser root
  config/             Yuki owners, modes, labels, symlinks, and image parameters
  output/             rebuilt images
  workspace.prop      import and repack information
  .busy               import in progress
  .staging/           temporary import files
  .metadata-pending   an edit whose metadata commit has not finished
```

For the standard package this is under
`/data/data/com.ditzzy.dsunext/files/gsi-workspaces/`. Android can choose a different
physical data path; the app resolves it through `Context.filesDir`. Workspaces
are excluded from automatic backup and device transfer. Uninstalling the app or
clearing its storage deletes them; export images you want to keep.

The editor updates `system_fs_config`, `system_file_contexts`, and
`system_symlinks` after complete, partial, and cancelled operations. Deleted links
are removed from the sidecar so they stay deleted after repack. Metadata writes
use temporary files and a pending marker. If an edit is interrupted before all
sidecars commit, editing and repacking are blocked, while browsing and exporting files remain
available for recovery. Re-import the original image
into a new workspace to recover safely. Temporary `.file-manager-*.tmp` files can
remain after process death.

Workspaces created by older versions in `/data/local/dsu-next-workspace` remain
there and are not migrated or deleted automatically. Export those workspaces
using the older version and import their images again.

## Backend API

The Kotlin `:file-manager` module implements general file operations. The Android
UI and GSI metadata adapter live in `:app`.

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
names such as `file (1).txt`. Constructing a manager with
`CollisionNaming.DASH_NUMBER` uses `file-1.txt`, as the GSI adapter does. `REPLACE` replaces a file or symlink of the same
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

The app depends on `:file-manager`. `GsiFileEditor` adapts the generic backend to
Yuki's sidecars. Its `ChangeListener` receives every committed entry, including
entries omitted from the capped result samples. Unlike progress callbacks,
change callback failures stop further mutations and produce a partial result.
`WorkspaceJobs` serializes image import, repack, export, deletion, and editor work
inside the application process.
