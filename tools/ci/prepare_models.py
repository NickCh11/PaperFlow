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
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))

ROOT = Path(__file__).resolve().parents[2]
PACKS = ("en", "el")


def verify(data, entry):
    if not data or (not entry.get("gzip") and len(data) != entry["size"]):
        raise ValueError("Language size mismatch")
    if "blobSha1" in entry:
        digest = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        expected = entry["blobSha1"]
    elif "sha256" in entry:
        digest, expected = hashlib.sha256(data).hexdigest(), entry["sha256"]
    else:
        return  # Upstream v6 declares transport sizes, not content digests.
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
                    with temporary.open("wb") as out:
                        count = 0
                        while chunk := response.read(65536):
                            count += len(chunk)
                            if count > entry["size"]:
                                raise ValueError("Language download exceeds manifest size")
                            out.write(chunk)
                if count != entry["size"]:
                    raise ValueError("Language download size mismatch")
                if entry.get("gzip"):
                    decoded = temporary.with_suffix(".decoded.part")
                    try:
                        with gzip.open(temporary,"rb") as source, decoded.open("wb") as output:
                            shutil.copyfileobj(source,output)
                        decoded.replace(temporary)
                    finally:
                        decoded.unlink(missing_ok=True)
                verify(temporary.read_bytes(), entry)
                temporary.replace(target)
                return
            except (OSError, ValueError):
                if attempt == 3:
                    raise
                time.sleep(2 ** attempt)
    finally:
        temporary.unlink(missing_ok=True)


def prepare(cache, all_ocr=False):
    from offline_catalog import CATALOG, active_files, core_packs, ocr_packs, resolve
    catalog = json.loads((ROOT / CATALOG).read_text("utf-8"))
    # A distinct cache root prevents fixtures from the retired engine being seeded.
    install_root = cache / "offline-language-packs"
    packs=set(core_packs(catalog,PACKS))
    if all_ocr:packs.update(ocr_packs(catalog))
    for path,item in active_files(catalog,sorted(packs)).items():
        entry = dict(item,size=item["sizeBytes"],gzip=item["url"].split("?",1)[0].endswith(".gz"))
        target = resolve(install_root,path)
        fetch(entry,target)
        archive = item.get("archive")
        if archive:
            import zipfile
            marker=item.get("installMarker")
            install_name=Path(marker["path"]).parent.name if marker else None
            with zipfile.ZipFile(target) as source:
                for member in source.infolist():
                    parts=Path(member.filename.lstrip("/")).parts
                    if ".." in parts:raise ValueError("Unsafe archive path")
                    if install_name and parts and parts[0]!=install_name:parts=(install_name,)+parts
                    destination=resolve(install_root,str(Path(archive["extractTo"],*parts)))
                    if member.is_dir():destination.mkdir(parents=True,exist_ok=True)
                    else:
                        destination.parent.mkdir(parents=True,exist_ok=True)
                        with source.open(member) as src,destination.open("wb") as dst:shutil.copyfileobj(src,dst)
            if marker:
                marker_file=resolve(install_root,marker["path"]);marker_file.parent.mkdir(parents=True,exist_ok=True)
                marker_file.write_text(json.dumps(dict(version=marker["version"])),encoding="utf-8")
            if archive.get("deleteAfterExtract"):target.unlink()


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
    import uuid
    staging="/data/local/tmp/paperflow-language-seed-"+uuid.uuid4().hex
    source=cache/"offline-language-packs"
    files=[file for file in sorted(source.rglob("*")) if file.is_file() and file.suffix!=".part"]
    for file in files:
        for segment in file.relative_to(cache).parts:safe_name(segment)
    try:
        subprocess.run(command+["shell","mkdir","-p",staging],check=True)
        subprocess.run(command+["push",str(source),staging+"/"],check=True)
        subprocess.run(command+["shell","run-as","com.example.myapplication","mkdir","-p","files"],check=True)
        subprocess.run(command+["shell","run-as","com.example.myapplication","cp","-R",staging+"/offline-language-packs","files/"],check=True)
        # exec-in/dd may lose the final partial block on Windows. adb push waits
        # for completion; check every installed file before running device tests.
        for file in files:
            target="files/"+file.relative_to(cache).as_posix()
            actual=subprocess.run(command+["shell","run-as","com.example.myapplication","sha256sum",target],check=True,capture_output=True,text=True).stdout.split()[0]
            if actual!=hashlib.sha256(file.read_bytes()).hexdigest():raise ValueError("Corrupt emulator fixture: "+target)
    finally:
        subprocess.run(command+["shell","rm","-rf",staging],check=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, default=ROOT / "build/ci-models")
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--all-ocr", action="store_true", help="Seed every downloadable OCR script for language detection tests")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT", "")
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")))
    args = parser.parse_args()
    prepare(args.cache,args.all_ocr)
    if args.install:
        install(args.cache, args.adb, args.serial)


if __name__ == "__main__":
    main()
