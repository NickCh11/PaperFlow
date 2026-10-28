"""Build and attach signed APKs only to an already published, validated release."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / "build/release-state.json"
DIST = ROOT / "build/release-dist"
ABIS = ("arm64-v8a", "armeabi-v7a", "all")
VERSION = r"(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})"
MANIFEST = "release-manifest.json"


def run(*args, **kwargs):
    return subprocess.check_output(args, text=True, cwd=ROOT, **kwargs).strip()


def api(path):
    return json.loads(run("gh", "api", f"repos/{os.environ['GH_REPO']}/{path}"))


def pages(path):
    return json.loads(run("gh", "api", "--paginate", "--slurp", f"repos/{os.environ['GH_REPO']}/{path}"))


def parse_version(name):
    if not re.fullmatch(VERSION, name):
        raise ValueError("Version must be major.minor.patch (no leading zeroes; minor/patch <= 999)")
    major, minor, patch = map(int, name.split("."))
    code = major * 1000000 + minor * 1000 + patch
    if not 1 <= code <= 2100000000:
        raise ValueError("Version exceeds Android's versionCode range")
    return name, code


def version():
    values = [line.split("=", 1)[1].strip() for line in
              (ROOT / "version.properties").read_text("utf-8").splitlines()
              if line.startswith("versionName=")]
    if len(values) != 1:
        raise ValueError("version.properties must contain one versionName")
    return parse_version(values[0])


def event_state():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text("utf-8"))
    release = event.get("release", {})
    if os.environ.get("GITHUB_EVENT_NAME") != "release" or event.get("action") != "published":
        raise ValueError("Production APKs require a release: published event")
    if release.get("draft", True) or release.get("prerelease", True):
        raise ValueError("Only published stable releases are supported")
    tag = release.get("tag_name", "")
    if not tag.startswith("v"):
        raise ValueError("Release tag must be vMAJOR.MINOR.PATCH")
    name, code = parse_version(tag[1:])
    sha = os.environ["GITHUB_SHA"]
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or os.environ.get("GITHUB_REF") != "refs/tags/" + tag:
        raise ValueError("Release event commit or tag ref is invalid")
    if type(release.get("id")) is not int or release["id"] <= 0:
        raise ValueError("Release event has no valid release ID")
    return dict(version=name, code=code, tag=tag, sha=sha, release_id=release["id"])


def remote_tag_sha(tag):
    obj = api("git/ref/tags/" + quote(tag, safe=""))["object"]
    for _ in range(10):
        if obj["type"] == "commit":
            return obj["sha"]
        if obj["type"] != "tag":
            break
        obj = api("git/tags/" + obj["sha"])["object"]
    raise ValueError("Release tag does not resolve to a commit")


def validate_source(state):
    if run("git", "rev-parse", "HEAD") != state["sha"] or remote_tag_sha(state["tag"]) != state["sha"]:
        raise ValueError("Release tag, event and checkout must identify the same commit")
    subprocess.run(["git", "merge-base", "--is-ancestor", state["sha"], "origin/main"], cwd=ROOT, check=True)
    release = api(f"releases/{state['release_id']}")
    if release["tag_name"] != state["tag"] or release["draft"] or release["prerelease"]:
        raise ValueError("Release changed or is no longer a published stable release")
    if release.get("immutable", False):
        raise ValueError("This release is immutable: GitHub prohibits attaching assets after publication")


def require_full_validation(jobs):
    required = ["Android compilation and native checks", "PR gate", "Device tests API 29",
                "Device tests API 37.0", "Unit tests, lint and translations", "SonarQube analysis"]
    required += [f"native ({abi})" for abi in ("arm64-v8a", "armeabi-v7a", "x86_64")]
    for name in required:
        matches = [job for job in jobs if job.get("name") == name or job.get("name", "").endswith(" / " + name)]
        if len(matches) != 1 or matches[0].get("conclusion") != "success":
            raise RuntimeError(f"Full release validation is missing or unsuccessful: {name}")


def apk_names(state):
    return {f"PaperFlow-{state['version']}-{'universal' if abi == 'all' else abi}.apk" for abi in ABIS}


def expected_names(state):
    return apk_names(state) | {"SHA256SUMS.txt", MANIFEST}


def release_assets(state):
    return [asset for page in pages(f"releases/{state['release_id']}/assets?per_page=100") for asset in page]


def validate_manifest(manifest, state):
    for key in ("sha", "tag", "version", "code", "release_id"):
        if manifest.get(key) != state[key]:
            raise ValueError("Release bundle does not match the published release")
    if not re.fullmatch(r"[0-9a-f]{64}", manifest.get("certificate", "")):
        raise ValueError("Release bundle has no verified signing certificate")
    if type(manifest.get("run")) is not int or manifest["run"] <= 0:
        raise ValueError("Release bundle has no successful CI provenance")
    files = manifest.get("files", {})
    if set(files) != expected_names(state) - {MANIFEST}:
        raise ValueError("Release bundle is incomplete or contains unexpected files")
    for record in files.values():
        if not re.fullmatch(r"[0-9a-f]{64}", record.get("sha256", "")) or type(record.get("size")) is not int or record["size"] <= 0:
            raise ValueError("Release bundle contains an invalid file digest or size")


def already_complete(state, assets):
    relevant = [asset for asset in assets if asset["name"] in expected_names(state)]
    if not relevant:
        return False
    if len(relevant) != len(expected_names(state)) or {a["name"] for a in relevant} != expected_names(state):
        raise ValueError("Partial or conflicting release assets exist; inspect them before retrying. Nothing will be overwritten")
    manifest_asset = next(a for a in relevant if a["name"] == MANIFEST)
    manifest = json.loads(run("gh", "api", f"repos/{os.environ['GH_REPO']}/releases/assets/{manifest_asset['id']}",
                              "-H", "Accept: application/octet-stream"))
    validate_manifest(manifest, state)
    for asset in relevant:
        if asset["name"] == MANIFEST:
            continue
        record = manifest["files"][asset["name"]]
        if asset.get("state") != "uploaded" or asset.get("digest") != "sha256:" + record["sha256"] or asset["size"] != record["size"]:
            raise ValueError("Existing release asset does not match its manifest")
    return True


def require_newer_version(state):
    baseline, baseline_code = version()
    if state["code"] <= baseline_code:
        raise ValueError("Release must be newer than the checked-in version baseline; current baseline is " + baseline)
    for page in pages("releases?per_page=100"):
        for release in page:
            if release["id"] == state["release_id"] or release["draft"] or not re.fullmatch("v" + VERSION, release["tag_name"]):
                continue
            if parse_version(release["tag_name"][1:])[1] >= state["code"]:
                raise ValueError("Release versionCode must increase beyond every other published version")


def validated_ci_run(sha):
    runs = [r for page in pages(f"actions/workflows/android-ci.yml/runs?head_sha={sha}&per_page=100") for r in page["workflow_runs"]]
    for ci in sorted(runs, key=lambda r: r["run_number"], reverse=True):
        if not (ci["head_sha"] == sha and ci["head_branch"] == "main" and ci["status"] == "completed" and ci["conclusion"] == "success" and ci.get("event") in ("push", "workflow_dispatch")):
            continue
        jobs = [j for page in pages(f"actions/runs/{ci['id']}/jobs?per_page=100") for j in page["jobs"]]
        try:
            require_full_validation(jobs)
        except RuntimeError:
            continue
        return ci["id"]
    raise RuntimeError("Complete full Android CI on this exact main commit before publishing; no APK build was started")


def prepare():
    state = event_state()
    validate_source(state)
    complete = already_complete(state, release_assets(state))
    if not complete:
        require_newer_version(state)
        state["run"] = validated_ci_run(state["sha"])
    state["complete"] = complete
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(state), encoding="utf-8")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"sha={state['sha']}\ncomplete={str(complete).lower()}\n")
    print("Release already complete; no rebuild or upload" if complete else f"Validated {state['tag']} at {state['sha']}")


def build():
    state = json.loads(STATE.read_text("utf-8"))
    if state.get("complete"):
        raise ValueError("Release assets are already complete")
    event = event_state()
    if any(state.get(k) != v for k, v in event.items()):
        raise ValueError("Build state does not match the event")
    validate_source(state)
    for abi in ABIS:
        command = ["bash", "gradlew", ":app:assembleRelease", "-PpaperflowReleaseVersion=" + state["version"], "--stacktrace"]
        if abi != "all":
            command.append("-PpaperflowAbi=" + abi)
        subprocess.run(command, cwd=ROOT, check=True)
        subprocess.run(["python3", "tools/ci/check_android_manifest.py", "app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"], cwd=ROOT, check=True)
        destination = ROOT / "build/release-inputs" / abi
        destination.mkdir(parents=True, exist_ok=False)
        shutil.copy2(ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk", destination)
        mapping = ROOT / "build/release-mappings" / abi
        mapping.mkdir(parents=True, exist_ok=False)
        shutil.copy2(ROOT / "app/build/outputs/mapping/release/mapping.txt", mapping)


def file_record(path):
    with path.open("rb") as source:
        return dict(sha256=hashlib.file_digest(source, "sha256").hexdigest(), size=path.stat().st_size)


def validate_badging(badging, state):
    first = badging.splitlines()[0]
    for field, value in (("name", "com.example.myapplication"), ("versionCode", state["code"]), ("versionName", state["version"])):
        if f"{field}='{value}'" not in first:
            raise ValueError("APK application ID or version does not match the release")


def sign():
    state = json.loads(STATE.read_text("utf-8"))
    event = event_state()
    if any(state.get(k) != v for k, v in event.items()) or state.get("complete"):
        raise ValueError("Signing state does not match the event")
    required = ("PAPERFLOW_KEYSTORE_BASE64", "PAPERFLOW_STORE_PASSWORD", "PAPERFLOW_KEY_ALIAS", "PAPERFLOW_KEY_PASSWORD")
    if any(not os.environ.get(key) for key in required):
        raise RuntimeError("Configure all four PAPERFLOW signing secrets; no production key will be generated")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    DIST.mkdir(parents=True, exist_ok=False)
    certificates = set()
    with tempfile.TemporaryDirectory(prefix="paperflow-signing-", dir=os.environ["RUNNER_TEMP"]) as folder:
        keystore = Path(folder) / "release.p12"
        keystore.write_bytes(base64.b64decode(os.environ["PAPERFLOW_KEYSTORE_BASE64"], validate=True))
        keystore.chmod(0o600)
        for abi in ABIS:
            apk = ROOT / "build/release-inputs" / abi / "app-release-unsigned.apk"
            subprocess.run(["python3", "tools/ci/check_apk.py", str(apk), abi], cwd=ROOT, check=True)
            validate_badging(run(str(tools / "aapt"), "dump", "badging", str(apk)), state)
            output = DIST / f"PaperFlow-{state['version']}-{'universal' if abi == 'all' else abi}.apk"
            subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
            subprocess.run([str(tools / "apksigner"), "sign", "--ks", str(keystore), "--ks-key-alias",
                            os.environ["PAPERFLOW_KEY_ALIAS"], "--ks-pass", "env:PAPERFLOW_STORE_PASSWORD",
                            "--key-pass", "env:PAPERFLOW_KEY_PASSWORD", "--v4-signing-enabled", "false",
                            "--out", str(output), str(apk)], check=True)
            verified = run(str(tools / "apksigner"), "verify", "--min-sdk-version", "26", "--verbose", "--print-certs", str(output))
            digest = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})", verified)
            if not digest:
                raise ValueError("Could not verify release signing certificate")
            certificates.add(digest[1].lower())
    if len(certificates) != 1:
        raise ValueError("All release architectures must use the same signing certificate")
    state["certificate"] = certificates.pop()
    state["files"] = {p.name: file_record(p) for p in sorted(DIST.glob("*.apk"))}
    sums = "".join(f"{record['sha256']}  {name}\n" for name, record in state["files"].items())
    (DIST / "SHA256SUMS.txt").write_text(sums, encoding="utf-8")
    state["files"]["SHA256SUMS.txt"] = file_record(DIST / "SHA256SUMS.txt")
    state.pop("complete", None)  # Preflight upload status is not bundle metadata.
    validate_manifest(state, event)
    (DIST / MANIFEST).write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    print("All three APKs signed and verified; certificate SHA-256: " + state["certificate"])


def publish():
    state = event_state()
    manifest = json.loads((DIST / MANIFEST).read_text("utf-8"))
    validate_manifest(manifest, state)
    if {p.name for p in DIST.iterdir()} != expected_names(state):
        raise ValueError("Only the complete verified release bundle can be uploaded")
    for name, record in manifest["files"].items():
        if (DIST / name).is_symlink() or file_record(DIST / name) != record:
            raise ValueError("Release bundle file was changed after signing: " + name)
    validate_source(state)
    if already_complete(state, release_assets(state)):
        print("Release already complete; nothing uploaded")
        return
    require_newer_version(state)
    uploaded = []
    try:
        # The manifest is the final completion marker. Never overwrite an asset.
        for name in sorted(expected_names(state) - {MANIFEST}) + [MANIFEST]:
            validate_source(state)
            endpoint = f"https://uploads.github.com/repos/{os.environ['GH_REPO']}/releases/{state['release_id']}/assets?name={quote(name, safe='')}"
            asset = json.loads(run("gh", "api", "--method", "POST", endpoint, "-H", "Content-Type: application/octet-stream", "--input", str(DIST / name)))
            uploaded.append(asset["id"])
            if asset.get("state") != "uploaded" or asset.get("digest") != "sha256:" + file_record(DIST / name)["sha256"]:
                raise ValueError("Uploaded asset failed integrity verification")
        validate_source(state)
        if not already_complete(state, release_assets(state)):
            raise ValueError("Release upload did not complete")
    except Exception:
        # Only delete asset IDs acknowledged as created by this execution. An
        # ambiguous network failure may need maintainer inspection before retry.
        for asset_id in reversed(uploaded):
            try:
                run("gh", "api", "--method", "DELETE", f"repos/{os.environ['GH_REPO']}/releases/assets/{asset_id}")
            except subprocess.CalledProcessError:
                print(f"::warning::Could not clean up asset {asset_id}; inspect this release before retrying")
        raise
    print(f"Attached verified APKs to https://github.com/{os.environ['GH_REPO']}/releases/tag/{state['tag']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("version", "prepare", "build", "sign", "publish"))
    args = parser.parse_args()
    if args.command == "version":
        name, code = version()
        print(f"PaperFlow {name} (Android versionCode {code})")
    else:
        {"prepare": prepare, "build": build, "sign": sign, "publish": publish}[args.command]()
