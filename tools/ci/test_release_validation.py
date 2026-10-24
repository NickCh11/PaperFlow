import unittest
from release_android import require_full_validation


class ReleaseValidationTest(unittest.TestCase):
    def jobs(self):
        names = [f"Build {abi} debug and release" for abi in ("arm64-v8a", "armeabi-v7a", "all")]
        names += ["Device tests API 29", "Device tests API 37.0", "Unit tests, lint and translations", "sonar / SonarQube analysis"]
        names += [f"language-runtime / native ({abi})" for abi in ("arm64-v8a", "armeabi-v7a", "x86_64")]
        return [dict(name=name, conclusion="success") for name in names]

    def test_full_success_is_accepted(self):
        require_full_validation(self.jobs())

    def test_fast_green_ci_does_not_authorize_a_release(self):
        with self.assertRaises(RuntimeError):
            require_full_validation([dict(name="Unit tests, lint and translations", conclusion="success"), dict(name="sonar / SonarQube analysis", conclusion="success")])

    def test_missing_skipped_failed_or_duplicate_acceptance_cannot_pass(self):
        for i in range(len(self.jobs())):
            for conclusion in ("skipped", "failure", None):
                jobs = self.jobs(); jobs[i]['conclusion'] = conclusion
                with self.assertRaises(RuntimeError):
                    require_full_validation(jobs)
            jobs = self.jobs(); jobs.append(jobs[i].copy())
            with self.assertRaises(RuntimeError):
                require_full_validation(jobs)
