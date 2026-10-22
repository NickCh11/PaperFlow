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
    manifests = {kind: json.loads((ROOT / f"app/src/main/assets/languages/{kind}.json").read_text("utf-8")) for kind in ("ocr", "translation")}
    (folder / "upstream-manifests.json").write_text(json.dumps(manifests, indent=2, ensure_ascii=True) + "\n", encoding="utf-8", newline="\n")
    (folder / "MODEL-NOTICES.txt").write_text(
        "PaperFlow language model mirror\n\n"
        "All model bytes are redistributed unchanged. Content-addressed filenames do not modify the model.\n\n"
        "OCR: Tesseract tessdata_fast authors and contributors. Apache License 2.0.\n"
        "https://github.com/tesseract-ocr/tessdata_fast\n"
        "The pinned upstream commit, original filenames, sizes and Git blob hashes are in upstream-manifests.json.\n\n"
        "Translation: Mozilla / Firefox Translations contributors. Mozilla Public License 2.0.\n"
        "https://github.com/mozilla/firefox-translations-models\n"
        "Unmodified original model, vocabulary and shortlist files are included in this release.\n"
        "Original source URLs, versions, names, license attribution and SHA-256 hashes are in upstream-manifests.json.\n"
        "The release's model assets are the same downloadable source material under those licenses;\n"
        "they can be downloaded independently of the app and used or modified under the included terms.\n",
        encoding="utf-8", newline="\n")
    sums = []
    for file in sorted(folder.iterdir()):
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
