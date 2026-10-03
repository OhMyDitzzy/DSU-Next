# Edit GSI

Edit GSI lets you unpack a GSI on the device, change its files with a root file manager, and build it back into an image. The result is a normal `.img` that you can install as a DSU from the home screen.

> [!WARNING]
> Editing a GSI carelessly can leave a device unable to boot. Change only files you understand and keep the original image as a backup.

## Requirements

- **Root.** Unpacked files live in `/data/local`, which only root can reach. Grant DSU Next root in your root manager.
- **A root file manager** to edit the files. The app is written around MT Manager; any file manager that can open a folder with root access works.
- **Free space in `/data`.** At least the size of the image, plus room for the unpacked files.
- **An unlocked bootloader** to boot the result.

## Why root is required

For now Edit GSI only works on rooted devices. The reason is not the unpacking itself, it is the editing step: DSU Next does not have a built-in file manager yet.

A workspace could be kept in a folder managed through the Storage Access Framework (SAF) and edited with an external file manager, but that would hide part of the GSI. Files with special permissions, owners or SELinux labels, and symlinks, do not show up in an app that reaches the files through SAF. Android system images are full of them, so you would be editing a partial view of the GSI without knowing it, and a repack could not be trusted to match what you meant to change.

Keeping the workspace in `/data/local` and opening it with a root file manager shows the real tree, symlinks included. That is why the feature asks for root and a root file manager today.

## Workflow

### 1. Open the screen

**Tools > Edit your GSI** shows a warning first. Tap **I understand** to continue or **Go back**. The app then connects to a root service. If root is refused you get a "Root access required" screen, and if the service stops you get a "Root service unavailable" screen with **Try again**.

### 2. Import

Tap **Import** and pick a file. You can accept the suggested workspace name, which is made from the file name (`aosp-arm64.img.xz` becomes `aosp-arm64`), or type your own. A name must start with a letter or digit, may contain letters, digits, dot, dash and underscore, and is at most 64 characters. It must not already exist.

Accepted input:

| Input | Handling |
| --- | --- |
| Raw ext4 or EROFS image | Unpacked directly. |
| Android sparse image | Converted to a raw image in a staging folder, then unpacked. |
| `.zip`, `.7z`, `.tar` | The system image inside is found and extracted, then unpacked. |
| `.gz`, `.xz`, `.tgz`, `.txz` | Decompressed (or, for a tar, searched) the same way. |

The format is detected from the file contents, not the extension. An EROFS image is recognized by its magic number at offset 1024, an ext4 image by its magic number at offset 1080.

When an archive holds several images, the app picks the most likely system image: `system.img` first, then other `system*.img` files, then any other `.img`. Images that are clearly something else are skipped: `vbmeta`, `boot`, `init_boot`, `vendor_boot`, `recovery`, `dtbo`, `userdata`, `cache`, `super`, `misc`, `metadata`, `vendor`, `product`, `odm`, `system_ext` and `system_other`.

Before unpacking, the app checks that `/data` has room for the image and stops with a message if not. A failed import removes the workspace again. If one is left behind it shows as **Incomplete import** and can be deleted.

### 3. Edit

Tap a workspace. The dialog tells you to give your file manager root access first, then tap **Open** and choose it from the **Open with** list. If no app opens the folder, use **Copy path** and open the path yourself.

Edit the files under the `system/` folder of the workspace. Deleted files are left out of the new image. New files get Android-like defaults for owner, mode and SELinux label, based on their folder, and the repack reports how many files that applied to. To set them yourself, edit the metadata files in `config/`.

### 4. Repack

Open the workspace menu and choose **Repack**.

- **Image size** (ext4 only):
  - *Original size* keeps the size of the imported image. Repacking fails if the files no longer fit.
  - *Smallest that fits* builds the smallest image that holds the files.
  - *Custom* takes bytes, or a number followed by `K`, `M` or `G`, for example `3G` or `3500M`.
- **Image name.** The output is `<name>.img`. Same naming rules as a workspace.
- EROFS images are always built as small as possible, so the size option does not apply. A compressed EROFS partition is rebuilt with LZ4 whatever algorithm it used before.

If the original size fails, try *Smallest that fits* or a larger custom size.

### 5. Save

When the repack finishes, tap **Save…** and choose where to put the image. The copy runs in the root service and reports progress. The image also stays in the workspace `output/` folder until you delete the workspace.

Install the saved image from the home screen like any other GSI

### Info and delete

**Info** shows the file system, mount point, original and unpacked sizes, the number of files, folders and symlinks, block size, UUID, source file, import time and last repack. It also reads `build.prop` live for the Android version and API level, build ID and fingerprint, so your edits show up there. **Delete** removes the unpacked files from the device and cannot be undone.

## Where things live

```
/data/local/dsu-next-workspace/<name>/
  system/            the unpacked files, edit these
  config/            owners, permissions, SELinux labels, symlinks and file system parameters
  output/            repacked images
  workspace.prop     details of the import and the last repack
  .busy              present while an import is running
  .staging/          temporary files during an import
```

The partition is always called `system`. The files in `config/` follow the layout Jancox uses: `system_fs_config` for owner, group, mode and capabilities, `system_file_contexts` for SELinux labels, `system_symlinks` for symlinks and `system_info` for file system parameters.

## AVB footer

A rebuilt image no longer matches its dm-verity hash tree, so after building, DSU Next appends a new AVB hashtree footer. It is the equivalent of:

```
avbtool add_hashtree_footer --image <image> --partition_name system --hash_algorithm sha256 --do_not_generate_fec
```

There is no FEC data and no signing key, so the vbmeta is unsigned and the image only boots on a device with an unlocked bootloader. The image grows by the hash tree plus two blocks. If adding the footer fails, the output is deleted, because an image without its footer does not boot.

## Limitations

- Root is required, and so is an external root file manager, because DSU Next has no built-in file manager yet.
- The result is a raw ext4 or EROFS image, not a sparse one.
- Only one import, repack or save runs at a time.

See [troubleshooting](troubleshooting.md) for common problems.
