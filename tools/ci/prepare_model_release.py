"""Stage verified, unchanged language assets and redistribution notices for GitHub Releases."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from model_distribution import ROOT, check_tag
from prepare_models import fetch, verify


def prepare(output):
    tag, entries = check_tag()
    # Each tag gets its own directory: never upload stale assets from an older catalog.
    folder = output / tag
    folder.mkdir(parents=True, exist_ok=True)
    objects = output / "objects"
    objects.mkdir(parents=True, exist_ok=True)
    def stage(item):
        name, entry = item
        cached, target = objects/name, folder/name
        fetch(entry, cached)
        if target.is_file():
            try:
                verify(target.read_bytes(), entry)
                return
            except ValueError:
                target.unlink()
        try:
            os.link(cached, target)
        except OSError:
            shutil.copyfile(cached, target)
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(stage, entries.items()))
    licenses = ROOT / "app/src/main/assets/licenses"
    (folder / "OCR-Apache-2.0.txt").write_text((licenses / "Apache-2.0.txt").read_text("utf-8"), encoding="utf-8", newline="\n")
    (folder / "Translation-MPL-2.0.txt").write_text((licenses / "Mozilla-models-MPL-2.0.txt").read_text("utf-8"), encoding="utf-8", newline="\n")
    catalog=json.loads((ROOT / "translation/src/main/assets/index_v6.json").read_text("utf-8"))
    (folder / "upstream-manifests.json").write_text(json.dumps(catalog,indent=2,ensure_ascii=True)+"\n",encoding="utf-8",newline="\n")
    (folder / "MODEL-NOTICES.txt").write_text(
        "PaperFlow offline-translator language pack archive\n\n"
        "OCR: PaddleOCR and contributors, Apache-2.0. https://github.com/PaddlePaddle/PaddleOCR\n"
        "Document support: DocAligner lcnet050, DocsaidLab and contributors, Apache-2.0. https://github.com/DocsaidLab/DocAligner\n"
        "Translation: Firefox Translations models, Mozilla and contributors, MPL-2.0.\n"
        "https://github.com/mozilla/firefox-translations-models\n"
        "Catalog: https://github.com/DavidVentura/offline-translator\n"
        "Files are unchanged after decoding the transport gzip. Names identify catalog descriptors, not content hashes.\n"
        "The upstream v6 catalog declares transport sizes, not content digests. SHA256SUMS.txt records the decoded archive bytes.\n"
        "Original URLs, install paths and dependency/role information are in upstream-manifests.json.\n",
        encoding="utf-8",newline="\n")
    sums = []
    for file in sorted(folder.iterdir(), key=lambda file: file.name):
        if not file.is_file() or file.name == "SHA256SUMS.txt" or file.suffix == ".part":
            continue
        digest = hashlib.sha256()
        with file.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        sums.append(f"{digest.hexdigest()}  {file.name}")
    (folder / "SHA256SUMS.txt").write_text("\n".join(sums) + "\n", encoding="utf-8", newline="\n")
    return folder


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "build/model-releases")
    args = parser.parse_args()
    print(prepare(args.output))
