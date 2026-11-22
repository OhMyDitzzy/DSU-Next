# Usage

This page walks through DSU Next from the first launch to a booted DSU. For how the app reaches the system. For the GSI editor, see [Edit GSI](edit-gsi.md).

## Installing a GSI

1. Tap **Select file** and choose one of:

   | Format | What happens |
   | --- | --- |
   | `.img` | Compressed to `.img.gz` first, which is what the DSU app reads. |
   | `.gz` | Used as is. |
   | `.xz` | Decompressed, then compressed to `.img.gz`. |
   | `.zip` | Treated as a DSU package and passed on untouched. |

   With the built-in installer (root only) no recompression is needed: an `.img` is installed directly, a `.gz` or `.xz` is extracted to a raw image first, and a `.zip` package is passed on as is.

2. Set the **userdata size** in gigabytes. The card shows the maximum that can be allocated.
3. Optionally set the **image size** in bytes. DSU Next works this out for you, so setting it by hand is strongly inadvisable and the app asks you to confirm.
4. Tap **Install** and review the parameters in the confirmation dialog.
5. If a DSU is already installed, the app offers to discard it. Everything stored in that DSU is deleted when you do. With [Multi-GSI](multi-gsi.md) on (root only) there is nothing to discard: the new GSI is installed next to the others.

DSU cannot be installed while a DSU is running. Reboot into your normal system first.

## Multi-GSI

**Tools > Multi-GSI** lists the GSIs you installed with Multi-GSI and lets you switch to one, rename it or remove it. It needs root and is covered in [Multi-GSI](multi-gsi.md).

## Treble check

**Tools > Treble check** reports whether the device ships Project Treble and shows:

- Treble type: standard, or legacy with the vendor manifest in `/vendor`.
- VNDK version and whether VNDK Lite is used.
- CPU architecture: ARM 32-bit, ARM 32-bit with 64-bit binder, ARM 64-bit, x86 or x86-64.
- Partition scheme: Virtual A/B, A/B or A-only.
- System-as-root.

Use it to choose a GSI that matches the device. DSU Next does not check that the image you select is compatible.

## Edit your GSI

**Tools > Edit your GSI** opens the GSI editor. It works without root, keeps workspaces in private app storage, and is covered in [Edit GSI](edit-gsi.md).

## Appearance and language

**Settings > Appearance** has the theme (dark, light or follow the system), dynamic color, a color palette and the language.

**Language** opens a dialog with **Follow system** (the default), **English** and **Bahasa Indonesia**. With **Follow system** the app uses your device language when it is translated and English otherwise. On Android 13 and newer the same choice is also available in the system settings under **App languages**. To add a language, see [Translating](../CONTRIBUTING.md#translating).
