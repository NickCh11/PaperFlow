"""Collect real Android LLVM profiles and verify the matching instrumented objects."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
BINARIES = ("libpaperflow_runtime.so", "paperflow-runtime-tests", "libc++_shared.so")
APP = "com.example.myapplication"
PROFILE = re.compile(r"[A-Za-z0-9_-]+\.profraw")
SESSION = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def manifest(directory, revision, toolchain):
    sources = list((ROOT / "translation/native/src").rglob("*.rs"))
    sources += list((ROOT / "translation/native/testdata").rglob("*.xml"))
    sources += [ROOT / path for path in ("translation/native/Cargo.toml", "translation/native/Cargo.lock",
                "translation/src/main/assets/index_v6.json",
                "tools/ci/build_native_coverage.sh", "tools/ci/build_language_runtime.sh", "tools/ci/native_coverage_toolchain.txt")]
    inputs = {path.relative_to(ROOT).as_posix(): sha256(path) for path in sorted(sources)}
    result = dict(schema=1, revision=revision, toolchain=toolchain, inputs=inputs, binaries={})
    for name in BINARIES:
        path = directory / name
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f"Missing native coverage object: {name}")
        result["binaries"][name] = sha256(path)
    return result


def verify(directory, revision, toolchain):
    recorded = json.loads((directory / "manifest.json").read_text("utf-8"))
    expected = manifest(directory, revision, toolchain)
    if recorded != expected:
        raise ValueError("Native coverage objects do not match this revision/toolchain or their recorded hashes")
    return recorded


def rebind(directory, revision, toolchain):
    """An exact native-source cache may serve a newer commit with unchanged native inputs."""
    previous = json.loads((directory / "manifest.json").read_text("utf-8"))
    verify(directory, previous["revision"], toolchain)
    previous["revision"] = revision
    (directory / "manifest.json").write_text(json.dumps(previous, indent=2) + "\n", encoding="utf-8")
    return previous


def profile_names(raw):
    names = raw.decode("utf-8").split()
    if not names or len(names) != len(set(names)) or any(not PROFILE.fullmatch(name) for name in names):
        raise ValueError("Missing, duplicate or unsafe native profile names")
    return names


def app_session(raw):
    value = raw.decode("utf-8").strip()
    if not SESSION.fullmatch(value):
        raise ValueError("Invalid native coverage session")
    return value


def collect(command, remote, destination, application=False, run=subprocess.run):
    prefix = ["run-as", APP] if application else []
    listing = run(command + ["shell"] + prefix + ["ls", remote], check=True, capture_output=True).stdout
    names = profile_names(listing)
    destination.mkdir(parents=True, exist_ok=True)
    for name in names:
        raw = run(command + ["exec-out"] + prefix + ["cat", remote + "/" + name], check=True, capture_output=True).stdout
        if len(raw) < 16:
            raise ValueError("Empty or truncated native execution profile")
        (destination / name).write_bytes(raw)
    return len(names)


def assert_test_pass(output, expected):
    found = re.findall(r"test result: ok\. (\d+) passed; 0 failed; 0 ignored;", output)
    if len(found) != 1 or int(found[0]) != expected or expected < 1:
        raise ValueError("Instrumented Rust tests did not execute the complete declared suite")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("manifest", "rebind", "run-unit", "collect-app"))
    parser.add_argument("--artifacts", type=Path, default=ROOT / "build/native-coverage/x86_64")
    parser.add_argument("--output", type=Path, default=ROOT / "build/ci-device-coverage/native/profiles")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    args = parser.parse_args()
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    toolchain = (ROOT / "tools/ci/native_coverage_toolchain.txt").read_text("utf-8").strip()
    if args.operation == "manifest":
        (args.artifacts / "manifest.json").write_text(json.dumps(manifest(args.artifacts, revision, toolchain), indent=2) + "\n", encoding="utf-8")
        return
    if args.operation == "rebind":
        rebind(args.artifacts, revision, toolchain)
        return
    command = [args.adb] + (["-s", args.serial] if args.serial else [])
    if args.operation == "run-unit":
        verify(args.artifacts, revision, toolchain)
        remote = "/data/local/tmp/paperflow-native-coverage-" + str(uuid.uuid4())
        subprocess.run(command + ["shell", "mkdir", "-p", remote + "/profiles"], check=True)
        for name in ("paperflow-runtime-tests", "libc++_shared.so"):
            subprocess.run(command + ["push", str(args.artifacts / name), remote + "/" + name], check=True)
        subprocess.run(command + ["shell", "chmod", "755", remote + "/paperflow-runtime-tests"], check=True)
        # Every interpolated path below is generated here, never taken from device output.
        result = subprocess.run(command + ["shell", f"LD_LIBRARY_PATH={remote} LLVM_PROFILE_FILE={remote}/profiles/unit-%m-%p.profraw {remote}/paperflow-runtime-tests --test-threads=1 --color never"], capture_output=True, text=True)
        output = args.output.parent.parent / "rust-tests-coverage.txt"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(result.stdout + result.stderr, encoding="utf-8")
        print(result.stdout)
        result.check_returncode()
        declared = sum(len(re.findall(r"#\[test\]", source.read_text("utf-8"))) for source in (ROOT / "translation/native/src").rglob("*.rs"))
        assert_test_pass(result.stdout, declared)
        count = collect(command, remote + "/profiles", args.output / "unit")
    else:
        latest = subprocess.check_output(command + ["exec-out", "run-as", APP, "cat", "files/native-coverage/latest.txt"])
        session = app_session(latest)
        count = collect(command, "files/native-coverage/" + session, args.output / "app", application=True)
    print(f"Collected {count} fresh Android native profiles ({args.operation})")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Native coverage failed: {error}", file=sys.stderr)
        sys.exit(1)
