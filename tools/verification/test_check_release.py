import unittest
import xml.etree.ElementTree as ET

from check_release import ANDROID, APP_ID, DOMAINS, PLAYBACK_SERVICE, PLAYBACK_PERMISSIONS, BLUETOOTH_VALIDATION, check_extraction, check_manifest, runtime_modules


class ReleaseBoundaryTests(unittest.TestCase):
    def manifest(self):
        root = ET.Element("manifest", {"package": APP_ID})
        app = ET.SubElement(root, "application", {ANDROID + "allowBackup": "false",
            ANDROID + "fullBackupContent": "false", ANDROID + "dataExtractionRules": "@xml/data_extraction_rules"})
        ET.SubElement(app, "activity", {ANDROID + "name": APP_ID + ".ui.MainActivity", ANDROID + "exported": "true"})
        return root

    def test_foundation_boundary(self):
        self.assertEqual(check_manifest(self.manifest()), [])

    def test_network_permission_rejected(self):
        root = self.manifest()
        ET.SubElement(root, "uses-permission-sdk-23", {ANDROID + "name": "android.permission.INTERNET"})
        self.assertTrue(check_manifest(root))

    def test_weak_signature_declaration_rejected(self):
        root = self.manifest()
        ET.SubElement(root, "permission", {ANDROID + "name": APP_ID + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", ANDROID + "protectionLevel": "normal"})
        self.assertTrue(check_manifest(root))

    def test_exported_dependency_receiver_rejected(self):
        root = self.manifest()
        ET.SubElement(root.find("application"), "receiver", {ANDROID + "name": "third.party.Receiver", ANDROID + "exported": "true"})
        self.assertTrue(check_manifest(root))

    def test_playback_permission_budget(self):
        root = self.manifest()
        service = ET.SubElement(root.find("application"), "service", {
            ANDROID + "name": PLAYBACK_SERVICE, ANDROID + "exported": "true",
            ANDROID + "foregroundServiceType": "mediaPlayback"})
        intent = ET.SubElement(service, "intent-filter")
        ET.SubElement(intent, "action", {ANDROID + "name": "androidx.media3.session.MediaSessionService"})
        for name in PLAYBACK_PERMISSIONS:
            ET.SubElement(root, "uses-permission", {ANDROID + "name": name})
        self.assertEqual(check_manifest(root), [])
        ET.SubElement(root, "uses-permission", {ANDROID + "name": "android.permission.ACCESS_NETWORK_STATE"})
        self.assertTrue(check_manifest(root))

    def test_inspection_cannot_lose_isolation(self):
        root = self.manifest()
        service = ET.SubElement(root.find("application"), "service", {
            ANDROID + "name": APP_ID + ".metadata.InspectionService", ANDROID + "exported": "false"})
        self.assertTrue(check_manifest(root))
        service.set(ANDROID + "isolatedProcess", "true")
        self.assertEqual(check_manifest(root), [])

    def test_bluetooth_probe_must_be_privileged(self):
        root = self.manifest()
        activity = ET.SubElement(root.find("application"), "activity", {
            ANDROID + "name": BLUETOOTH_VALIDATION, ANDROID + "exported": "true",
            ANDROID + "excludeFromRecents": "true", ANDROID + "noHistory": "true",
            ANDROID + "theme": "@android:style/Theme.NoDisplay"})
        self.assertTrue(check_manifest(root))
        activity.set(ANDROID + "permission", "android.permission.BLUETOOTH_PRIVILEGED")
        self.assertEqual(check_manifest(root), [])

    def test_all_transfer_modes_exclude_data(self):
        root = ET.Element("data-extraction-rules")
        for mode in ("cloud-backup", "device-transfer", "cross-platform-transfer"):
            section = ET.SubElement(root, mode, {"platform": "ios"} if mode == "cross-platform-transfer" else {})
            for domain in DOMAINS:
                ET.SubElement(section, "exclude", {"domain": domain, "path": "."})
        self.assertEqual(check_extraction(root), [])
        root.remove(root.find("cross-platform-transfer"))
        self.assertTrue(check_extraction(root))

    def test_dependency_report_must_be_complete(self):
        self.assertEqual(runtime_modules("releaseRuntimeClasspath - Runtime\n+--- example.lib:artifact:1.0"), {"example.lib:artifact"})
        with self.assertRaises(ValueError):
            runtime_modules("releaseRuntimeClasspath - Runtime\n+--- example.lib:artifact:1.0 FAILED")


if __name__ == "__main__":
    unittest.main()
