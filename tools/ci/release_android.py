"""Publish signed, versioned APKs from a successful CI run of the exact commit."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / "build/release-state.json"
DIST = ROOT / "build/release-dist"
ABIS = ("arm64-v8a", "armeabi-v7a", "all")


def run(*args, **kwargs):
    return subprocess.check_output(args, text=True, cwd=ROOT, **kwargs).strip()


def api(path):
    return json.loads(run("gh", "api", f"repos/{os.environ['GH_REPO']}/{path}"))


def version():
    values = [line.split("=", 1)[1].strip() for line in
              (ROOT / "version.properties").read_text("utf-8").splitlines()
              if line.startswith("versionName=")]
    if len(values) != 1 or not re.fullmatch(r"(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})", values[0]):
        raise ValueError("version.properties must contain one major.minor.patch versionName")
    major, minor, patch = map(int, values[0].split("."))
    code = major * 1000000 + minor * 1000 + patch
    if not 1 <= code <= 2100000000:
        raise ValueError("Version exceeds Android's versionCode range")
    return values[0], code


def release_for(tag):
    # Distinguish an absent release from permission/network errors (which must fail).
    releases = json.loads(run("gh", "api", "--paginate", "--slurp",
                             f"repos/{os.environ['GH_REPO']}/releases?per_page=100"))
    return next((r for page in releases for r in page if r["tag_name"] == tag), None)


def require_full_validation(jobs):
    required = [f"Build {abi} debug and release" for abi in ABIS]
    required += ["Device tests API 29", "Device tests API 37.0", "Unit tests, lint and translations", "SonarQube analysis"]
    required += [f"native ({abi})" for abi in ("arm64-v8a", "armeabi-v7a", "x86_64")]
    for name in required:
        matches = [job for job in jobs if job.get("name") == name or job.get("name", "").endswith(" / " + name)]
        if len(matches) != 1 or matches[0].get("conclusion") != "success":
            raise RuntimeError(f"Full release validation is missing or unsuccessful: {name}")


def prepare():
    name, code = version()
    tag = "v" + name
    sha = os.environ["GITHUB_SHA"]
    if os.environ["GITHUB_REF"].startswith("refs/tags/") and os.environ["GITHUB_REF"] != "refs/tags/" + tag:
        raise ValueError("Git tag must match version.properties")
    subprocess.run(["git", "merge-base", "--is-ancestor", sha, "origin/main"], cwd=ROOT, check=True)
    existing_tag = subprocess.run(["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"],
                                  cwd=ROOT, capture_output=True, text=True)
    if existing_tag.returncode == 0 and existing_tag.stdout.strip() != sha:
        raise ValueError("This version tag already points to another source commit")
    existing = release_for(tag)
    if existing and not existing["draft"]:
        raise ValueError("This version is already published; bump version.properties")
    # Require a strictly increasing Android version for normal (non-draft) releases.
    latest = json.loads(run("gh", "api", "--paginate", "--slurp",
                           f"repos/{os.environ['GH_REPO']}/releases?per_page=100"))
    for page in latest:
        for release in page:
            match = re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)", release["tag_name"])
            if match and not release["draft"]:
                old = tuple(map(int, match.groups()))
                if old >= tuple(map(int, name.split("."))):
                    raise ValueError("Release version must be newer than every published version")
    runs = api(f"actions/workflows/android-ci.yml/runs?head_sha={sha}&per_page=100")["workflow_runs"]
    runs = [r for r in runs if r["head_sha"] == sha and r["head_branch"] == "main" and r["status"] == "completed" and r["conclusion"] == "success" and r.get("event") in ("push", "workflow_dispatch")]
    for ci in sorted(runs, key=lambda r: r["run_number"], reverse=True):
        report = api(f"actions/runs/{ci['id']}/jobs?per_page=100")
        if report.get("total_count", 0) > len(report["jobs"]):
            raise RuntimeError("Incomplete job list; full release validation cannot be verified")
        try:
            require_full_validation(report["jobs"])
        except RuntimeError:
            continue
        STATE.parent.mkdir(parents=True, exist_ok=True)
        STATE.write_text(json.dumps({"version": name, "code": code, "tag": tag,
                                     "sha": sha, "run": ci["id"]}), encoding="utf-8")
        print(f"Release {tag}: using successful full CI run {ci['id']}", flush=True)
        return
    raise RuntimeError("Complete full Android validation on this exact main commit before publishing. A release runner will not wait and consume minutes.")


def sign():
    state = json.loads(STATE.read_text("utf-8"))
    required = ("PAPERFLOW_KEYSTORE_BASE64", "PAPERFLOW_STORE_PASSWORD", "PAPERFLOW_KEY_ALIAS", "PAPERFLOW_KEY_PASSWORD")
    if any(not os.environ.get(key) for key in required):
        raise RuntimeError("Configure all four PAPERFLOW signing secrets before publishing")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    DIST.mkdir(parents=True, exist_ok=False)
    certificates = set()
    with tempfile.TemporaryDirectory(prefix="paperflow-signing-", dir=os.environ["RUNNER_TEMP"]) as folder:
        keystore = Path(folder) / "release.p12"
        keystore.write_bytes(base64.b64decode(os.environ["PAPERFLOW_KEYSTORE_BASE64"], validate=True))
        keystore.chmod(0o600)
        for abi in ABIS:
            destination = ROOT / "build/release-inputs" / abi
            subprocess.run(["gh", "run", "download", str(state["run"]), "--name", f"apks-{abi}",
                            "--dir", str(destination)], cwd=ROOT, check=True)
            inputs = list(destination.rglob("app-release-unsigned.apk"))
            if len(inputs) != 1:
                raise ValueError(f"Expected exactly one unsigned release APK for {abi}")
            apk = inputs[0]
            subprocess.run(["python3", "tools/ci/check_apk.py", str(apk), abi], cwd=ROOT, check=True)
            badging = run(str(tools / "aapt"), "dump", "badging", str(apk))
            if f"versionCode='{state['code']}'" not in badging.splitlines()[0] or f"versionName='{state['version']}'" not in badging.splitlines()[0]:
                raise ValueError(f"CI APK version does not match release {state['tag']}")
            output = DIST / f"PaperFlow-{state['version']}-{'universal' if abi == 'all' else abi}.apk"
            subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
            subprocess.run([str(tools / "apksigner"), "sign", "--ks", str(keystore), "--ks-key-alias",
                            os.environ["PAPERFLOW_KEY_ALIAS"], "--ks-pass", "env:PAPERFLOW_STORE_PASSWORD",
                            "--key-pass", "env:PAPERFLOW_KEY_PASSWORD", "--out", str(output), str(apk)], check=True)
            verified = run(str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(output))
            print(verified)
            digest = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", verified)
            if not digest:
                raise ValueError("Could not verify release signing certificate")
            certificates.add(digest[1].lower())
    if len(certificates) != 1:
        raise ValueError("All release architectures must use the same signing certificate")
    files = sorted(DIST.glob("*.apk"))
    sums = []
    for file in files:
        with file.open("rb") as stream:
            sums.append(f"{hashlib.file_digest(stream, 'sha256').hexdigest()}  {file.name}\n")
    (DIST / "SHA256SUMS.txt").write_text("".join(sums), encoding="utf-8")
    state["certificate"] = certificates.pop()
    STATE.write_text(json.dumps(state), encoding="utf-8")


def publish():
    state = json.loads(STATE.read_text("utf-8"))
    if len(list(DIST.glob("*.apk"))) != 3 or not state.get("certificate"):
        raise RuntimeError("All three signed, verified release APKs are required")
    tag = state["tag"]
    # No tag is moved or overwritten. The workflow may also be dispatched on main.
    refs = api(f"git/matching-refs/tags/{tag}")
    exact = next((r for r in refs if r["ref"] == "refs/tags/" + tag), None)
    if exact is None:
        subprocess.run(["gh", "api", f"repos/{os.environ['GH_REPO']}/git/refs", "-f", f"ref=refs/tags/{tag}",
                        "-f", f"sha={state['sha']}"], check=True)
    else:
        obj = exact["object"]
        while obj["type"] == "tag":
            obj = api(f"git/tags/{obj['sha']}")["object"]
        if obj["sha"] != state["sha"]:
            raise ValueError("Version tag changed during release preparation")
    existing = release_for(tag)
    if existing and not existing["draft"]:
        raise ValueError("Release is already published")
    notes = ROOT / "build/release-notes.md"
    notes.write_text(f"## PaperFlow {state['version']}\n\n"
                     "Signed Android APKs (Android 8.0+):\n\n"
                     "- **arm64-v8a**: ARMv8 / most modern phones\n"
                     "- **armeabi-v7a**: ARMv7 / older 32-bit devices\n"
                     "- **universal**: all supported architectures\n\n"
                     f"Built and tested from `{state['sha']}`. "
                     f"[CI reports](https://github.com/{os.environ['GH_REPO']}/actions/runs/{state['run']}).\n\n"
                     "`SHA256SUMS.txt` contains the APK checksums.\n\n"
                     f"Signing certificate SHA-256: `{state['certificate']}`\n", encoding="utf-8")
    if existing is None:
        subprocess.run(["gh", "release", "create", tag, "--verify-tag", "--draft", "--title",
                        f"PaperFlow {state['version']}", "--notes-file", str(notes)], check=True)
    assets = [str(p) for p in sorted(DIST.iterdir()) if p.suffix == ".apk" or p.name == "SHA256SUMS.txt"]
    subprocess.run(["gh", "release", "upload", tag, *assets, "--clobber"], check=True)
    subprocess.run(["gh", "release", "edit", tag, "--draft=false", "--latest", "--notes-file", str(notes)], check=True)
    print(f"Published https://github.com/{os.environ['GH_REPO']}/releases/tag/{tag}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("version", "prepare", "sign", "publish"))
    args = parser.parse_args()
    if args.command == "version":
        name, code = version()
        print(f"PaperFlow {name} (Android versionCode {code})")
    else:
        {"prepare": prepare, "sign": sign, "publish": publish}[args.command]()
