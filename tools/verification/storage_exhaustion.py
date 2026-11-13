#!/usr/bin/env python3
"""Explicit-only kernel space/quota fault on a dedicated new synthetic AOSP AVD."""
import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

PACKAGE = "io.github.jnesew.paudiobooks"
AVD = "paudio_codex_fullspace26"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd-dir", type=Path, required=True, help="Host directory for this dedicated AVD")
    parser.add_argument("--output", type=Path, required=True)
    options = parser.parse_args()
    options.output.mkdir(parents=True, exist_ok=False)
    adb = ["adb", "-s", options.serial]

    def run(*args):
        result = subprocess.run(adb + list(args), capture_output=True, text=True, timeout=20)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout.strip()

    if run("emu", "avd", "name").splitlines()[0] != AVD:
        raise RuntimeError("Requires the dedicated new storage-fault AVD")
    if options.avd_dir.name != AVD + ".avd" or not (options.avd_dir / "config.ini").is_file():
        raise RuntimeError("Host AVD directory does not match")
    if shutil.disk_usage(options.avd_dir).free < 20 * 1024**3:
        raise RuntimeError("Insufficient host margin for the bounded 11 GiB trial")
    if run("shell", "getprop", "ro.build.version.sdk") != "26":
        raise RuntimeError("Unexpected API level")
    if run("shell", "pm", "list", "packages", "com.google.android.gms"):
        raise RuntimeError("Requires GMS-free AOSP")
    if run("shell", "settings", "get", "global", "airplane_mode_on") != "1" or run("shell", "settings", "get", "global", "wifi_on") != "0":
        raise RuntimeError("Establish actual offline state first")
    identity = {"avd": AVD, "serial": options.serial,
        "fingerprint": run("shell", "getprop", "ro.build.fingerprint"),
        "filesystemBefore": run("shell", "df", "-k", "/data"), "apks": {}}
    for package, apk in [(PACKAGE, Path("app/build/outputs/apk/debug/app-debug.apk")),
                         (PACKAGE + ".test", Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))]:
        installed = run("shell", "pm", "path", package).removeprefix("package:")
        if not installed.startswith("/data/app/") or "\n" in installed:
            raise RuntimeError("Unexpected installed APK path")
        digest = run("shell", "sha256sum", installed).split()[0]
        if digest != hashlib.sha256(apk.read_bytes()).hexdigest():
            raise RuntimeError("Install the matching current APK pair first")
        identity["apks"][package] = digest
    (options.output / "identity.json").write_text(json.dumps(identity, indent=2) + "\n")
    result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "fullFilesystem", "true",
        "-e", "ownedAvd", AVD, "-e", "class", PACKAGE + ".importing.FilesystemExhaustionAcceptanceTest",
        PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"], capture_output=True, text=True, timeout=120)
    output = result.stdout + result.stderr
    (options.output / "instrument.log").write_text(output)
    (options.output / "filesystemAfter.txt").write_text(run("shell", "df", "-k", "/data") + "\n")
    if result.returncode or "OK (1 test)" not in output or "FAILURES" in output or not any(
        marker in output for marker in ("kernelDestinationEnospc=true", "kernelDestinationEdquot=true")
    ):
        raise RuntimeError("Kernel-fault/preservation/retry acceptance failed; inspect instrument.log")
    print(output)


if __name__ == "__main__":
    main()
