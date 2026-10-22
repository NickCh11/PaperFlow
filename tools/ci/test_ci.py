import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from check_test_results import check
from prepare_models import safe_name, verify, fetch


class CiRegressionTest(unittest.TestCase):
    def test_vendored_cpuinfo_logging_sources_are_present(self):
        root = Path(__file__).resolve().parents[2]
        clog = root / "translation/third-party/slimt/3rd-party/ruy/third_party/cpuinfo/deps/clog"
        for name in ("CMakeLists.txt", "LICENSE", "include/clog.h", "src/clog.c"):
            with self.subTest(file=name):
                self.assertTrue((clog / name).is_file(), f"Missing vendored native source: {name}")

    def test_cached_download_is_reverified_before_use(self):
        import io
        data=b"verified model"
        entry={"url":"https://example.invalid/model", "size":len(data), "sha256":hashlib.sha256(data).hexdigest()}
        with tempfile.TemporaryDirectory() as folder:
            target=Path(folder,"model.bin")
            target.write_bytes(b"corrupt")
            with patch("prepare_models.urllib.request.urlopen",return_value=io.BytesIO(data)) as download:
                fetch(entry,target)
                download.assert_called_once()
            self.assertEqual(data,target.read_bytes())
            with patch("prepare_models.urllib.request.urlopen") as download:
                fetch(entry,target)
                download.assert_not_called()
            self.assertFalse(target.with_name("model.bin.part").exists())

    def test_failed_download_never_installs_and_cleans_partial_file(self):
        import io
        entry={"url":"https://example.invalid/model", "size":3, "sha256":"0"*64}
        with tempfile.TemporaryDirectory() as folder:
            target=Path(folder,"model.bin")
            with patch("prepare_models.urllib.request.urlopen",side_effect=lambda *a,**k:io.BytesIO(b"bad")),patch("prepare_models.time.sleep"):
                with self.assertRaises(ValueError):fetch(entry,target)
            self.assertFalse(target.exists())
            self.assertFalse(target.with_name("model.bin.part").exists())

    def test_checksums_reject_corruption_and_wrong_size(self):
        data = b"fixture"
        entry = {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        verify(data, entry)
        with self.assertRaises(ValueError):
            verify(b"corrupt", entry)
        with self.assertRaises(ValueError):
            verify(data + b"x", entry)

    def test_git_blob_digest(self):
        verify(b"abc", {"size": 3, "blobSha1": hashlib.sha1(b"blob 3\0abc").hexdigest()})

    def test_manifest_paths_cannot_escape(self):
        self.assertEqual("model.en-el.bin", safe_name("model.en-el.bin"))
        for value in ("../key", "/etc/passwd", "..", "a b", "$(id)"):
            with self.assertRaises(ValueError):
                safe_name(value)

    def test_gate_rejects_empty_reports(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                check(folder)
            Path(folder, "TEST-a.xml").write_text('<testsuite tests="0"/>')
            with self.assertRaises(ValueError):
                check(folder)

    def test_gate_checks_suite_level_failures(self):
        with tempfile.TemporaryDirectory() as folder:
            report=Path(folder,"TEST-a.xml")
            for attribute in ("errors", "failures", "skipped"):
                report.write_text(f'<testsuite {attribute}="1"><testcase name="a"/></testsuite>')
                with self.assertRaises(ValueError):check(folder)

    def test_gate_rejects_failures_errors_and_skips(self):
        with tempfile.TemporaryDirectory() as folder:
            report = Path(folder, "TEST-a.xml")
            for tag in ("failure", "error", "skipped"):
                report.write_text(f'<testsuite><testcase name="a"><{tag}/></testcase></testsuite>')
                with self.assertRaises(ValueError):
                    check(folder)
            report.write_text('<testsuite><testcase name="a"/></testsuite>')
            self.assertEqual(1, check(folder))

    def test_gate_rejects_partial_suite(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder, "sources")
            source.mkdir()
            Path(source, "ExampleTest.java").write_text("package example; public class ExampleTest { @Test public void first() {} @Test public void second() {} }")
            report = Path(folder, "TEST-a.xml")
            report.write_text('<testsuite><testcase classname="example.ExampleTest" name="first"/></testsuite>')
            with self.assertRaisesRegex(ValueError, "second"):
                check(folder, source)
            report.write_text('<testsuite><testcase classname="example.ExampleTest" name="first"/><testcase classname="example.ExampleTest" name="second"/></testsuite>')
            self.assertEqual(2, check(folder, source))

    def test_kotlin_tests_are_also_required(self):
        from check_test_results import expected_tests
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, "KotlinTest.kt").write_text("package example\nclass KotlinTest { @Test fun context() {} }")
            self.assertEqual({("example.KotlinTest", "context")}, expected_tests(folder))
            Path(folder, "KotlinTest.kt").write_text("package example\nclass KotlinTest { @Test(timeout=100) fun context() {} }")
            with self.assertRaises(ValueError):
                expected_tests(folder)


if __name__ == "__main__":
    unittest.main()
