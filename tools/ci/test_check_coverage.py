import contextlib
from decimal import Decimal
import io
from pathlib import Path
import tempfile
import unittest

from check_coverage import Counter, evaluate, main, markdown, read_jacoco, read_native


class WholeProjectCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.native = {"translation/native/src/lib.rs", "translation/native/src/live_pipeline_jni.rs"}
        self.reports = {}
        for module in ("app", "scanengine", "translation"):
            path = self.root / module / "build/reports/jacoco/coverageReport/coverageReport.xml"
            path.parent.mkdir(parents=True)
            path.write_text(self.xml(), encoding="utf-8")
            self.reports[module] = path
        for source in self.native:
            path = self.root / source
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fn sample() {}", encoding="utf-8")

    def xml(self, covered=3, missed=0):
        counters = f'<counter type="LINE" covered="{covered}" missed="{missed}"/><counter type="BRANCH" covered="2" missed="0"/>'
        return '<report name="fixture"><package name="example"><sourcefile name="Sample.java"><line nr="7" ci="0"/>' + counters + '</sourcefile></package>' + counters + '</report>'

    def lcov(self, sources=None, hits="1", branches=True):
        path = self.root / "native.lcov"
        rows = []
        for source in sorted(self.native if sources is None else sources):
            rows.extend([f"SF:/runner/work/PaperFlow/{source}", f"DA:7,{hits}"])
            if branches:
                rows.extend([f"BRDA:7,0,0,{hits}", f"BRDA:7,0,1,{hits}"])
            rows.append("end_of_record")
        path.write_text("\n".join(rows), encoding="utf-8")
        return path

    def test_rounding_cannot_turn_a_missing_line_into_a_pass(self):
        counter = Counter(999999999, 1)
        self.assertEqual(100.0, counter.data()["percent"])
        self.assertFalse(counter.passes(Decimal(100)))

    def test_native_report_locates_actual_uncovered_lines_and_branches_per_source(self):
        result=read_native(self.lcov(hits="0"),self.native)
        self.assertEqual(self.native,{row["path"] for row in result["files"]})
        for row in result["files"]:
            self.assertEqual([7],row["uncovered_lines"])
            self.assertEqual([7],row["uncovered_branch_lines"])
            self.assertEqual(1,row["line"]["missed"])
            self.assertEqual(2,row["branch"]["missed"])
        self.assertEqual(sum(row["line"]["total"] for row in result["files"]),result["line"]["total"])

    def test_unmeasured_native_scope_cannot_invent_file_counters(self):
        native=read_native(None,self.native)
        self.assertEqual([],native["files"])
        self.assertIsNone(native["line"])
        self.assertEqual(self.native,set(native["missing_sources"]))

    def test_no_branches_is_not_reported_as_measured_one_hundred_percent(self):
        self.assertIsNone(Counter(0, 0).data()["percent"])
        self.assertTrue(Counter(0, 0).passes(Decimal(100)))

    def test_complete_reports_from_every_module_can_pass(self):
        result = evaluate(self.reports, Decimal(100), self.lcov(), self.native)
        self.assertTrue(result["passed"])
        self.assertEqual(9, result["jvm"]["line"]["covered"])
        self.assertEqual("measured", result["native"]["status"])

    def test_missing_native_measurement_blocks_a_whole_project_pass(self):
        result = evaluate(self.reports, Decimal(100), None, self.native)
        self.assertFalse(result["passed"])
        self.assertEqual("unmeasured", result["native"]["status"])
        self.assertIn("Not measured", markdown(result))

    def test_host_only_native_report_does_not_stand_in_for_android_jni(self):
        result = evaluate(self.reports, Decimal(100), self.lcov({"translation/native/src/lib.rs"}), self.native)
        self.assertFalse(result["passed"])
        self.assertEqual(["translation/native/src/live_pipeline_jni.rs"], result["native"]["missing_sources"])

    def test_native_lines_without_branch_instrumentation_do_not_pass(self):
        result = evaluate(self.reports, Decimal(100), self.lcov(branches=False), self.native)
        self.assertFalse(result["passed"])
        self.assertEqual("partial", result["native"]["status"])

    def test_explicit_missing_native_report_is_a_measurement_error(self):
        with self.assertRaises(ValueError):
            read_native(self.root / "missing.lcov", self.native)

    def test_malformed_native_records_are_rejected(self):
        for record in ("DA:7", "DA:0,1", "BRDA:0,0,0,1", "BRDA:7,-1,0,1", "BRDA:7,0,-1,1", "BRDA:7,0,0", "BRDA:7,0,0,invalid"):
            path = self.root / "broken.lcov"
            path.write_text("SF:translation/native/src/lib.rs\n" + record + "\nend_of_record\n", encoding="utf-8")
            with self.subTest(record=record), self.assertRaises(ValueError):
                read_native(path, self.native)

    def test_uncovered_native_records_fail_the_target(self):
        result = evaluate(self.reports, Decimal(100), self.lcov(hits="0"), self.native)
        self.assertFalse(result["passed"])
        self.assertEqual(2, result["native"]["line"]["missed"])

    def test_missing_jvm_module_and_reused_report_are_rejected(self):
        with self.assertRaises(ValueError):
            evaluate({"app": self.reports["app"]}, Decimal(100))
        with self.assertRaises(ValueError):
            evaluate(dict.fromkeys(self.reports, self.reports["app"]), Decimal(100))

    def test_uncovered_module_cannot_hide_behind_other_complete_modules(self):
        self.reports["translation"].write_text(self.xml(2, 1), encoding="utf-8")
        result = evaluate(self.reports, Decimal(100), self.lcov(), self.native)
        self.assertFalse(result["passed"])
        self.assertEqual(1, result["modules"]["translation"]["line"]["missed"])

    def test_empty_missing_and_negative_counters_are_rejected(self):
        for text in (self.xml(0, 0), self.xml(-1, 1), self.xml().replace('type="BRANCH"', 'type="OTHER"'), "<report/>"):
            self.reports["app"].write_text(text, encoding="utf-8")
            with self.assertRaises(ValueError):
                read_jacoco(self.reports["app"])

    def test_duplicate_root_counter_is_rejected(self):
        self.reports["app"].write_text(self.xml().replace("</report>", '<counter type="LINE" covered="3" missed="0"/></report>'), encoding="utf-8")
        with self.assertRaises(ValueError):
            read_jacoco(self.reports["app"])

    def test_dependency_coverage_cannot_mask_uncovered_owned_native_source(self):
        path = self.lcov(hits="0")
        with path.open("a", encoding="utf-8") as output:
            output.write("\nSF:/cargo/vendor/library.rs\nDA:1,300\nBRDA:1,0,0,300\nend_of_record\n")
        result = read_native(path, self.native)
        self.assertEqual(0, result["line"]["covered"])
        self.assertEqual(2, result["line"]["total"])

    def test_negative_native_hit_count_is_rejected(self):
        with self.assertRaises(ValueError):
            read_native(self.lcov(hits="-3"), self.native)

    def test_cli_writes_the_gap_report_while_returning_a_failed_target(self):
        report = self.root / "out/report.md"
        data = self.root / "out/report.json"
        with contextlib.redirect_stdout(io.StringIO()):
            code = main(["--root", str(self.root), "--markdown", str(report), "--json", str(data)])
        self.assertEqual(1, code)
        self.assertIn("TARGET NOT MET", report.read_text("utf-8"))
        self.assertIn('"status": "unmeasured"', data.read_text("utf-8"))

    def test_cli_rejects_nonfinite_and_out_of_range_targets(self):
        for target in ("NaN", "Infinity", "-1", "101"):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                main(["--target", target])


if __name__ == "__main__":
    unittest.main()
