#!/usr/bin/env python3
"""Fail closed on changes to the offline player's merged release boundary."""

import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
APP_ID = "io.github.jnesew.paudiobooks"
PLAYBACK_SERVICE = APP_ID + ".playback.PlaybackService"
BLUETOOTH_VALIDATION = "androidx.media3.session.BluetoothValidationActivity"
PLAYBACK_PERMISSIONS = {"android.permission.FOREGROUND_SERVICE",
                        "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
                        "android.permission.WAKE_LOCK"}
DOMAINS = {"root", "file", "database", "sharedpref", "external", "device_root",
           "device_file", "device_database", "device_sharedpref"}


def check_manifest(root):
    problems = []
    signature = APP_ID + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    if root.get("package") != APP_ID:
        problems.append("Unexpected application ID")
    app = root.find("application")
    has_playback = app is not None and any(e.tag == "service" and e.get(ANDROID + "name") == PLAYBACK_SERVICE for e in app)
    allowed = {signature} | (PLAYBACK_PERMISSIONS if has_playback else set())
    requested = set()
    for element in root:
        if element.tag.startswith("uses-permission"):
            name = element.get(ANDROID + "name")
            requested.add(name)
            if name not in allowed:
                problems.append("Unexpected requested permission: " + str(name))
        if element.tag == "permission" and (
                element.get(ANDROID + "name") != signature or
                element.get(ANDROID + "protectionLevel") != "signature"):
            problems.append("Unexpected permission declaration/protection")
    requests_signature = any(e.tag.startswith("uses-permission") and
                             e.get(ANDROID + "name") == signature for e in root)
    if requests_signature and not any(e.tag == "permission" and
                                     e.get(ANDROID + "name") == signature and
                                     e.get(ANDROID + "protectionLevel") == "signature" for e in root):
        problems.append("Receiver permission lacks its signature declaration")
    if has_playback and not PLAYBACK_PERMISSIONS <= requested:
        problems.append("Playback service lacks required playback permissions")
    if app is None:
        return problems + ["Missing application"]
    for attribute, expected in {"allowBackup": "false", "fullBackupContent": "false",
                                "dataExtractionRules": "@xml/data_extraction_rules"}.items():
        if app.get(ANDROID + attribute) != expected:
            problems.append("Unexpected application " + attribute)
    if app.get(ANDROID + "debuggable", "false") != "false":
        problems.append("Release application is debuggable")
    if app.get(ANDROID + "backupAgent"):
        problems.append("Unexpected backup agent")
    components = {"activity", "activity-alias", "service", "provider", "receiver"}
    for component in app:
        if component.tag not in components:
            continue
        name = component.get(ANDROID + "name")
        expected_export = "true" if name in {APP_ID + ".ui.MainActivity", PLAYBACK_SERVICE, BLUETOOTH_VALIDATION} else "false"
        if component.get(ANDROID + "exported") != expected_export:
            problems.append("Unexpected or implicit export: " + str(name))
        if name == PLAYBACK_SERVICE and (component.tag != "service" or
                component.get(ANDROID + "foregroundServiceType") != "mediaPlayback" or
                {action.get(ANDROID + "name") for action in component.findall("intent-filter/action")} != {"androidx.media3.session.MediaSessionService"}):
            problems.append("Unexpected playback service configuration")
        if name == APP_ID + ".metadata.InspectionService" and (
                component.tag != "service" or component.get(ANDROID + "isolatedProcess") != "true"):
            problems.append("Metadata inspection must remain isolated")
        if name == BLUETOOTH_VALIDATION:
            # Upstream API 36/37 AVRCP probe workaround. This restricts its callers;
            # the application does not request BLUETOOTH_PRIVILEGED itself.
            if (component.tag != "activity" or
                    component.get(ANDROID + "permission") != "android.permission.BLUETOOTH_PRIVILEGED" or
                    component.get(ANDROID + "excludeFromRecents") != "true" or
                    component.get(ANDROID + "noHistory") != "true" or
                    component.get(ANDROID + "theme") != "@android:style/Theme.NoDisplay"):
                problems.append("Bluetooth validation must retain upstream privileged protection")
    return problems


def check_extraction(root):
    problems = []
    if root.tag != "data-extraction-rules":
        return ["Invalid extraction rules root"]
    for mode in ("cloud-backup", "device-transfer", "cross-platform-transfer"):
        sections = root.findall(mode)
        if len(sections) != 1:
            problems.append("Require exactly one " + mode)
            continue
        section = sections[0]
        if mode == "cross-platform-transfer" and section.get("platform") != "ios":
            problems.append("Unexpected cross-platform destination")
        exclusions = {e.get("domain") for e in section if e.tag == "exclude" and e.get("path") == "."}
        if exclusions != DOMAINS or any(e.tag != "exclude" for e in section):
            problems.append("Incomplete exclusions or unexpected inclusion in " + mode)
    return problems


def runtime_modules(report):
    if "releaseRuntimeClasspath -" not in report or "FAILED" in report:
        raise ValueError("Require a complete successful releaseRuntimeClasspath report")
    modules = set(re.findall(r"(?:\+---|\\---) ([\w.-]+:[\w.-]+)(?=[:\s])", report))
    if not modules:
        raise ValueError("Empty dependency report")
    return modules


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("dependencies", type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    problems = check_manifest(ET.parse(args.manifest).getroot())
    problems += check_extraction(ET.parse(repo / "app/src/main/res/xml/data_extraction_rules.xml").getroot())
    approved = {line for line in (repo / "tools/verification/runtime-modules.txt").read_text().splitlines()
                if line and not line.startswith("#")}
    actual = runtime_modules(args.dependencies.read_text())
    if actual != approved:
        problems.append("Runtime module review required. Added: " + ", ".join(sorted(actual - approved)) +
                        "; removed: " + ", ".join(sorted(approved - actual)))
    if problems:
        raise SystemExit("\n".join(problems))
    print(f"Release boundary passed: playback permission budget; reviewed exports; backup exclusions; {len(actual)} reviewed runtime modules.")


if __name__ == "__main__":
    main()
