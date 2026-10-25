import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from native_coverage import BINARIES, app_session, assert_test_pass, collect, manifest, profile_names, rebind, verify


class NativeCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def record(self):
        for name in BINARIES:
            (self.root / name).write_bytes(b"fixture instrumented ELF " + name.encode())
        result = manifest(self.root, "revision-one", "pinned-nightly")
        (self.root / "manifest.json").write_text(json.dumps(result), encoding="utf-8")
        return result

    def test_coverage_objects_are_bound_to_the_recorded_revision_and_toolchain(self):
        expected = self.record()
        self.assertEqual(expected, verify(self.root, "revision-one", "pinned-nightly"))
        for revision, toolchain in (("other", "pinned-nightly"), ("revision-one", "different")):
            with self.assertRaises(ValueError):
                verify(self.root, revision, toolchain)

    def test_missing_empty_or_modified_native_object_cannot_be_reused(self):
        for name in BINARIES:
            self.record()
            path = self.root / name
            path.write_bytes(b"replaced binary")
            with self.assertRaises(ValueError):
                verify(self.root, "revision-one", "pinned-nightly")
            path.write_bytes(b"")
            with self.assertRaises(ValueError):
                verify(self.root, "revision-one", "pinned-nightly")
            path.unlink()
            with self.assertRaises(ValueError):
                verify(self.root, "revision-one", "pinned-nightly")

    def test_identical_cached_inputs_can_be_bound_to_the_current_run_without_recompilation(self):
        before=self.record()
        after=rebind(self.root,"revision-two","pinned-nightly")
        self.assertEqual(before["binaries"],after["binaries"])
        self.assertEqual(before["inputs"],after["inputs"])
        self.assertEqual("revision-two",verify(self.root,"revision-two","pinned-nightly")["revision"])

    def test_rebinding_does_not_relabel_replaced_objects_or_a_different_toolchain(self):
        self.record()
        with self.assertRaises(ValueError):
            rebind(self.root,"revision-two","other-nightly")
        (self.root/BINARIES[0]).write_bytes(b"different binary")
        with self.assertRaises(ValueError):
            rebind(self.root,"revision-two","pinned-nightly")

    def test_cached_source_fingerprints_must_match_the_current_native_sources(self):
        recorded=self.record()
        first=next(iter(recorded["inputs"]))
        recorded["inputs"][first]="outdated-source-fingerprint"
        (self.root/"manifest.json").write_text(json.dumps(recorded),encoding="utf-8")
        with self.assertRaises(ValueError):
            rebind(self.root,"revision-two","pinned-nightly")

    def test_bundled_catalog_changes_cannot_reuse_outdated_native_font_tests(self):
        recorded = self.record()
        catalog = "translation/src/main/assets/index_v6.json"
        self.assertIn(catalog, recorded["inputs"])
        recorded["inputs"][catalog] = "previous-language-catalog"
        (self.root / "manifest.json").write_text(json.dumps(recorded), encoding="utf-8")
        with self.assertRaises(ValueError):
            rebind(self.root, "revision-two", "pinned-nightly")

    def test_incomplete_or_ignored_unit_suites_are_not_native_execution_coverage(self):
        assert_test_pass("test result: ok. 10 passed; 0 failed; 0 ignored; 0 measured", 10)
        for value in ("", "test result: ok. 9 passed; 0 failed; 0 ignored;", "test result: ok. 0 passed; 0 failed; 10 ignored;", "test result: FAILED. 9 passed; 1 failed; 0 ignored;"):
            with self.assertRaises(ValueError):
                assert_test_pass(value, 10)

    def test_device_profile_names_cannot_be_used_as_paths_or_shell_commands(self):
        self.assertEqual(["123_0-789.profraw"], profile_names(b"123_0-789.profraw\r\n"))
        for value in (b"", b"../escape.profraw", b"$(secret).profraw", b"/absolute.profraw", b"a.profraw\na.profraw", b"profiles-directory"):
            with self.assertRaises(ValueError):
                profile_names(value)

    def test_application_profiles_must_name_a_generated_session(self):
        value = "12345678-1234-4321-abcd-123456789abc"
        self.assertEqual(value, app_session((value + "\n").encode()))
        for raw in (b"", b"../previous-run", b"123;command", b"/data/user/0"):
            with self.assertRaises(ValueError):
                app_session(raw)

    def test_binary_profiles_are_copied_without_text_conversion(self):
        calls = []
        data = b"\x81\x00\r\n\xff" * 20
        def run(command, **kwargs):
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, b"123_0-7.profraw\r\n" if "ls" in command else data)
        destination = self.root / "profiles"
        self.assertEqual(1, collect(["adb", "-s", "fixture-emulator"], "files/native-coverage/session", destination, application=True, run=run))
        self.assertEqual(data, (destination / "123_0-7.profraw").read_bytes())
        self.assertEqual(["exec-out", "run-as", "com.example.myapplication", "cat", "files/native-coverage/session/123_0-7.profraw"], calls[1][3:])

    def test_empty_profile_cannot_pass_collection(self):
        def run(command, **kwargs):
            return subprocess.CompletedProcess(command, 0, b"123_0-7.profraw\n" if "ls" in command else b"")
        with self.assertRaises(ValueError):
            collect(["adb"], "/fixed/remote", self.root / "profiles", run=run)


if __name__ == "__main__":
    unittest.main()
