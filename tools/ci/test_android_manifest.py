from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from check_android_manifest import ANDROID as A, PACKAGE, check


class ReleaseManifestTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.path = Path(self.folder.name, "AndroidManifest.xml")
        self.root = ET.Element("manifest", package=PACKAGE)
        ET.SubElement(self.root, "uses-sdk", {A+"targetSdkVersion": "37"})
        self.app = ET.SubElement(self.root, "application")
        self.entry = ET.SubElement(self.app, "activity", {A+"name": ".DocumentEntryActivity", A+"exported": "true"})
        self.provider = ET.SubElement(self.app, "provider", {
            A+"name": "androidx.core.content.FileProvider", A+"exported": "false",
            A+"authorities": PACKAGE+".files", A+"grantUriPermissions": "true"})
        self.roots = ET.SubElement(self.provider, "meta-data", {
            A+"name": "android.support.FILE_PROVIDER_PATHS", A+"resource": "@xml/file_paths"})

    def verify(self):
        ET.ElementTree(self.root).write(self.path, encoding="utf-8")
        return check(self.path)

    def test_safe_release_and_signature_guarded_profile_receiver_pass(self):
        self.assertTrue(self.verify())
        ET.SubElement(self.app, "receiver", {A+"name": "androidx.profileinstaller.ProfileInstallReceiver",
            A+"exported": "true", A+"permission": "android.permission.DUMP"})
        self.assertTrue(self.verify())

    def test_debug_testonly_and_cleartext_release_flags_are_rejected(self):
        for flag in ("debuggable", "testOnly", "usesCleartextTraffic"):
            self.app.set(A+flag, "true")
            with self.assertRaisesRegex(ValueError, flag): self.verify()
            del self.app.attrib[A+flag]
        self.app.set(A+"networkSecurityConfig", "@xml/unreviewed")
        with self.assertRaisesRegex(ValueError, "network security"): self.verify()

    def test_new_permissions_and_unbounded_legacy_storage_are_rejected(self):
        permission = ET.SubElement(self.root, "uses-permission", {A+"name": "android.permission.MANAGE_EXTERNAL_STORAGE"})
        with self.assertRaisesRegex(ValueError, "Unexpected release permission"): self.verify()
        permission.set(A+"name", "android.permission.WRITE_EXTERNAL_STORAGE")
        with self.assertRaisesRegex(ValueError, "API 28"): self.verify()
        permission.set(A+"maxSdkVersion", "28")
        self.assertTrue(self.verify())

    def test_dependency_or_app_cannot_add_an_unreviewed_public_component(self):
        for kind in ("activity", "activity-alias", "service", "receiver", "provider"):
            component = ET.SubElement(self.app, kind, {A+"name": ".Unexpected", A+"exported": "true"})
            with self.assertRaisesRegex(ValueError, "Unexpected exported"): self.verify()
            self.app.remove(component)

    def test_profile_receiver_cannot_lose_its_signature_permission(self):
        ET.SubElement(self.app, "receiver", {A+"name": "androidx.profileinstaller.ProfileInstallReceiver", A+"exported": "true"})
        with self.assertRaisesRegex(ValueError, "Unexpected exported"): self.verify()

    def test_private_sharing_and_public_entry_cannot_be_removed_or_widened(self):
        for flag, value in (("exported", "true"), ("grantUriPermissions", "false"), ("authorities", "other.files")):
            original = self.provider.get(A+flag); self.provider.set(A+flag, value)
            with self.assertRaises(ValueError): self.verify()
            self.provider.set(A+flag, original)
        self.entry.set(A+"exported", "false")
        with self.assertRaisesRegex(ValueError, "public document entry"): self.verify()
        self.entry.set(A+"exported", "true")
        self.roots.set(A+"resource", "@xml/all_private_files")
        with self.assertRaisesRegex(ValueError, "reviewed export-folder roots"): self.verify()

    def test_debug_fixture_is_forbidden_even_when_not_exported(self):
        ET.SubElement(self.app, "provider", {A+"name": ".TranslationTestDocumentsProvider", A+"exported": "false"})
        with self.assertRaisesRegex(ValueError, "Debug document provider"): self.verify()

    def test_implicit_exports_and_normal_custom_permissions_cannot_bypass_policy(self):
        component = ET.SubElement(self.app, "service", {A+"name": ".Implicit"})
        ET.SubElement(component, "intent-filter")
        with self.assertRaisesRegex(ValueError, "explicit exported"): self.verify()
        self.app.remove(component)
        ET.SubElement(self.root, "permission", {A+"name": PACKAGE+".weak", A+"protectionLevel": "normal"})
        with self.assertRaisesRegex(ValueError, "signature protected"): self.verify()
