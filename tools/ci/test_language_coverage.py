"""Every downloadable language must have an explicit device-test fixture."""
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]

def validate(catalog, samples):
    languages=set(catalog["languages"])
    if languages != set(samples):
        raise ValueError(f"Language test coverage: missing={sorted(languages-set(samples))}, obsolete={sorted(set(samples)-languages)}")
    for code, sample in samples.items():
        allowed=[""] if code in ("bs","hr") else ["nb","no"] if code in ("nb","no") else [code]
        if len(sample.get("text", "").strip())<40 or sample.get("expected")!=allowed or (code in ("bs","hr","nb","no") and not sample.get("reason","").strip()):
            raise ValueError(f"Missing meaningful detection ground truth for {code}")

class LanguageCoverageTest(unittest.TestCase):
    def test_every_download_language_has_a_detection_fixture(self):
        catalog=json.loads((ROOT/"translation/src/main/assets/index_v6.json").read_text("utf-8"))
        samples=json.loads((ROOT/"app/src/androidTest/assets/language-detection-samples.json").read_text("utf-8"))
        validate(catalog,samples)

    def test_adding_a_download_language_without_a_sample_fails(self):
        with self.assertRaisesRegex(ValueError,"missing=.*new"):
            validate({"languages":{"en":{},"new":{}}},{"en":{"text":"A sufficiently long English sample for this regression.","expected":["en"]}})

    def test_new_language_cannot_bypass_detection_with_an_empty_expectation(self):
        with self.assertRaisesRegex(ValueError,"ground truth"):
            validate({"languages":{"new":{}}},{"new":{"text":"A sufficiently long sample for this regression case.","expected":[""],"reason":"skip"}})

if __name__=="__main__":unittest.main()
