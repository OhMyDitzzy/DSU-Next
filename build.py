#!/usr/bin/env python3
"""Build helper for DSU Next: builds libyuki (Rust), builds the APK and can
install it on a connected device.

Release builds are signed with the key from release.properties (see
release.properties.example) or from the DSU_KEYSTORE_* environment variables.
Without a key they are signed with the debug key.

Examples:
    python3 build.py                                  debug, every ABI, one universal APK
    python3 build.py --release -a arm64,armeabi       release, arm64-v8a + armeabi-v7a in one APK
    python3 build.py --release -a arm64 -a x86_64 --apk-type split
    python3 build.py --install --launch               build for the connected device, install, start
    python3 build.py --skip-native                    reuse the libyuki already in jniLibs
    python3 build.py --doctor                         only check the environment
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import importlib.util
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APP_DIR = ROOT / "app"
APP_GRADLE = APP_DIR / "build.gradle"
YUKI_DIR = ROOT / "yuki"
YUKI_BUILD = YUKI_DIR / "build.py"
JNI_LIBS = YUKI_DIR / "src" / "main" / "jniLibs"
LIB_NAME = "libyuki.so"
APK_PREFIX = "DSU-Next"
MIN_JAVA = 17

# Same ABIs as yuki/build.py.
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
ABI_ALIASES = {
    "arm64": "arm64-v8a",
    "aarch64": "arm64-v8a",
    "armeabi": "armeabi-v7a",
    "armeabi-v7": "armeabi-v7a",
    "armv7": "armeabi-v7a",
    "armv7a": "armeabi-v7a",
    "arm": "armeabi-v7a",
    "v7a": "armeabi-v7a",
    "x64": "x86_64",
    "x86-64": "x86_64",
    "amd64": "x86_64",
    "i386": "x86",
    "i686": "x86",
}
APK_TYPES = ("universal", "split", "both")

IS_WINDOWS = platform.system() == "Windows"
IN_CI = bool(os.environ.get("GITHUB_ACTIONS"))
DRY_RUN = False

def info(message: str) -> None:
    print(f"==> {message}", flush=True)


def warn(message: str) -> None:
    if IN_CI:
        print(f"::warning::{message}", flush=True)
    else:
        print(f"warning: {message}", file=sys.stderr, flush=True)


def die(message: str) -> None:
    if IN_CI:
        print(f"::error::{message}", flush=True)
    else:
        print(f"error: {message}", file=sys.stderr, flush=True)
    sys.exit(1)


@contextlib.contextmanager
def group(title: str):
    """A collapsible log group on GitHub Actions, a plain heading elsewhere."""
    if IN_CI:
        print(f"::group::{title}", flush=True)
    else:
        info(title)
    try:
        yield
    finally:
        if IN_CI:
            print("::endgroup::", flush=True)


def fmt_cmd(cmd) -> str:
    parts = [str(part) for part in cmd]
    return subprocess.list2cmdline(parts) if IS_WINDOWS else shlex.join(parts)


def run(cmd, *, env=None, cwd=None, capture=False, check=True):
    print(f"$ {fmt_cmd(cmd)}", flush=True)
    if DRY_RUN:
        return subprocess.CompletedProcess(cmd, 0, "", "")
    try:
        result = subprocess.run(
            [str(part) for part in cmd], env=env, cwd=cwd, text=True, capture_output=capture
        )
    except FileNotFoundError:
        die(f"command not found: {cmd[0]}")
    if check and result.returncode != 0:
        die(f"command failed with exit code {result.returncode}: {fmt_cmd(cmd)}")
    return result

def read_properties(path: Path) -> dict:
    props = {}
    if not path.is_file():
        return props
    for line in path.read_text(errors="ignore").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        props[key.strip()] = re.sub(r"\\(.)", r"\1", value.strip())
    return props


def gradle_value(pattern: str, default: str) -> str:
    try:
        match = re.search(pattern, APP_GRADLE.read_text())
    except OSError:
        return default
    return match.group(1) if match else default


def app_package() -> str:
    return gradle_value(r"applicationId\s+['\"]([^'\"]+)['\"]", "com.ditzzy.dsunext")


def release_key_configured() -> bool:
    """Mirrors the lookup in app/build.gradle."""
    store = os.environ.get("DSU_KEYSTORE_FILE") or read_properties(ROOT / "release.properties").get("storeFile")
    return bool(store) and (ROOT / store).is_file()

def find_sdk(explicit):
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            candidates.append(Path(os.environ[var]))
    sdk_dir = read_properties(ROOT / "local.properties").get("sdk.dir")
    if sdk_dir:
        candidates.append(Path(sdk_dir))
    home = Path.home()
    candidates += [home / "Android" / "Sdk", home / "Library" / "Android" / "sdk"]
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk")
    for candidate in candidates:
        if candidate.is_dir():
            return candidate
    return None


def find_java():
    home = os.environ.get("JAVA_HOME")
    if home:
        exe = Path(home) / "bin" / ("java.exe" if IS_WINDOWS else "java")
        if exe.is_file():
            return str(exe)
    return shutil.which("java")


def java_major(java):
    try:
        result = subprocess.run([java, "-version"], capture_output=True, text=True)
    except OSError:
        return None
    match = re.search(r'version "(\d+)(?:\.(\d+))?', result.stdout + result.stderr)
    if not match:
        return None
    major = int(match.group(1))
    return int(match.group(2)) if major == 1 and match.group(2) else major  # "1.8.0" -> 8


def find_adb(sdk):
    found = shutil.which("adb")
    if found:
        return Path(found)
    if sdk:
        exe = sdk / "platform-tools" / ("adb.exe" if IS_WINDOWS else "adb")
        if exe.is_file():
            return exe
    return None


def load_yuki_build():
    """yuki/build.py already knows how to find the NDK, so reuse it."""
    try:
        spec = importlib.util.spec_from_file_location("yuki_build", YUKI_BUILD)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    except Exception:  # noqa: BLE001 - only used for reporting
        return None


def check_environment(args, sdk):
    """Returns (label, ok, detail, required) rows."""
    rows = []
    rows.append(("Python", True, platform.python_version(), True))

    java = find_java()
    major = java_major(java) if java else None
    if major is None:
        rows.append(("JDK", False, f"not found, JDK {MIN_JAVA}+ is required (set JAVA_HOME)", True))
    else:
        rows.append(("JDK", major >= MIN_JAVA, f"{major} ({java})" if major >= MIN_JAVA
                     else f"{major} is too old, JDK {MIN_JAVA}+ is required", True))

    if sdk is None:
        rows.append(("Android SDK", False, "not found (set ANDROID_HOME, sdk.dir in local.properties, or --sdk)", True))
    else:
        rows.append(("Android SDK", True, str(sdk), True))
        compile_sdk = gradle_value(r"compileSdk\s+(\d+)", "36")
        tools = gradle_value(r"buildToolsVersion\s+['\"]([^'\"]+)['\"]", "")
        wanted = [(f"platform android-{compile_sdk}", sdk / "platforms" / f"android-{compile_sdk}")]
        if tools:
            wanted.append((f"build-tools {tools}", sdk / "build-tools" / tools))
        for label, path in wanted:
            rows.append((label, path.is_dir(),
                         "installed" if path.is_dir() else "missing, Gradle downloads it if the licenses are accepted",
                         False))

    needs_native = not args.skip_native
    cargo = shutil.which("cargo")
    rows.append(("Rust (cargo)", bool(cargo), cargo or "not found, install it from https://rustup.rs", needs_native))
    rustup = shutil.which("rustup")
    rows.append(("rustup", bool(rustup), rustup or "not found, the Rust Android targets must be installed by hand", False))

    yuki = load_yuki_build()
    ndk = yuki.find_ndk(args.ndk) if yuki else None
    rows.append(("Android NDK", ndk is not None,
                 str(ndk) if ndk else "not found (SDK Manager, then ANDROID_NDK_HOME or --ndk)", needs_native))

    adb = find_adb(sdk)
    rows.append(("adb", bool(adb), str(adb) if adb else "not found (only needed for --install)", bool(args.install)))

    if args.type == "release":
        rows.append(("Release signing", release_key_configured(),
                     "key found" if release_key_configured()
                     else "no key, the release APK is signed with the debug key (see release.properties.example)",
                     False))
    return rows


def print_environment(rows) -> bool:
    ok_all = True
    for label, ok, detail, required in rows:
        mark = "ok     " if ok else ("MISSING" if required else "warn   ")
        print(f"  [{mark}] {label}: {detail}")
        ok_all = ok_all and (ok or not required)
    return ok_all

def device_abis(adb, serial):
    if DRY_RUN:
        return ["arm64-v8a"]
    out = run([adb, "-s", serial, "shell", "getprop", "ro.product.cpu.abilist"], capture=True).stdout.strip()
    if not out:
        out = run([adb, "-s", serial, "shell", "getprop", "ro.product.cpu.abi"], capture=True).stdout.strip()
    return [abi.strip() for abi in out.split(",") if abi.strip()]


def connected_devices(adb):
    out = run([adb, "devices"], capture=True).stdout
    return [parts[0] for parts in (line.split() for line in out.splitlines()[1:])
            if len(parts) >= 2 and parts[1] == "device"]


def pick_device(adb, wanted):
    if DRY_RUN:
        return wanted or "DEVICE"
    devices = connected_devices(adb)
    if wanted:
        if wanted not in devices:
            die(f"device '{wanted}' is not connected (connected: {', '.join(devices) or 'none'})")
        return wanted
    if not devices:
        die("no device connected (check `adb devices`, USB debugging and the authorization prompt)")
    if len(devices) > 1:
        die("more than one device connected, choose one with --device: " + ", ".join(devices))
    return devices[0]


def resolve_abis(values, device_primary_abi):
    tokens = [t.strip().lower() for value in values for t in value.split(",") if t.strip()]
    selected = []
    for token in tokens:
        if token == "all":
            return list(ABIS)
        if token == "device":
            abi = device_primary_abi()
        else:
            abi = ABI_ALIASES.get(token, token)
        if abi not in ABIS:
            die(f"unsupported ABI '{token}' (choose from: {', '.join(ABIS)}, all, device)")
        if abi not in selected:
            selected.append(abi)
    return selected

def prune_unselected(abis):
    """Keep jniLibs in line with the selection, so no stale ABI ends up in the APK."""
    for abi in ABIS:
        if abi in abis:
            continue
        lib = JNI_LIBS / abi / LIB_NAME
        if lib.is_file():
            info(f"Removing {abi}/{LIB_NAME} (not selected)")
            if not DRY_RUN:
                lib.unlink()
        if not DRY_RUN:
            with contextlib.suppress(OSError):
                (JNI_LIBS / abi).rmdir()


def build_native(args, abis):
    command = [sys.executable, YUKI_BUILD, "--abi", ",".join(abis)]
    if args.native_debug:
        command.append("--debug")
    if args.ndk:
        command += ["--ndk", args.ndk]
    if args.clean:
        command.append("--clean")
    run(command, cwd=ROOT)


def gradle_command(args, abis, apk_type):
    wrapper = ROOT / ("gradlew.bat" if IS_WINDOWS else "gradlew")
    if not wrapper.is_file():
        die(f"Gradle wrapper not found: {wrapper}")
    if not IS_WINDOWS and not os.access(wrapper, os.X_OK):
        with contextlib.suppress(OSError):
            wrapper.chmod(wrapper.stat().st_mode | 0o111)  # zip/git checkouts can lose the exec bit
    split = apk_type in ("split", "both")
    command = [wrapper]
    if args.clean:
        command.append("clean")
    command.append(f":app:assemble{args.type.capitalize()}")
    command += [
        f"-Pdsu.abis={','.join(abis)}",
        f"-Pdsu.split={str(split).lower()}",
        f"-Pdsu.universal={str(apk_type == 'both').lower()}",
    ]
    if args.offline:
        command.append("--offline")
    if args.no_daemon:
        command.append("--no-daemon")
    if args.stacktrace:
        command.append("--stacktrace")
    command += args.gradle_arg or []
    return command


def collect_apks(build_type, abis):
    """Returns [(path, label, version)] from the Gradle output folder."""
    apk_dir = APP_DIR / "build" / "outputs" / "apk" / build_type
    plain_label = abis[0] if len(abis) == 1 else "universal"
    found = []

    meta = apk_dir / "output-metadata.json"
    if meta.is_file():
        with contextlib.suppress(ValueError, OSError):
            for element in json.loads(meta.read_text()).get("elements", []):
                path = apk_dir / element.get("outputFile", "")
                if not path.is_file():
                    continue
                filters = [f.get("value") for f in element.get("filters", []) if f.get("filterType") == "ABI"]
                if filters:
                    label = filters[0]
                elif element.get("type") == "UNIVERSAL" or "universal" in path.name:
                    label = "universal"
                else:
                    label = plain_label
                found.append((path, label, element.get("versionName") or "dev"))

    if not found:  # no metadata: fall back to the file names
        for path in sorted(apk_dir.glob("*.apk")):
            match = re.match(rf"app-(?:(.+)-)?{build_type}(?:-unsigned)?\.apk$", path.name)
            label = match.group(1) if match and match.group(1) else plain_label
            found.append((path, label, "dev"))
    return found


def export_apks(apks, build_type, out_dir):
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob(f"{APK_PREFIX}-*.apk"):
        old.unlink()
    exported = []
    for path, label, version in apks:
        if path.name.endswith("-unsigned.apk"):
            warn(f"{path.name} is not signed and cannot be installed")
        dest = out_dir / f"{APK_PREFIX}-{version}-{build_type}-{label}.apk"
        shutil.copy2(path, dest)
        exported.append((dest, label))
    lines = [f"{hashlib.sha256(dest.read_bytes()).hexdigest()}  {dest.name}" for dest, _ in exported]
    (out_dir / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n")
    return exported


def install_apk(adb, serial, exported, abis_on_device, launch):
    by_label = {label: dest for dest, label in exported}
    choice = next((by_label[abi] for abi in abis_on_device if abi in by_label), None) or by_label.get("universal")
    if choice is None:
        built = ", ".join(label for _, label in exported)
        die(f"no built APK fits this device (device ABIs: {', '.join(abis_on_device)}; built: {built})")
    info(f"Installing {choice.name} on {serial}")
    result = run([adb, "-s", serial, "install", "-r", choice], check=False)
    if result.returncode != 0:
        die("adb install failed. If the message says INSTALL_FAILED_UPDATE_INCOMPATIBLE, the installed app is "
            f"signed with another key: uninstall it first (adb -s {serial} uninstall {app_package()}).")
    if launch:
        info("Starting the app")
        run([adb, "-s", serial, "shell", "monkey", "-p", app_package(),
             "-c", "android.intent.category.LAUNCHER", "1"], capture=True)

def parse_args():
    parser = argparse.ArgumentParser(
        description="Build DSU Next: libyuki (Rust) + APK, optionally install it on a device.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Examples:" + __doc__.split("Examples:", 1)[1],
    )
    parser.add_argument("-t", "--type", choices=("debug", "release"), default="debug",
                        help="build type (default: debug)")
    parser.add_argument("--debug", dest="type", action="store_const", const="debug", help="same as --type debug")
    parser.add_argument("--release", dest="type", action="store_const", const="release", help="same as --type release")
    parser.add_argument("-a", "--abi", action="append", metavar="ABI",
                        help=f"ABI to build, repeat or comma-separate. One of: {', '.join(ABIS)}, plus the shortcuts "
                             "all, device (primary ABI of the connected phone), arm64, armeabi, x64. "
                             "Default: device with --install/--launch, otherwise all")
    parser.add_argument("--apk-type", choices=APK_TYPES, default="universal",
                        help="universal: one APK with every selected ABI; split: one APK per ABI; "
                             "both: the two (default: universal)")
    parser.add_argument("-o", "--out", default="dist", help="folder for the finished APKs, relative to the project (default: dist)")

    parser.add_argument("-i", "--install", action="store_true", help="install the APK on a device with adb after the build")
    parser.add_argument("-s", "--device", metavar="SERIAL", help="adb serial to use with --install")
    parser.add_argument("--launch", action="store_true", help="start the app after installing (implies --install)")

    native = parser.add_mutually_exclusive_group()
    native.add_argument("--skip-native", action="store_true", help="do not build libyuki, use what is in yuki/src/main/jniLibs")
    native.add_argument("--native-only", action="store_true", help="only build libyuki, no APK")
    parser.add_argument("--native-debug", action="store_true",
                        help="build libyuki in debug mode too (slow, big; libyuki is always release otherwise)")

    parser.add_argument("--ndk", help="path to the Android NDK")
    parser.add_argument("--sdk", help="path to the Android SDK")
    parser.add_argument("--clean", action="store_true", help="clean Gradle and libyuki outputs before building")
    parser.add_argument("--doctor", action="store_true", help="check the environment and exit")
    parser.add_argument("--offline", action="store_true", help="pass --offline to Gradle")
    parser.add_argument("--no-daemon", action="store_true", help="pass --no-daemon to Gradle")
    parser.add_argument("--stacktrace", action="store_true", help="pass --stacktrace to Gradle")
    parser.add_argument("--gradle-arg", action="append", metavar="ARG", help="extra Gradle argument, repeatable")
    parser.add_argument("--dry-run", action="store_true", help="print the commands without running them")

    args = parser.parse_args()
    if args.launch:
        args.install = True
    return args


def main():
    global DRY_RUN
    args = parse_args()
    DRY_RUN = args.dry_run

    sdk = find_sdk(args.sdk)
    adb = find_adb(sdk)

    # Resolve the target device first: --abi device and --install both need it.
    serial = None
    device_abi_list = []
    if args.install and not args.doctor:
        if not adb and not DRY_RUN:
            die("adb not found (install platform-tools, or add it to PATH)")
        serial = pick_device(adb, args.device)
        device_abi_list = device_abis(adb, serial)

    def device_primary_abi():
        if not device_abi_list:
            if not adb and not DRY_RUN:
                die("--abi device needs adb to read the device ABI")
            serial_for_abi = serial or pick_device(adb, args.device)
            device_abi_list.extend(device_abis(adb, serial_for_abi))
        supported = [abi for abi in device_abi_list if abi in ABIS]
        if not supported:
            die(f"the device ABIs ({', '.join(device_abi_list)}) are not supported by libyuki")
        return supported[0]

    default_tokens = ["device"] if args.install else ["all"]
    abis = resolve_abis(args.abi or default_tokens, device_primary_abi) if not args.doctor \
        else resolve_abis(args.abi or ["all"], lambda: "arm64-v8a")

    apk_type = args.apk_type
    if len(abis) == 1 and apk_type == "both":
        apk_type = "split"  # with a single ABI the universal APK would be a duplicate

    with group("Environment"):
        rows = check_environment(args, sdk)
        env_ok = print_environment(rows)
    if args.doctor:
        sys.exit(0 if env_ok else 1)
    if not env_ok:
        if DRY_RUN:
            warn("environment is incomplete (ignored because of --dry-run)")
        else:
            die("the environment is incomplete, see above (run with --doctor for the full report)")
    if args.type == "release" and not release_key_configured():
        warn("no release key found: the release APK will be signed with the debug key. "
             "Set it up with release.properties (see release.properties.example) or the DSU_KEYSTORE_* variables.")

    info(f"{args.type} build, ABIs: {', '.join(abis)}, APK type: {apk_type}")

    prune_unselected(abis)

    if not args.skip_native:
        with group(f"libyuki ({', '.join(abis)})"):
            build_native(args, abis)
    elif not DRY_RUN:
        missing = [abi for abi in abis if not (JNI_LIBS / abi / LIB_NAME).is_file()]
        if missing:
            die(f"--skip-native, but {LIB_NAME} is missing for: {', '.join(missing)}. Build it first (without --skip-native).")

    if args.native_only:
        info("Native build done (--native-only)")
        return

    env = dict(os.environ)
    if sdk and (args.sdk or not (env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT"))):
        env["ANDROID_HOME"] = env["ANDROID_SDK_ROOT"] = str(sdk)

    with group("Gradle"):
        run(gradle_command(args, abis, apk_type), cwd=ROOT, env=env)

    if DRY_RUN:
        info("Dry run: nothing was built")
        return

    apks = collect_apks(args.type, abis)
    if not apks:
        die(f"Gradle finished but no APK was found in {APP_DIR / 'build' / 'outputs' / 'apk' / args.type}")
    out_dir = (ROOT / args.out).resolve()
    exported = export_apks(apks, args.type, out_dir)

    info(f"Done: {len(exported)} APK(s) in {out_dir}")
    for dest, label in exported:
        print(f"    {dest.name}  ({dest.stat().st_size / 1024 / 1024:.1f} MiB, {label})")

    if args.install:
        install_apk(adb, serial, exported, device_abi_list, args.launch)


if __name__ == "__main__":
    main()
