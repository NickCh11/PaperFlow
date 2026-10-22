"""Publish or resume a model release, without replacing any existing asset bytes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import quote
from urllib.request import Request, urlopen
sys.path.insert(0, str(Path(__file__).resolve().parent))
from model_distribution import check_tag, repository


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)


def fingerprint(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def validate_assets(expected, actual):
    names = {asset["name"] for asset in actual}
    if len(names) != len(actual) or not names.issubset(expected):
        raise ValueError("Release has unexpected or duplicate assets; nothing will be overwritten")
    for asset in actual:
        item = expected[asset["name"]]
        if asset.get("state") != "uploaded" or asset.get("size") != item["size"] or asset.get("digest") != item["digest"]:
            raise ValueError(f"Release asset does not match verified local bytes: {asset['name']}")
    return set(expected) - names


def publish(repo, folder, commit, private_archive=False):
    repo = repository(repo)
    tag, entries = check_tag()
    if folder.name != tag:
        raise ValueError("Staged folder does not match current catalog tag")
    metadata = {"OCR-Apache-2.0.txt", "Translation-MPL-2.0.txt", "upstream-manifests.json", "MODEL-NOTICES.txt", "SHA256SUMS.txt"}
    required = set(entries) | metadata
    if {file.name for file in folder.iterdir()} != required:
        raise ValueError("Stage must contain exactly the verified models and redistribution metadata")
    expected = {name: dict(size=(folder/name).stat().st_size, digest=fingerprint(folder/name)) for name in required}
    # Verify the model pins again at the publication boundary, even if staging was resumed.
    from prepare_models import verify
    for name, entry in entries.items():
        verify((folder/name).read_bytes(), entry)
    remote = json.loads(gh("api", f"repos/{repo}"))
    if private_archive:
        if remote.get("private") is not True:
            raise ValueError("Private model archives must remain in a private repository")
    elif remote.get("private") or remote.get("visibility") != "public":
        raise ValueError("App language mirrors must be in a public repository")
    found = json.loads(gh("api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"))
    candidates = [item for page in found for item in page if item["tag_name"] == tag]
    release = max(candidates, key=lambda item: len(item.get("assets", [])), default=None)
    if release is None:
        # Draft releases are not reliably returned by the tag lookup endpoint.
        # Keep the ID returned by creation instead of looking the draft up by tag.
        release = json.loads(gh("api", "--method", "POST", f"repos/{repo}/releases",
            "-f", f"tag_name={tag}", "-f", f"target_commitish={commit}",
            "-f", f"name={tag}", "-F", "draft=true",
            "-f", "body=" + (folder/"MODEL-NOTICES.txt").read_text(encoding="utf-8")))
    def assets():
        pages = json.loads(gh("api", "--paginate", "--slurp", f"repos/{repo}/releases/{release['id']}/assets?per_page=100"))
        return [item for page in pages for item in page]
    missing = validate_assets(expected, assets())
    if not release["draft"] and missing:
        raise ValueError("Published release is incomplete; published assets are never modified")
    if missing:
        # Address the exact release ID: tag-based CLI uploads can select another
        # draft when multiple drafts share an unpublished tag.
        token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or gh("auth", "token").strip()
        for name in sorted(missing):
            request = Request(
                f"https://uploads.github.com/repos/{repo}/releases/{release['id']}/assets?name={quote(name, safe='')}",
                data=(folder/name).read_bytes(), method="POST",
                headers={"Authorization": "Bearer " + token,
                    "Content-Type": "application/octet-stream", "Accept": "application/vnd.github+json",
                    "User-Agent": "PaperFlow-Model-Publisher"})
            with urlopen(request, timeout=180) as response:
                asset = json.load(response)
            validate_assets(expected, [asset])
    if validate_assets(expected, assets()):
        raise ValueError("Not all assets were uploaded")
    if release["draft"]:
        gh("api", "--method", "PATCH", f"repos/{repo}/releases/{release['id']}", "-F", "draft=false", "-f", "make_latest=false")
    kind = "private archive" if private_archive else "public mirror"
    print(f"Verified {kind} release: https://github.com/{repo}/releases/tag/{tag}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--folder", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--private-archive", action="store_true", help="Require a private repository; assets will not be accessible to anonymous app downloads")
    args = parser.parse_args()
    publish(args.repository, args.folder, args.commit, private_archive=args.private_archive)
