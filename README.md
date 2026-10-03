<div align="center">

# DSU Next

**A DSU installer and GSI editor for Android.**

Install Generic System Images through Dynamic System Updates, and unpack, edit and repack a GSI right on the device.

[![License](https://img.shields.io/github/license/OhMyDitzzy/DSU-Next?style=flat-square&color=blue)](LICENSE)
[![Stars](https://img.shields.io/github/stars/OhMyDitzzy/DSU-Next?style=flat-square&logo=github)](https://github.com/OhMyDitzzy/DSU-Next/stargazers)
[![Issues](https://img.shields.io/github/issues/OhMyDitzzy/DSU-Next?style=flat-square)](https://github.com/OhMyDitzzy/DSU-Next/issues)
[![Last commit](https://img.shields.io/github/last-commit/OhMyDitzzy/DSU-Next?style=flat-square)](https://github.com/OhMyDitzzy/DSU-Next/commits)
[![PRs welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)](https://github.com/OhMyDitzzy/DSU-Next/pulls)

</div>

> [!WARNING]
> DSU Next changes how your device boots and how its storage is allocated. It is made for developers and advanced users. You need an unlocked bootloader and a way to restore your stock firmware.

## What is DSU Next?

DSU (Dynamic System Updates) lets you boot a different Android system image next to your installed one, without touching the device partitions. DSU Next is an app that makes this easy, and it goes one step further than a plain installer: it can open a GSI, let you change its files, and build it back into an image you can install.

DSU Next is a derivative of [DSU Sideloader](https://github.com/VegaBobo/DSU-Sideloader) by VegaBobo. It keeps the installation core and adds a new interface, an on-device GSI editor, a Treble checker and a native Rust module.

## Features

- **Install a GSI as a DSU** from `.img`, `.gz`, `.xz` or a DSU package `.zip`.
- **Built-in installer** (root) that talks to the DynamicSystem API directly.
- **Installation progress and diagnostics** read from logcat, with a plain explanation and a suggested fix for the common failures.
- **Edit GSI** (root only for now): import an image or an archive, edit the unpacked files with a root file manager, repack to ext4 or EROFS, save the result.
- **Treble check**: Treble support and type, VNDK version, CPU architecture, partition scheme and system-as-root.

## Quick start

1. Install the APK and open DSU Next.
2. Read and accept the User Agreement.
3. Pick a folder for the app storage when asked (it holds temporary files).
4. Select a GSI, set the userdata size, and tap **Install**.
5. Follow the prompts for your operation mode. Without root or Shizuku the app gives you a command to run over `adb`.

## Credits

DSU Next would not exist without these two projects.

### DSU Sideloader

[**DSU Sideloader**](https://github.com/VegaBobo/DSU-Sideloader) by [**VegaBobo**](https://github.com/VegaBobo) and its contributors is the base of DSU Next. The privileged service and operation modes, the installation preparation pipeline, the ADB script generation, the root DSU installer and the logcat based diagnostics are derived from it. DSU Sideloader is licensed under the Apache License 2.0.

### Jancox

[**Jancox**](https://github.com/wahyu6070/jancox) by [**wahyu6070**](https://github.com/wahyu6070) is the key to the Edit GSI feature. Its Rust core, which reads and writes ext4 and EROFS images together with their owners, permissions and SELinux labels, is what the `:yuki` module is built on. The `sdat2img` and `img2sdat` crates that Yuki depends on are also by wahyu6070.

## Disclaimer

DSU Next is provided "as is" and "as available", without warranty of any kind. You alone are responsible for the images you choose, the options you enable and the permissions you grant. To the maximum extent permitted by law, the developer is not liable for data loss, a device that fails to boot, loss of warranty or any other harm that results from your negligence or from using this tool. The developer does not provide, verify or endorse any system image you install. The same text is shown in the app on the User Agreement page.

## License

DSU Next is licensed under the [Apache License 2.0](LICENSE). Notices for the code it is derived from are in [NOTICE](NOTICE).
