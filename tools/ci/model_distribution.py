"""Reproducible model-release names and a validated, deduplicated asset inventory."""
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import urlsplit
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))

ROOT = Path(__file__).resolve().parents[2]
TAG_RESOURCE = ROOT / "app/src/main/res/values/model_distribution.xml"


def inventory(root=ROOT):
    from offline_catalog import CATALOG, active_files, core_packs
    catalog = json.loads((root / CATALOG).read_text("utf-8"))
    canonical = json.dumps(catalog,sort_keys=True,separators=(",",":"),ensure_ascii=True).encode()
    entries = {}
    for path, item in active_files(catalog,core_packs(catalog,catalog["languages"])).items():
        url = urlsplit(item["url"])
        if url.scheme != "https" or not url.hostname or url.username or url.password:
            raise ValueError("Models must use public HTTPS URLs")
        if not isinstance(item["sizeBytes"],int) or item["sizeBytes"]<=0:
            raise ValueError("Invalid model size")
        descriptor = json.dumps(item,sort_keys=True,separators=(",",":")).encode()
        name = "asset-" + hashlib.sha256(descriptor).hexdigest() + ".bin"
        entries[name] = dict(item,size=item["sizeBytes"],gzip=url.path.endswith(".gz"))
    return "language-models-"+hashlib.sha256(canonical).hexdigest()[:20],entries



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
