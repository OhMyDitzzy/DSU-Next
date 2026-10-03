# Troubleshooting

## Installation

DSU Next reads logcat during an installation and turns the common failures into a clear message. The table lists each one with what to try.

| Message | Cause | What to try |
| --- | --- | --- |
| Installing a DSU when running an installed DSU is not supported | You are booted into a DSU. | Reboot to the default system and install again. |
| Allocation error | Allocation on the SD card is failing. | Use a module that ships sepolicy fixes for gsid, or unmount the SD card. **Unmount SD and retry** does the second one, and the option can be made permanent in Settings. |
| Storage error | Android needs a minimum share of free storage to install. | Free up space and try again. |
| FS features unavailable | The kernel probably registers f2fs under a different path than the one gsid expects. | Use a module that ships a gsid binary that handles `f2fs_dev`, or change the kernel. |
| SELinux error | SELinux denials block the installation. | Use a module that ships sepolicy fixes for gsid. Unmount the SD card if you use one. As a last resort, **Retry with permissive**, which weakens device security. |
| Extents error | On Android 10 the image needs more than 512 extents. | Use a module with a patched gsid binary. |
| Failed to create a partition | gsid could not create the partition. | Check free storage and the logs. |
| Unknown error | Nothing matched. | Open **View logs** and read the last lines. |

Use **View logs** on any error to see what the app saw. **Save logs** writes them to a file for a bug report.

### The installation finishes but the device does not boot into the DSU

AVB is the usual reason: it can block the installed images. The upstream DSU Sideloader README suggests flashing a disabled `vbmeta`. This needs an unlocked bootloader.

### Unsupported device

If the app says the device does not support dynamic partitions, DSU cannot work on it.

### The app asks for READ_LOGS

Progress tracking and diagnostics need it. Grant it from a mode that can (root, system or Shizuku), and restart the app if it asks.

### Other things to check

- Pick a GSI that matches the CPU architecture, A/B scheme and VNDK of the device. The [Treble check](usage.md#treble-check) shows them.
- Make sure the storage folder has free space for an extracted or compressed image.

## Edit GSI

| Problem | What to try |
| --- | --- |
| Root access required | Grant DSU Next root in your root manager, then try again. |
| Root service unavailable, or the service stopped | Tap **Try again**. If it keeps happening, reopen the app. |
| Unsupported file | The input is not an ext4 or EROFS image, or a zip, 7z, tar, gz or xz archive that contains one. |
| No system image found in the archive | The archive has no image the app recognizes as the system image. The message lists up to eight entries it saw. |
| Not enough free space in /data | Free up space. The image needs room, and so do the unpacked files. |
| A workspace with this name already exists | Pick another name. |
| Incomplete import | The import did not finish. Delete the workspace and import again. |
| Repack fails after your edits | The files no longer fit the original size. Repack with *Smallest that fits* or a larger custom size. |
| No app could open the folder | Use **Copy path** and open it in a file manager that has root access. |
| The file manager shows an empty or locked folder | Give the file manager root access first. The folder is in `/data/local` and is not readable otherwise. |
| The repacked image does not boot | Check that the bootloader is unlocked, see the [AVB footer](edit-gsi.md#avb-footer), and that you did not remove or break files the system needs. Reinstall from your backup of the original image. |

## Reporting a bug

If the app crashes, the crash screen shows a log. Tap **Copy log** and paste it into your report, together with the device model, Android version, operation mode and the GSI you used.
