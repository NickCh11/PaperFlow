import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch
from ci_plan import plan, resolve_manual_pr


class CiPlanTest(unittest.TestCase):
    def test_draft_updates_stay_fast_even_with_an_old_full_label(self):
        for action in ("opened", "synchronize", "reopened", "converted_to_draft"):
            with self.subTest(action=action):
                event = {"action": action, "pull_request": {"draft": True},
                         "labels": [{"name": "full-validation"}]}
                self.assertEqual("false", plan("pull_request", event, "work")["full"])

    def test_ready_pr_open_reopen_and_every_update_require_full_validation(self):
        for action in ("opened", "synchronize", "reopened", "ready_for_review"):
            with self.subTest(action=action):
                event = {"action": action, "pull_request": {"draft": False}}
                self.assertEqual("true", plan("pull_request", event, "work")["full"])

    def test_missing_readiness_metadata_cannot_downgrade_a_pr(self):
        self.assertEqual("true", plan("pull_request", {"action": "synchronize"}, "work")["full"])

    def test_explicit_full_label_overrides_draft_but_unrelated_labels_do_not(self):
        for draft in (True, False):
            with self.subTest(draft=draft):
                event = {"action": "labeled", "pull_request": {"draft": draft},
                         "label": {"name": "full-validation"}}
                self.assertEqual("true", plan("pull_request", event, "work")["full"])
                event["label"]["name"] = "documentation"
                self.assertEqual("false", plan("pull_request", event, "work")["full"])

    def test_main_and_merge_queue_still_require_full_acceptance(self):
        for branch in ("main", "master"):
            self.assertEqual("true", plan("push", {}, branch)["full"])
        self.assertEqual("true", plan("merge_group", {}, "queue")["full"])
        self.assertEqual("false", plan("push", {}, "feature/offline-translator")["full"])

    def test_manual_branch_run_without_a_pr_preserves_the_selected_mode(self):
        self.assertEqual("true", resolve_manual_pr(plan("workflow_dispatch", {}, "work"), [])["full"])
        selected = plan("workflow_dispatch", {"inputs": {"full_validation": "false"}}, "work")
        self.assertEqual("false", resolve_manual_pr(selected, [])["full"])

    def test_manual_fast_selection_cannot_bypass_a_ready_pr_gate(self):
        for readiness in ({"draft": False}, {}):
            with self.subTest(readiness=readiness):
                selected = plan("workflow_dispatch", {"inputs": {"full_validation": "false"}}, "work")
                pr = {"number": 1, "head": {"ref": "work"}, "base": {"ref": "feature/offline-translator"}, **readiness}
                result = resolve_manual_pr(selected, [pr])
                self.assertEqual("true", result["full"])
                self.assertEqual(("1", "work", "feature/offline-translator"),
                                 (result["pr_number"], result["pr_branch"], result["pr_base"]))

    def test_manual_draft_run_can_be_fast_or_explicitly_full(self):
        pr = {"number": 1, "draft": True, "head": {"ref": "work"}, "base": {"ref": "main"}}
        for choice in ("true", "false"):
            selected = plan("workflow_dispatch", {"inputs": {"full_validation": choice}}, "work")
            self.assertEqual(choice, resolve_manual_pr(selected, [pr])["full"])

    def test_ambiguous_manual_pr_selection_fails_instead_of_analyzing_the_wrong_pr(self):
        with self.assertRaisesRegex(ValueError, "More than one PR"):
            resolve_manual_pr(plan("workflow_dispatch", {}, "work"), [{}, {}])

    def test_manual_cli_writes_full_mode_for_a_ready_pr_even_if_fast_was_selected(self):
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory, "event.json")
            output = Path(directory, "output.txt")
            event.write_text(json.dumps({"inputs": {"full_validation": "false"}}), encoding="utf-8")
            pr = {"number": 1, "draft": False, "head": {"ref": "work"}, "base": {"ref": "main"}}
            env = {"GITHUB_EVENT_PATH": str(event), "GITHUB_OUTPUT": str(output),
                   "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF_NAME": "work",
                   "GITHUB_REPOSITORY": "NickCh11/PaperFlow", "GITHUB_REPOSITORY_OWNER": "NickCh11"}
            with patch.dict(os.environ, env), patch("subprocess.check_output", return_value=json.dumps([pr]).encode()) as request:
                runpy.run_path(str(Path(__file__).with_name("ci_plan.py")), run_name="__main__")
            values = dict(line.split("=", 1) for line in output.read_text(encoding="utf-8").splitlines())
            self.assertEqual("true", values["full"])
            self.assertEqual("1", values["pr_number"])
            self.assertEqual("main", values["pr_base"])
            self.assertIn("head=NickCh11:work", request.call_args.args[0])
