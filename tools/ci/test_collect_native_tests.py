import json
from pathlib import Path
import tempfile
import unittest
from collect_native_tests import collect


class NativeTestCollectionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.messages = self.root / "cargo.json"
        self.binary = self.root / "test-binary"
        self.binary.write_bytes(b"fixture-test-executable")
        self.output = self.root / "collected/test-binary"

    def record(self, **overrides):
        result = dict(reason="compiler-artifact", target=dict(name="paperflow_runtime"), profile=dict(test=True), executable=str(self.binary))
        result.update(overrides)
        return result

    def write(self, *records, finished=True):
        rows=list(records)
        if finished is not None:
            rows.append(dict(reason="build-finished",success=finished))
        self.messages.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")

    def test_wrapper_exit_zero_cannot_hide_failed_or_incomplete_cargo_build(self):
        for finished in (None,False):
            self.write(self.record(),finished=finished)
            with self.assertRaises(ValueError):
                collect(self.messages,self.output)

    def test_collects_the_test_binary_while_ignoring_other_cargo_artifacts(self):
        self.write(self.record(reason="build-script-executed"), self.record(profile=dict(test=False)), self.record(target=dict(name="dependency")), self.record())
        collect(self.messages, self.output)
        self.assertEqual(self.binary.read_bytes(), self.output.read_bytes())

    def test_release_library_or_no_tests_cannot_stand_in_for_the_test_harness(self):
        for row in (self.record(profile=dict(test=False)), self.record(executable=None), self.record(target=dict(name="dependency"))):
            self.write(row)
            with self.assertRaises(ValueError):
                collect(self.messages, self.output)

    def test_ambiguous_missing_and_empty_binaries_are_rejected(self):
        self.write(self.record(), self.record(executable=str(self.root / "other")))
        with self.assertRaises(ValueError):
            collect(self.messages, self.output)
        self.binary.unlink()
        self.write(self.record())
        with self.assertRaises(ValueError):
            collect(self.messages, self.output)
        self.binary.touch()
        with self.assertRaises(ValueError):
            collect(self.messages, self.output)


if __name__ == "__main__":
    unittest.main()
