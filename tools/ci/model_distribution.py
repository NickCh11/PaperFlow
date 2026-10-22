"""Reproducible model-release names and a validated, deduplicated asset inventory."""
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
TAG_RESOURCE = ROOT / "app/src/main/res/values/model_distribution.xml"


def inventory(root=ROOT):
    entries = {}
    digest = hashlib.sha256()
    for kind in ("ocr", "translation"):
        raw = (root / f"app/src/main/assets/languages/{kind}.json").read_bytes()
        catalog = json.loads(raw)
        canonical = json.dumps(catalog, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
        digest.update(kind.encode() + b"\0" + canonical + b"\0")
        for language in catalog["languages"]:
            for item in language.get("files", [language]):
                git_blob = "blobSha1" in item
                fingerprint = item["blobSha1" if git_blob else "sha256"]
                if not re.fullmatch(r"[0-9a-f]{40}" if git_blob else r"[0-9a-f]{64}", fingerprint):
                    raise ValueError("Invalid model digest")
                if not isinstance(item["size"], int) or item["size"] <= 0:
                    raise ValueError("Invalid model size")
                url = urlsplit(item["url"])
                if url.scheme != "https" or not url.hostname or url.username or url.password:
                    raise ValueError("Models must use public HTTPS URLs")
                name = ("blobsha1-" if git_blob else "sha256-") + fingerprint + (".traineddata" if git_blob else ".bin")
                if name in entries and entries[name]["size"] != item["size"]:
                    raise ValueError("Conflicting model sizes")
                entries.setdefault(name, dict(item))
    return "language-models-" + digest.hexdigest()[:20], entries


def check_tag(root=ROOT):
    import xml.etree.ElementTree as ET
    tag, entries = inventory(root)
    resource = root / "app/src/main/res/values/model_distribution.xml"
    actual = ET.parse(resource).getroot().find("string[@name='model_release_tag']")
    if actual is None or actual.text != tag or actual.get("translatable") != "false":
        raise ValueError("Model release tag is stale; run model_distribution.py --write-tag")
    return tag, entries


def repository(value):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]+", value) or value.rsplit("/",1)[-1] in (".", "..") :
        raise ValueError("Expected public GitHub owner/repository")
    return value


def mirror_url(repo, tag, asset):
    return f"https://github.com/{repository(repo)}/releases/download/{tag}/{asset}"


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--write-tag", action="store_true")
    args = parser.parse_args()
    tag, entries = inventory()
    if args.write_tag:
        TAG_RESOURCE.write_text('<resources>\n    <string name="model_release_tag" translatable="false">' + tag + '</string>\n</resources>\n', encoding="utf-8")
    else:
        check_tag()
    print(f"{tag}: {len(entries)} unique assets, {sum(e['size'] for e in entries.values()):,} bytes")
