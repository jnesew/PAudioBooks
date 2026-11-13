#!/usr/bin/env python3
"""Observe real paused-service expiry without reconnecting a UI/controller."""
import argparse
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path

PACKAGE = "io.github.jnesew.paudiobooks"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-mode", choices=["private", "linked"], default="private")
    options = parser.parse_args()
    options.output.mkdir(parents=True, exist_ok=False)
    adb = ["adb", "-s", options.serial]

    def run(*args):
        result = subprocess.run(adb + list(args), capture_output=True, text=True, timeout=20)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    if run("emu", "avd", "name").splitlines()[0] != options.avd:
        raise RuntimeError("Unexpected AVD identity")
    if run("shell", "settings", "get", "global", "airplane_mode_on").strip() != "1" or run("shell", "settings", "get", "global", "wifi_on").strip() != "0":
        raise RuntimeError("Dedicated AVD must be offline")
    if run("shell", "pm", "list", "packages", "com.google.android.gms").strip():
        raise RuntimeError("A GMS-free AOSP AVD is required")
    identity = {"avd": options.avd, "serial": options.serial, "sourceMode": options.source_mode}
    for package, apk in [(PACKAGE, Path("app/build/outputs/apk/debug/app-debug.apk")),
                         (PACKAGE + ".test", Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))]:
        path = run("shell", "pm", "path", package).strip().removeprefix("package:")
        if not path.startswith("/data/app/") or "\n" in path:
            raise RuntimeError("Unexpected installed package path")
        actual = run("shell", "sha256sum", path).split()[0]
        if actual != hashlib.sha256(apk.read_bytes()).hexdigest():
            raise RuntimeError("Installed APK differs from local build")
        identity[package + ".sha256"] = actual
    (options.output / "identity.json").write_text(json.dumps(identity, indent=2) + "\n")
    with (options.output / "instrument.log").open("w") as log:
        runner = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "hostDriven", "true", "-e", "sourceMode", options.source_mode,
            "-e", "class", PACKAGE + ".playback.ProcessDeathAcceptanceTest#pausedIdleWithoutController",
            PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"], stdout=log, stderr=subprocess.STDOUT)
        try:
            start = time.monotonic()
            while "PAUSED_WITHOUT_CONTROLLER" not in (options.output / "instrument.log").read_text():
                if runner.poll() is not None or time.monotonic() - start >= 30:
                    raise RuntimeError("No live paused baseline; inspect instrument.log")
                time.sleep(0.1)
            if "INSTRUMENTATION_STATUS: sourceMode=" + options.source_mode not in (options.output / "instrument.log").read_text():
                raise RuntimeError("Baseline source mode does not match the requested trial")
            baseline = time.monotonic()

            def state(label):
                services = run("shell", "dumpsys", "activity", "services", PACKAGE)
                sessions = run("shell", "dumpsys", "media_session")
                (options.output / (label + "-services.txt")).write_text(services)
                (options.output / (label + "-sessions.txt")).write_text(sessions)
                if "ACTIVITY MANAGER SERVICES" not in services or "Sessions Stack" not in sessions:
                    raise RuntimeError("Invalid system observation")
                return {"elapsedHostSeconds": time.monotonic() - baseline,
                    "runnerStillRunning": runner.poll() is None,
                    "service": bool(re.search(r"ServiceRecord\{[^\n]*" + re.escape(PACKAGE) + r"/\.playback\.PlaybackService", services)),
                    "session": ("package=" + PACKAGE + "\n") in sessions}

            records = [state("before")]
            if not records[0]["service"] or not records[0]["session"]:
                raise RuntimeError("Service/session missing at baseline")
            while time.monotonic() - baseline < 605:
                if runner.poll() is not None:
                    raise RuntimeError("Runner exited before timed observation")
                time.sleep(min(5, max(0, 605 - (time.monotonic() - baseline))))
            records.append(state("after605"))
            (options.output / "observations.json").write_text(json.dumps(records, indent=2) + "\n")
            after = records[-1]
            if not after["runnerStillRunning"] or after["service"] or after["session"]:
                raise RuntimeError("Expiry not observed while runner alive: " + str(after))
            runner.wait(timeout=30)
        finally:
            if runner.poll() is None:
                runner.terminate()
                runner.wait(timeout=5)
    output = (options.output / "instrument.log").read_text()
    if "OK (1 test)" not in output or "FAILURES" in output:
        raise RuntimeError(output)
    print(json.dumps(records))


if __name__ == "__main__":
    main()
