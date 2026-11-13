#!/usr/bin/env python3
"""Hold only the production inspector worker with public JDI; require watchdog death and recovery."""
import argparse
import os
import selectors
import subprocess
import time
from pathlib import Path

PACKAGE = "io.github.jnesew.paudiobooks"


def await_marker(path, marker, process, seconds):
    deadline = time.monotonic() + seconds
    while marker not in path.read_text():
        if process.poll() is not None or time.monotonic() >= deadline:
            raise RuntimeError("Missing " + marker + ": " + path.read_text())
        time.sleep(0.1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--classes", type=Path, required=True, help="javac output for HoldInspectionWorker.java")
    parser.add_argument("--java", default="java")
    options = parser.parse_args()
    options.output.mkdir(parents=True, exist_ok=False)
    adb = ["adb", "-s", options.serial]

    def run(*args):
        result = subprocess.run(adb + list(args), capture_output=True, text=True, timeout=20)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout.strip()

    if run("emu", "avd", "name").splitlines()[0] != options.avd:
        raise RuntimeError("Unexpected AVD identity")
    test = debugger = None
    port = None
    try:
        with (options.output / "instrument.log").open("w") as log:
            test = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "hostDriven", "true",
                "-e", "class", PACKAGE + ".metadata.InspectionWatchdogTest#watchdogTerminatesHeldWorkerAndNextInspectionSucceeds",
                PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"], stdout=log, stderr=subprocess.STDOUT)
            await_marker(options.output / "instrument.log", "WATCHDOG_ATTACH_READY", test, 15)
            processes = [line.split() for line in run("shell", "ps", "-A").splitlines()[1:]]
            candidates = [line[1] for line in processes if line[0].startswith("u0_i") and
                line[-1] in (PACKAGE, PACKAGE + ":" + PACKAGE + ".metadata.InspectionService")]
            if len(candidates) != 1:
                raise RuntimeError("Ambiguous isolated process identity: " + str(candidates))
            pid = candidates[0]
            probe = subprocess.Popen(adb + ["jdwp"], stdout=subprocess.PIPE)
            selector = selectors.DefaultSelector()
            try:
                selector.register(probe.stdout, selectors.EVENT_READ)
                data = b""
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    if selector.select(0.1):
                        chunk = os.read(probe.stdout.fileno(), 4096)
                        if not chunk:
                            break
                        data += chunk
                if pid not in data.decode().split():
                    raise RuntimeError("Isolated process is not debuggable")
            finally:
                probe.terminate()
                probe.wait(timeout=5)
                probe.stdout.close()
                selector.close()
            port = run("forward", "tcp:0", "jdwp:" + pid)
            if not port.isdigit():
                raise RuntimeError("Invalid forwarded port: " + port)
            with (options.output / "debugger.log").open("w") as debuglog:
                debugger = subprocess.Popen([options.java, "--add-modules", "jdk.jdi", "-cp", str(options.classes),
                    "HoldInspectionWorker", port], stdout=debuglog, stderr=subprocess.STDOUT)
                await_marker(options.output / "debugger.log", "DEBUGGER_ATTACHED", debugger, 10)
                run("shell", "run-as", PACKAGE, "touch", "files/watchdog-continue")
                test.wait(timeout=45)
                debugger.wait(timeout=10)
                if debugger.returncode:
                    raise RuntimeError((options.output / "debugger.log").read_text())
    finally:
        for process in (debugger, test):
            if process is not None and process.poll() is None:
                process.terminate()
                process.wait(timeout=5)
        if port is not None and port.isdigit():
            run("forward", "--remove", "tcp:" + port)
    output = (options.output / "instrument.log").read_text()
    debug = (options.output / "debugger.log").read_text()
    if "OK (1 test)" not in output or "FAILURES" in output or "INSPECTION_WORKER_HELD" not in debug or "WATCHDOG_DISCONNECTED" not in debug:
        raise RuntimeError(output + debug)
    print(output)


if __name__ == "__main__":
    main()
