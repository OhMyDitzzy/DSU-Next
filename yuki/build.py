#!/usr/bin/env python3
"""Builds libyuki.so (Yuki + JNI bridge) for Android and copies it into
src/main/jniLibs/<abi>/, where Gradle picks it up.

Needs: Rust (rustup + cargo) and the Android NDK. The NDK is looked up in
--ndk, ANDROID_NDK_HOME / ANDROID_NDK_ROOT / ANDROID_NDK, ndk.dir and sdk.dir
in the root local.properties, ANDROID_HOME / ANDROID_SDK_ROOT and the usual
Android Studio install paths.

Examples:
    python3 build.py                      all ABIs, release
    python3 build.py --abi arm64-v8a      one ABI
    python3 build.py --debug --abi x86_64
    python3 build.py --clean
"""

import argparse
import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path

MODULE_DIR = Path(__file__).resolve().parent
ROOT_DIR = MODULE_DIR.parent
RUST_DIR = MODULE_DIR / "src" / "main" / "rust"
JNI_LIBS = MODULE_DIR / "src" / "main" / "jniLibs"
CRATE = "yuki"
LIB_NAME = "libyuki.so"

# abi -> (rust target, clang target prefix)
ABIS = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android"),
    "armeabi-v7a": ("armv7-linux-androideabi", "armv7a-linux-androideabi"),
    "x86_64": ("x86_64-linux-android", "x86_64-linux-android"),
    "x86": ("i686-linux-android", "i686-linux-android"),
}


def die(message):
    print(f"error: {message}", file=sys.stderr)
    sys.exit(1)


def info(message):
    print(f"==> {message}", flush=True)


def min_sdk():
    try:
        text = (MODULE_DIR / "build.gradle").read_text()
    except OSError:
        return 29
    match = re.search(r"minSdk\s+(\d+)", text)
    return int(match.group(1)) if match else 29


def local_properties():
    props = {}
    path = ROOT_DIR / "local.properties"
    if not path.is_file():
        return props
    for line in path.read_text(errors="ignore").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        props[key.strip()] = re.sub(r"\\(.)", r"\1", value.strip())
    return props


def version_key(path):
    return [int(part) if part.isdigit() else 0 for part in re.split(r"[.\-]", path.name)]


def find_ndk(explicit):
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    for var in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK"):
        if os.environ.get(var):
            candidates.append(Path(os.environ[var]))

    props = local_properties()
    if props.get("ndk.dir"):
        candidates.append(Path(props["ndk.dir"]))

    sdks = []
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            sdks.append(Path(os.environ[var]))
    if props.get("sdk.dir"):
        sdks.append(Path(props["sdk.dir"]))
    home = Path.home()
    sdks += [home / "Android" / "Sdk", home / "Library" / "Android" / "sdk"]
    if os.environ.get("LOCALAPPDATA"):
        sdks.append(Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk")

    for sdk in sdks:
        versions = sorted((sdk / "ndk").glob("*"), key=version_key, reverse=True)
        candidates += versions
        candidates.append(sdk / "ndk-bundle")

    for candidate in candidates:
        if (candidate / "toolchains" / "llvm" / "prebuilt").is_dir():
            return candidate
    return None


def host_tag():
    system = platform.system()
    if system == "Linux":
        return "linux-x86_64"
    if system == "Darwin":
        return "darwin-x86_64"
    if system == "Windows":
        return "windows-x86_64"
    die(f"unsupported host for the Android NDK: {system}")


def toolchain_bin(ndk):
    path = ndk / "toolchains" / "llvm" / "prebuilt" / host_tag() / "bin"
    if not path.is_dir():
        die(f"NDK toolchain not found: {path}")
    return path


def target_env(triple, clang_prefix, bin_dir, api):
    windows = platform.system() == "Windows"
    wrapper = ".cmd" if windows else ""
    clang = bin_dir / f"{clang_prefix}{api}-clang{wrapper}"
    clangxx = bin_dir / f"{clang_prefix}{api}-clang++{wrapper}"
    ar = bin_dir / ("llvm-ar.exe" if windows else "llvm-ar")
    if not clang.is_file():
        die(f"linker not found: {clang} (does this NDK support API {api}?)")
    upper = triple.upper().replace("-", "_")
    lower = triple.replace("-", "_")
    return {
        f"CARGO_TARGET_{upper}_LINKER": str(clang),
        f"CC_{lower}": str(clang),
        f"CXX_{lower}": str(clangxx),
        f"AR_{lower}": str(ar),
    }


def ensure_rust_targets(triples):
    rustup = shutil.which("rustup")
    if not rustup:
        print("warning: rustup not found, assuming the Rust targets are installed", file=sys.stderr)
        return
    result = subprocess.run(
        [rustup, "target", "list", "--installed"], capture_output=True, text=True, check=True
    )
    installed = set(result.stdout.split())
    missing = [t for t in triples if t not in installed]
    if missing:
        info(f"Installing Rust targets: {' '.join(missing)}")
        subprocess.run([rustup, "target", "add", *missing], check=True)


def parse_abis(values):
    if not values:
        return list(ABIS)
    selected = []
    for value in values:
        for abi in value.split(","):
            abi = abi.strip()
            if not abi:
                continue
            if abi == "all":
                return list(ABIS)
            if abi not in ABIS:
                die(f"unknown ABI '{abi}' (choose from: {', '.join(ABIS)})")
            if abi not in selected:
                selected.append(abi)
    return selected or list(ABIS)


def main():
    parser = argparse.ArgumentParser(
        description="Build libyuki.so for Android.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Examples:" + __doc__.split("Examples:", 1)[1],
    )
    parser.add_argument(
        "--abi", action="append", metavar="ABI",
        help=f"ABI to build, repeat or comma-separate (default: all of {', '.join(ABIS)})",
    )
    parser.add_argument("--debug", action="store_true", help="debug build instead of release")
    parser.add_argument("--api", type=int, default=min_sdk(), help="Android API level of the linker (default: minSdk)")
    parser.add_argument("--ndk", help="path to the Android NDK")
    parser.add_argument("--out", type=Path, default=JNI_LIBS, help=f"output folder (default: {JNI_LIBS})")
    parser.add_argument("--locked", action="store_true", help="pass --locked to cargo (Cargo.lock must be up to date)")
    parser.add_argument("--clean", action="store_true", help="remove the Rust target folder and built libraries first")
    args = parser.parse_args()

    abis = parse_abis(args.abi)

    if not shutil.which("cargo"):
        die("cargo not found. Install Rust: https://rustup.rs")

    ndk = find_ndk(args.ndk)
    if ndk is None:
        die("Android NDK not found. Install it from the SDK Manager and set ANDROID_NDK_HOME, or pass --ndk.")
    bin_dir = toolchain_bin(ndk)
    info(f"NDK: {ndk} (API {args.api})")

    if args.clean:
        info("Cleaning")
        shutil.rmtree(RUST_DIR / "target", ignore_errors=True)
        for abi in ABIS:
            (args.out / abi / LIB_NAME).unlink(missing_ok=True)

    ensure_rust_targets([ABIS[abi][0] for abi in abis])

    profile = "debug" if args.debug else "release"
    for abi in abis:
        triple, clang_prefix = ABIS[abi]
        info(f"Building {abi} ({triple}, {profile})")
        command = [
            "cargo", "build",
            "--manifest-path", str(RUST_DIR / "Cargo.toml"),
            "--package", CRATE,
            "--target", triple,
        ]
        if not args.debug:
            command.append("--release")
        if args.locked:
            command.append("--locked")
        env = {**os.environ, **target_env(triple, clang_prefix, bin_dir, args.api)}
        if subprocess.run(command, cwd=RUST_DIR, env=env).returncode != 0:
            die(f"cargo build failed for {abi}")

        built = RUST_DIR / "target" / triple / profile / LIB_NAME
        if not built.is_file():
            die(f"{built} was not produced")
        destination = args.out / abi / LIB_NAME
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(built, destination)
        print(f"    {destination} ({destination.stat().st_size / 1024:.0f} KiB)")

    info(f"Done: {len(abis)} ABI(s) in {args.out}")


if __name__ == "__main__":
    main()
