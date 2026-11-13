#!/usr/bin/env python3
"""External live-process acceptance on a dedicated, already-provisioned AOSP AVD.

Does not wipe data, export a database, use root, or launch a custom runner. Seed
instrumentation is intentionally terminated while playing; verify instrumentation
must pass in a new process. Logs contain only synthetic test values.
"""
import argparse
import hashlib
import json
import re
import selectors
import subprocess
import time
from pathlib import Path

PACKAGE = "io.github.jnesew.paudiobooks"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
CLASS = PACKAGE + ".playback.ProcessDeathAcceptanceTest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--steps", nargs="+", type=int, required=True, help="10..60 inclusive; speed = step / 20")
    parser.add_argument("--source-mode", choices=["private", "linked"], default="private")
    parser.add_argument("--mode", choices=["sigkill", "force-stop", "reboot"], required=True)
    options = parser.parse_args()
    if any(step not in range(10, 61) for step in options.steps):
        parser.error("steps must be in 10..60")
    options.output.mkdir(parents=True, exist_ok=False)
    adb = ["adb", "-s", options.serial]

    def run(*args, timeout=30, allow_failure=False):
        result = subprocess.run(adb + list(args), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        if not allow_failure and result.returncode:
            raise RuntimeError(f"adb failed ({result.returncode}): {result.stdout}")
        return result.stdout.strip()

    def pid():
        text = run("shell", "pidof", PACKAGE, allow_failure=True)
        if not text:
            return None
        if not re.fullmatch(r"[0-9]+", text):
            raise RuntimeError("Ambiguous process identity: " + text)
        return int(text)

    def boot_id():
        value = run("shell", "cat", "/proc/sys/kernel/random/boot_id")
        if not re.fullmatch(r"[0-9a-f-]{36}", value):
            raise RuntimeError("Cannot establish boot identity")
        return value

    actual_avd = run("emu", "avd", "name").splitlines()[0]
    if actual_avd != options.avd:
        raise RuntimeError(f"Expected {options.avd}, found {actual_avd}")
    if run("shell", "settings", "get", "global", "airplane_mode_on") != "1":
        raise RuntimeError("Airplane mode must already be enabled")
    if run("shell", "settings", "get", "global", "wifi_on") != "0":
        raise RuntimeError("Wi-Fi must already be disabled")
    if run("shell", "pm", "list", "packages", "com.google.android.gms"):
        raise RuntimeError("This acceptance driver requires a GMS-free AOSP AVD")
    identity = {"serial": options.serial, "avd": actual_avd, "mode": options.mode,
                "sourceMode": options.source_mode, "fingerprint": run("shell", "getprop", "ro.build.fingerprint"), "initialBootId": boot_id(),
                "airplane": 1, "wifi": 0, "gms": False}
    for package, apk in [(PACKAGE, Path("app/build/outputs/apk/debug/app-debug.apk")),
                         (PACKAGE + ".test", Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))]:
        package_path = run("shell", "pm", "path", package).removeprefix("package:")
        if not package_path.startswith("/data/app/") or "\n" in package_path:
            raise RuntimeError("Unexpected installed APK path")
        installed = run("shell", "sha256sum", package_path).split()[0]
        local = hashlib.sha256(apk.read_bytes()).hexdigest()
        if local != installed:
            raise RuntimeError("Installed APK does not match local build: " + package)
        identity[package + ".sha256"] = installed
    (options.output / "identity.json").write_text(json.dumps(identity, indent=2) + "\n")
    records = []
    for step in options.steps:
        sample = {}
        seed_command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", CLASS + "#playingUntilExternalTermination",
                              "-e", "speedStep", str(step), "-e", "sourceMode", options.source_mode, "-e", "hostDriven", "true", RUNNER]
        with (options.output / f"{step}-seed.log").open("w") as log:
            seed = subprocess.Popen(seed_command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            selector = selectors.DefaultSelector()
            selector.register(seed.stdout, selectors.EVENT_READ)
            buffer = b""
            deadline = time.monotonic() + 50
            try:
                while time.monotonic() < deadline:
                    if not selector.select(timeout=1):
                        if seed.poll() is not None:
                            raise RuntimeError("Seed exited before live-playback sample")
                        continue
                    chunk = seed.stdout.read1(4096)
                    if not chunk:
                        raise RuntimeError("Seed stream ended before live-playback sample")
                    log.write(chunk.decode(errors="replace")); log.flush()
                    buffer += chunk
                    while b"\n" in buffer:
                        raw, buffer = buffer.split(b"\n", 1)
                        line = raw.decode(errors="replace").strip()
                        match = re.fullmatch(r"INSTRUMENTATION_STATUS: (\w+)=(.*)", line)
                        if match:
                            sample[match[1]] = match[2]
                        if line == "INSTRUMENTATION_STATUS_CODE: 2" and sample.get("phase") == "AWAITING_EXTERNAL_TERMINATION":
                            break
                    else:
                        continue
                    break
                else:
                    raise RuntimeError("No live-playback sample within 50s")
                if sample.get("sourceMode") != options.source_mode:
                    raise RuntimeError("Sample source mode differs from the requested trial")
                old_pid = int(sample["pid"])
                if pid() != old_pid:
                    raise RuntimeError("Sample PID does not match actual live target")
                if abs(float(sample["speed"]) - step / 20) > 0.0001:
                    raise RuntimeError("Sample playback speed does not match trial")
                old_boot = boot_id()
                if options.mode == "reboot":
                    run("reboot")
                    run("wait-for-device", timeout=120)
                    deadline = time.monotonic() + 120
                    while True:
                        try:
                            ready = boot_id() != old_boot and run("shell", "getprop", "sys.boot_completed") == "1"
                        except RuntimeError:  # The old transport may disappear after wait-for-device.
                            ready = False
                        if ready:
                            break
                        if time.monotonic() > deadline:
                            raise RuntimeError("Reboot did not finish across a new kernel boundary")
                        time.sleep(1)
                    bracket = None
                else:
                    termination = f"run-as {PACKAGE} kill -9 {old_pid}" if options.mode == "sigkill" else f"am force-stop {PACKAGE}"
                    output = run("shell", f"cat /proc/uptime; {termination}; cat /proc/uptime")
                    bracket = [round(float(line.split()[0]) * 1000) for line in output.splitlines()]
                    if len(bracket) != 2 or bracket[1] < bracket[0]:
                        raise RuntimeError("Invalid termination time bracket")
                deadline = time.monotonic() + 5
                while pid() == old_pid:
                    if time.monotonic() >= deadline:
                        raise RuntimeError("The sampled target PID survived external termination")
                    time.sleep(0.05)
                tail, _ = seed.communicate(timeout=10)
                log.write(tail.decode(errors="replace"))
            finally:
                selector.close()
                if seed.poll() is None:
                    seed.terminate()
                    seed.wait(timeout=5)
                seed.stdout.close()
        verified = run("shell", "am", "instrument", "-w", "-r", "-e", "class", CLASS + "#restoredPausedAfterExternalTermination", "-e", "sourceMode", options.source_mode, "-e", "hostDriven", "true", RUNNER, timeout=45)
        (options.output / f"{step}-verify.log").write_text(verified + "\n")
        if "FAILURES" in verified or not re.search(r"OK \(1 test\)", verified):
            raise RuntimeError("Cold-process verification failed; inspect verify log")
        restored = dict(re.findall(r"INSTRUMENTATION_STATUS: (\w+)=(.*)", verified))
        if restored.get("sourceMode") != options.source_mode:
            raise RuntimeError("Restored source mode differs from the requested trial")
        if restored.get("phase") != "RESTORED_PAUSED":
            raise RuntimeError("Verification omitted positive restored-state evidence")
        saved = int(restored["durablePositionMs"])
        interval = None
        if bracket:
            # /proc/uptime is quantized to 10ms. Require the entire uncertainty
            # interval to meet the budget; never hide uncertainty by rounding down.
            interval = [int(sample["samplePositionMs"]) + (t - int(sample["sampleElapsedMs"])) * step / 20 - saved
                        for t in (bracket[0] - 10, bracket[1] + 10)]
        record = {"step": step, "speed": step / 20, "mode": options.mode, "sample": sample,
                  "killBracketMs": bracket, "restored": restored, "lossIntervalMs": interval,
                  "passed": interval is None or (interval[0] >= -500 and interval[1] <= 5000)}
        records.append(record)
        (options.output / "results.json").write_text(json.dumps(records, indent=2) + "\n")
        print(json.dumps({key: record[key] for key in ["speed", "mode", "lossIntervalMs", "passed"]}), flush=True)
        if not record["passed"]:
            raise RuntimeError("Media-loss budget failed")


if __name__ == "__main__":
    main()
