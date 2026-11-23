# Contributing to DSU Next

Thanks for wanting to help. DSU Next is a small project and every kind of contribution is welcome: bug reports, documentation, translations and code.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

> [!WARNING]
> DSU Next changes how a device boots and how its storage is allocated. Test on a device you can restore, and say clearly in your pull request what you tested and on what.

## Ways to contribute

### Report a bug

Open an issue with the **Bug report** form. The more of this you include, the faster it gets fixed:

- Device model, Android version and the operation mode (root, Shizuku or adb), which is shown in **Settings > Other**.
- What you did, what you expected and what happened.
- The installation log (**View logs**, then **Save logs**) or, after a crash, the log from the crash screen.
- The kind of image you used (`.img`, `.gz`, `.xz`, `.zip`) and where it came from.

Check the [troubleshooting guide](docs/troubleshooting.md) first, many installation errors are explained there.

### Suggest a feature

Use the **Feature request** form. Describe the problem you want solved before the solution you have in mind. For anything large, please open an issue and agree on the approach before you write the code.

### Improve the documentation

The guides live in [`docs/`](docs). Fixes to wording, missing steps and new troubleshooting entries are always welcome. Documentation changes do not trigger a build.

### Translate the app

See [Translating](#translating).

### Send code

Small, focused pull requests are easiest to review. If a change touches several unrelated things, split it.

## Project layout

| Module | Language | What it holds |
| --- | --- | --- |
| `app` | Kotlin | The application: screens, installer, privileged service, Multi-GSI, Edit GSI. |
| `file-manager` | Kotlin | File operations inside a GSI workspace (copy, move, permissions, symlinks). |
| `treble` | Kotlin | The Treble checker. |
| `yuki` | Kotlin + Rust | JNI bridge and the Rust core that reads and writes ext4 and EROFS images. |
| `hidden-api-stub` | Java | Compile-only stubs for the hidden Android APIs that the app calls. |
| `docs` | Markdown | User documentation. |

## Building

You need:

- JDK 17
- Android SDK with platform 36 and build tools 35.0.1
- The Android NDK (the CI uses r27c, `27.2.12479018`) and a Rust toolchain, to build `libyuki`
- Python 3, for `build.py`

`build.py` builds the native library and the APK in one go:

```
python3 build.py --doctor            # check the environment first
python3 build.py                     # debug build, every ABI, one universal APK
python3 build.py -a arm64 --install --launch
python3 build.py --skip-native       # reuse the libyuki that is already built
```

The APKs end up in `dist/`. Run `python3 build.py --help` for every option. Release builds are signed with the key described in `release.properties.example`; without one they use the debug key.

Unit tests run without a device:

```
./gradlew testDebugUnitTest
```

The tests under `androidTest` need a device or an emulator.

## Code style

[`.editorconfig`](.editorconfig) holds the basics: UTF-8, LF line endings, four spaces (two for YAML and shell scripts) and a final newline. Android Studio and most editors read it on their own.

Beyond that, follow what the surrounding code does:

- Application and library code use Kotlin. The compile-only Android framework stubs stay in Java to preserve their platform signatures.
- **No text in code or layouts.** Every string the user can see goes into `res/values/` and is translated (see below). Use numbered placeholders such as `%1$s` so translators can reorder words.
- The interface is Material 3 Expressive. Use theme attributes (`?attr/colorSurfaceContainerHigh`, `?attr/textAppearanceTitleMedium`) instead of fixed colors, and check both light and dark themes with dynamic color on and off. Settings screens are built from `ListRow` and `SegmentedGroup`.
- Never block the main thread with I/O or shell commands.
- Comments explain *why*, not what the line already says.
- Keep the user's data safe: a destructive action needs a confirmation, and an error needs a message that says what to do next.

## Translating

Translations are Android string resources. Thank you for every language you add.

### Fix or complete an existing translation

Edit `app/src/main/res/values-<code>/strings.xml` and `file_manager_strings.xml`. The default language is English in `res/values/`.

### Add a new language

1. Copy `res/values/strings.xml` and `res/values/file_manager_strings.xml` into `res/values-<code>/` (for example `values-fr`). Use the [Android resource qualifier](https://developer.android.com/guide/topics/resources/providing-resources#QualifierRules) for the code, which is `in` for Indonesian and `b+sr+Latn` for Serbian in Latin script.
2. Translate every string. Leave `translatable="false"` entries out of the new files.
3. Keep the placeholders (`%1$s`, `%2$d`, `%%`), the `\n` line breaks and the escapes (`\'`, `\"`, `&amp;`) exactly as they are. The order of the words can change, the numbered placeholders keep that safe.
4. Give each `<plurals>` the quantity forms your language needs. See the [CLDR plural rules](https://cldr.unicode.org/index/cldr-spec/plural-rules).
5. Add the language to the picker:
   - Add `<locale android:name="<tag>" />` to `res/xml/locales_config.xml`, using the BCP 47 tag (`fr`, `id`, `pt-BR`).
   - Add a constant to `model/AppLanguage.kt` with a **new, never reused id**. The id is stored in the user's preferences.
   - Add `language_<name>` (the language written in itself, `translatable="false"`) and `language_<name>_name` (the language written in each interface language) to `res/values/strings.xml`, and `language_<name>_name` to every translation.
6. Run `./gradlew testDebugUnitTest` and open **Settings > Appearance > Language** on a device to check the result.

Some terms stay in English on purpose because they are the names of things: GSI, DSU, Treble, VNDK, userdata, logcat, root, bootloader, slot, SELinux. Translate the sentences around them. Use the same word for the same thing everywhere, and the same words as the Android system in your language for Settings, Theme and so on.

Android lint reports an incomplete translation as an error, which can fail a release build, so a new language needs every string before it is merged.

## Pull requests

1. Fork the repository and branch from `main`. A short descriptive branch name helps (`fix-sd-unmount`, `translate-french`).
2. Make the change, with tests where the logic can be tested without a device.
3. Update the documentation if behavior or settings changed.
4. Write commit messages that say what changed and why, in the imperative: `Fix crash when the workspace name is empty`.
5. Open the pull request and fill in:
   - what it does and why,
   - how you tested it: device, Android version, operation mode,
   - screenshots for any visible change, in light and dark.
6. CI builds the APK for every pull request. A failing build needs fixing before review.

Reviews are about the code, never about the person. Expect questions and requests for changes, they are part of the process.

## Areas that need extra care

- **Installer, privileged service and operation modes** (`installer/`, `service/`): a mistake can leave a device unable to boot. Explain the failure modes you considered.
- **Multi-GSI slots** (`SlotRegistry`, `GsiSlotController`): they decide which GSI boots and what gets deleted.
- **Edit GSI and `yuki`**: they rebuild whole images, so keep ownership, permissions and SELinux labels intact.
- **The User Agreement text**: if a change needs people to accept it again, raise `CURRENT_USER_AGREEMENT_VERSION` in `AppPrefs`.

## Licensing

DSU Next is licensed under the [Apache License 2.0](LICENSE). By sending a contribution you agree that it is licensed under the same terms.

Only submit code you wrote or code whose license allows this. If you bring in code from another project, keep its copyright notice and add it to [NOTICE](NOTICE). DSU Next is a derivative of [DSU Sideloader](https://github.com/VegaBobo/DSU-Sideloader), so changes to files that come from it must keep their original notices.

## Questions

Open an issue if something here is unclear or out of date. A pull request that fixes this file is welcome too.
