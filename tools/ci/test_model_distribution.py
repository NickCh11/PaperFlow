import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from model_distribution import ROOT, check_tag, inventory, mirror_url, repository
from check_model_links import check_url
from publish_model_release import validate_assets


class ModelDistributionTests(unittest.TestCase):
    def test_all_catalog_files_have_stable_distinct_assets_and_current_tag(self):
        tag, entries = check_tag()
        self.assertTrue(tag.startswith("language-models-"))
        self.assertGreater(len(entries), 250)
        self.assertEqual(len(entries), len(set(entries)))
        self.assertTrue(all("/" not in name and ".." not in name for name in entries))

    def test_catalog_change_requires_new_release_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for kind in ("ocr", "translation"):
                dest=root/f"app/src/main/assets/languages/{kind}.json"
                dest.parent.mkdir(parents=True,exist_ok=True)
                dest.write_bytes((ROOT/f"app/src/main/assets/languages/{kind}.json").read_bytes())
            before,_=inventory(root)
            file=root/"app/src/main/assets/languages/ocr.json"
            data=json.loads(file.read_text())
            file.write_bytes((json.dumps(data,indent=4)+"\n").replace("\n","\r\n").encode("utf-8"))
            self.assertEqual(before,inventory(root)[0],"Line endings/formatting must not change release tags")
            data["languages"][0]["url"]+="?mirror=changed";file.write_text(json.dumps(data))
            after,_=inventory(root)
            self.assertNotEqual(before,after)

    def test_repository_cannot_inject_paths_or_credentials(self):
        self.assertEqual("owner/models",repository("owner/models"))
        self.assertEqual("https://github.com/owner/models/releases/download/tag/file.bin",mirror_url("owner/models","tag","file.bin"))
        for invalid in ("", "https://github.com/a/b", "a/b/c", "a/b?token=x", "a/b\n", "a@b/c", "../models", "owner/.."):
            with self.assertRaises(ValueError):repository(invalid)

    def test_health_probe_checks_get_bytes_and_retries_failures(self):
        response=io.BytesIO(b"x");response.status=206
        with patch("check_model_links.urllib.request.urlopen",side_effect=[OSError("temporary"),response]) as call,patch("check_model_links.time.sleep"):
            check_url("https://example.org/model")
            self.assertEqual(2,call.call_count)
            self.assertEqual("bytes=0-0",call.call_args.args[0].headers["Range"])

    def test_publisher_rejects_corrupt_or_unexpected_remote_assets(self):
        expected={"model.bin":{"size":42,"digest":"sha256:verified"}}
        valid={"name":"model.bin","size":42,"digest":"sha256:verified","state":"uploaded"}
        self.assertEqual({"model.bin"},validate_assets(expected,[]))
        self.assertEqual(set(),validate_assets(expected,[valid]))
        for changed in (dict(valid,size=41),dict(valid,digest="sha256:wrong"),dict(valid,state="starter"),dict(valid,name="unknown.bin")):
            with self.assertRaises(ValueError):validate_assets(expected,[changed])
        with self.assertRaises(ValueError):validate_assets(expected,[valid,valid])

    def test_empty_success_response_is_not_a_healthy_model(self):
        responses=[]
        for _ in range(3):
            value=io.BytesIO(b"");value.status=200;responses.append(value)
        with patch("check_model_links.urllib.request.urlopen",side_effect=responses),patch("check_model_links.time.sleep"):
            with self.assertRaises(ValueError):check_url("https://example.org/empty")


if __name__ == "__main__":
    unittest.main()
