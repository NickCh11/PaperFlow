import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from check_coverage import read_native
from export_native_coverage import combine, diagnostics, export
from native_coverage import BINARIES, ROOT, manifest


class NativeExportTest(unittest.TestCase):
    source = "translation/native/src/lib.rs"

    def metadata(self, branches, source=None):
        return dict(type="llvm.coverage.json.export", data=[dict(files=[dict(
            filename="/ci/project/" + (source or self.source), branches=branches)])])

    def warning(self, symbol, digest="0", total=1):
        return f"warning: {total} functions have mismatched data\nhash-mismatch: No profile record found for '{symbol}' with hash = 0x{digest}\n"

    def test_only_unused_zero_hash_definitions_from_known_dependencies_are_audited(self):
        symbol = "_RNvCs123_3log9max_level"
        self.assertEqual([dict(dependency="log", symbol=symbol, hash=0)], diagnostics(self.warning(symbol), {"log"}))
        self.assertEqual([], diagnostics("", {"log"}))

    def test_owned_functions_wrong_hashes_and_unaccounted_warnings_fail(self):
        cases = [self.warning("_RNvCs123_17paperflow_runtime3run"),
                 self.warning("Java_com_paperflow_translation_RuntimeNative_image"),
                 self.warning("_RNvCs123_3log9max_level", "123"),
                 self.warning("_RNvCs123_7unknown3run"),
                 self.warning("_RNvCs123_3log9max_level", total=2),
                 "warning: 1 functions have mismatched data\n",
                 "warning: source file does not exist\n", "error: invalid profile\n"]
        for stderr in cases:
            with self.subTest(stderr=stderr), self.assertRaises(ValueError):
                diagnostics(stderr, {"log"})

    def test_object_specific_branch_numbers_do_not_change_source_coverage(self):
        app = (f"SF:/ci/project/{self.source}\nDA:10,1\nDA:11,0\nBRDA:10,9,0,1\nend_of_record\n",
               self.metadata([[10, 1, 10, 5, 1, 0, 0, 0, 4], [10, 8, 10, 12, 0, 1, 0, 0, 4]]))
        unit = (f"SF:/other/{self.source}\nDA:10,0\nDA:11,3\nBRDA:10,83,0,0\nend_of_record\n",
                self.metadata([[10, 8, 10, 12, 2, 0, 0, 0, 4], [10, 1, 10, 5, 0, 3, 0, 0, 4]]))
        output = combine([app, unit], {self.source})
        self.assertEqual(output, combine([unit, app], {self.source}))
        self.assertIn("BRF:4\nBRH:4", output)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "native.lcov"
            path.write_text(output, encoding="utf-8")
            measured = read_native(path, {self.source})
        self.assertEqual("measured", measured["status"])
        self.assertEqual(100, measured["line"]["percent"])
        self.assertEqual(100, measured["branch"]["percent"])

    def test_uncovered_sides_and_lines_remain_missing(self):
        output = combine([(f"SF:{self.source}\nDA:10,0\nDA:11,3\nend_of_record\n",
                           self.metadata([[11, 1, 11, 5, 2, 0, 0, 0, 4]]))], {self.source})
        self.assertIn("DA:10,0", output)
        self.assertIn("BRDA:11,0,1,0", output)
        self.assertIn("LF:2\nLH:1\nBRF:2\nBRH:1", output)

    def test_missing_owned_sources_and_duplicate_file_mappings_cannot_pass(self):
        lcov = f"SF:{self.source}\nDA:10,1\nend_of_record\n"
        branch = [10, 1, 10, 5, 1, 0, 0, 0, 4]
        with self.assertRaises(ValueError):
            combine([(lcov, self.metadata([branch]))], {self.source, "translation/native/src/other.rs"})
        metadata = self.metadata([branch])
        metadata["data"][0]["files"] *= 2
        with self.assertRaisesRegex(ValueError, "Duplicate native source"):
            combine([(lcov, metadata)], {self.source})

    def test_generic_instantiations_union_real_source_hits_without_extra_branches(self):
        # Actual LLVM records from the failed Android font-provider export:
        # one source condition repeated for five resolver instantiations.
        branches = [[362, 31, 362, 35, true, false, 0, 0, 4]
                    for true, false in [(0, 1), (1, 0), (0, 1), (52, 52), (0, 27)]]
        output = combine([(f"SF:{self.source}\nDA:362,52\nend_of_record\n", self.metadata(branches))], {self.source})
        self.assertIn("BRF:2\nBRH:2", output)
        self.assertEqual(2, output.count("BRDA:"))
        self.assertIn("BRDA:362,0,0,52", output)
        self.assertIn("BRDA:362,0,1,52", output)
        self.assertEqual(output, combine([(f"SF:{self.source}\nDA:362,52\nend_of_record\n",
                                          self.metadata(list(reversed(branches))))], {self.source}))

    def test_unexecuted_generic_instantiations_never_gain_coverage(self):
        branch = [10, 1, 10, 5, 0, 0, 0, 0, 4]
        output = combine([(f"SF:{self.source}\nDA:10,0\nend_of_record\n", self.metadata([branch, branch]))], {self.source})
        self.assertIn("LF:1\nLH:0\nBRF:2\nBRH:0", output)

    def test_conflicting_generic_expansion_ids_cannot_be_folded(self):
        first = [10, 1, 10, 5, 1, 0, 0, 0, 4]
        for field in (6, 7):
            conflicting = list(first)
            conflicting[field] = 1
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "Ambiguous native branch"):
                combine([(f"SF:{self.source}\nDA:10,1\nend_of_record\n",
                          self.metadata([first, conflicting]))], {self.source})

    def test_invalid_branch_ranges_and_counts_are_rejected(self):
        lcov = f"SF:{self.source}\nDA:10,1\nend_of_record\n"
        for branch in ([10, 0, 10, 5, 1, 0, 0, 0, 4], [10, 1, 9, 5, 1, 0, 0, 0, 4],
                       [10, 1, 10, 5, -1, 0, 0, 0, 4], [10, 1, 10, 5, 1, 0, 0, 0],
                       [10, 1, 10, 5, True, 0, 0, 0, 4]):
            with self.subTest(branch=branch), self.assertRaises(ValueError):
                combine([(lcov, self.metadata([branch]))], {self.source})
        with self.assertRaises(ValueError):
            combine([(lcov, {"type": "unrelated", "data": []})], {self.source})

    def test_diagnostic_dump_cannot_corrupt_structured_exports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            objects, profiles, llvm = (root / part for part in ("objects", "profiles", "llvm"))
            objects.mkdir(); llvm.mkdir()
            for name in BINARIES:
                (objects / name).write_bytes(b"synthetic ELF for exporter orchestration tests")
            recorded = manifest(objects, "test-revision", "test-toolchain")
            (objects / "manifest.json").write_text(json.dumps(recorded), encoding="utf-8")
            for kind in ("app", "unit"):
                (profiles / kind).mkdir(parents=True)
                (profiles / kind / "input.profraw").write_bytes(b"synthetic profile" * 2)
            for name in ("llvm-cov", "llvm-profdata"):
                (llvm / (name + (".exe" if os.name == "nt" else ""))).touch()
            sources = sorted(file.relative_to(ROOT).as_posix() for file in (ROOT / "translation/native/src").rglob("*.rs"))
            calls = []
            def run(command, **kwargs):
                calls.append(command)
                if command[1] == "merge":
                    Path(command[command.index("-o") + 1]).write_bytes(b"merged test fixture")
                    return
                if "-dump" in command:
                    kwargs["stdout"].write("InstantiationGroup: debug text, not JSON\n")
                    kwargs["stderr"].write(self.warning("_RNvCs123_3log9max_level"))
                    return
                unit = command[2].endswith("paperflow-runtime-tests")
                kwargs["stderr"].write("warning: 1 functions have mismatched data\n")
                if "-format=lcov" in command:
                    kwargs["stdout"].write("".join(f"SF:{source}\nDA:10,1\nend_of_record\n" for source in sources))
                else:
                    kwargs["stdout"].write(json.dumps(dict(type="llvm.coverage.json.export", data=[dict(files=[
                        dict(filename=source, branches=[[10, 1, 10, 5, int(not unit), int(unit), 0, 0, 4]]) for source in sources])])) )
            output = root / "result/native.lcov"
            with patch("export_native_coverage.subprocess.run", side_effect=run):
                measured = export(objects, profiles, output, llvm, "test-revision", "test-toolchain")
            self.assertEqual("measured", measured["status"])
            self.assertEqual(100, measured["branch"]["percent"])
            self.assertEqual(2, sum("-dump" in command for command in calls))
            self.assertFalse(output.read_text("utf-8").startswith("InstantiationGroup"))


if __name__ == "__main__":
    unittest.main()
