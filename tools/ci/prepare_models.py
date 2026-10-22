"""Fetch pinned public test languages, verify bytes, optionally seed an installed debug app."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
PACKS = {"ocr": ("eng", "ell"), "translation": ("en-el", "el-en")}


def verify(data, entry):
    if len(data) != entry["size"]:
        raise ValueError("Language size mismatch")
    if "blobSha1" in entry:
        digest = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        expected = entry["blobSha1"]
    else:
        digest, expected = hashlib.sha256(data).hexdigest(), entry["sha256"]
    if digest != expected:
        raise ValueError("Language checksum mismatch")


def safe_name(value):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", value) or value in (".", ".."):
        raise ValueError("Invalid manifest filename")
    return value


def fetch(entry, target):
    if target.is_file():
        try:
            verify(target.read_bytes(), entry)
            return
        except ValueError:
            target.unlink()
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".part")
    try:
        for attempt in range(4):
            try:
                request = urllib.request.Request(entry["url"], headers={"User-Agent": "PaperFlow-CI"})
                with urllib.request.urlopen(request, timeout=60) as response:
                    stream = gzip.GzipFile(fileobj=response) if entry.get("gzip") else response
                    with temporary.open("wb") as out:
                        count = 0
                        while chunk := stream.read(65536):
                            count += len(chunk)
                            if count > entry["size"]:
                                raise ValueError("Language expands beyond manifest size")
                            out.write(chunk)
                verify(temporary.read_bytes(), entry)
                temporary.replace(target)
                return
            except (OSError, ValueError):
                if attempt == 3:
                    raise
                time.sleep(2 ** attempt)
    finally:
        temporary.unlink(missing_ok=True)


def prepare(cache):
    for kind, codes in PACKS.items():
        catalog = json.loads((ROOT / f"app/src/main/assets/languages/{kind}.json").read_text("utf-8"))
        entries = {e["code"]: e for e in catalog["languages"]}
        for code in codes:
            entry = entries[code]
            if kind == "ocr":
                fetch(entry, cache / "tessdata" / f"{safe_name(code)}.traineddata")
            else:
                folder = cache / "translation-models" / safe_name(code)
                for item in entry["files"]:
                    fetch(item, folder / safe_name(item["path"]))
                (folder / "installed").write_text(json.dumps(entry), encoding="utf-8")


def wait_for_android(command, timeout=180):
    """Boot completion can precede a ready package manager on a cold AVD."""
    deadline = time.monotonic() + timeout
    ready = 0
    while time.monotonic() < deadline:
        try:
            result = subprocess.run(command + ["shell", "cmd", "package", "path", "android"],
                                    capture_output=True, text=True, timeout=15)
            ready = ready + 1 if result.returncode == 0 and "package:" in result.stdout else 0
            if ready == 5:
                return
        except subprocess.TimeoutExpired:
            ready = 0
        time.sleep(2)
    raise RuntimeError("Android package manager did not become ready within the boot timeout")


def install(cache, adb, serial):
    command = [adb] + (["-s", serial] if serial else [])
    try:
        wait_for_android(command)
        subprocess.run(command + ["install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk")], check=True)
    except (RuntimeError, subprocess.SubprocessError):
        report = ROOT / "build/ci-device-reports/emulator-install-logcat.txt"
        report.parent.mkdir(parents=True, exist_ok=True)
        with report.open("w", encoding="utf-8") as output:
            try:
                subprocess.run(command + ["logcat", "-d", "-b", "all", "-t", "3000"],
                               stdout=output, stderr=subprocess.STDOUT, timeout=30)
            except subprocess.SubprocessError as error:
                output.write(f"Could not collect emulator logcat: {error}\n")
        raise
    # Camera integration tests supply generated captures. Resolve the runtime prompt
    # before launching activities, so it cannot obscure review/result callbacks.
    subprocess.run(command + ["shell", "pm", "grant", "com.example.myapplication", "android.permission.CAMERA"], check=True)
    for file in sorted(cache.rglob("*")):
        if not file.is_file() or file.suffix == ".part":
            continue
        relative = file.relative_to(cache)
        for segment in relative.parts:
            safe_name(segment)
        target = "files/" + relative.as_posix()
        subprocess.run(command + ["shell", "run-as", "com.example.myapplication", "mkdir", "-p", str(Path(target).parent).replace("\\", "/")], check=True)
        # The path consists solely of validated manifest components, never user input.
        with file.open("rb") as data:
            subprocess.run(command + ["exec-in", "run-as", "com.example.myapplication", "dd", f"of={target}"], stdin=data, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, default=ROOT / "build/ci-models")
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT", "")
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")))
    args = parser.parse_args()
    prepare(args.cache)
    if args.install:
        install(args.cache, args.adb, args.serial)


if __name__ == "__main__":
    main()
