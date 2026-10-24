import unittest
from ci_plan import plan


class CiPlanTest(unittest.TestCase):
    def test_normal_pr_updates_remain_fast_even_if_full_label_is_still_attached(self):
        for action in ("opened", "synchronize", "reopened", "ready_for_review"):
            self.assertEqual("false", plan("pull_request", {"action": action, "labels": [{"name": "full-validation"}]}, "feature")['full'])

    def test_full_validation_requires_its_explicit_label_event(self):
        self.assertEqual("true", plan("pull_request", {"action": "labeled", "label": {"name": "full-validation"}}, "feature")['full'])
        self.assertEqual("false", plan("pull_request", {"action": "labeled", "label": {"name": "documentation"}}, "feature")['full'])

    def test_main_and_merge_queue_still_require_full_acceptance(self):
        self.assertEqual("true", plan("push", {}, "main")['full'])
        self.assertEqual("true", plan("merge_group", {}, "queue")['full'])
        self.assertEqual("false", plan("push", {}, "feature/offline-translator")['full'])

    def test_manual_choice_and_pr_identity_are_preserved(self):
        self.assertEqual("true", plan("workflow_dispatch", {}, "feature")['full'])
        self.assertEqual("false", plan("workflow_dispatch", {"inputs": {"full_validation": "false"}}, "feature")['full'])
        result = plan("pull_request", {"pull_request": {"number": 1, "head": {"ref": "work"}, "base": {"ref": "feature/offline-translator"}}}, "merge")
        self.assertEqual(("1", "work", "feature/offline-translator"), (result['pr_number'], result['pr_branch'], result['pr_base']))
