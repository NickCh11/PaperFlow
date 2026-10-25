"""Export matching Android unit/JNI execution profiles with the pinned Rust LLVM tools."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

from check_coverage import read_native
from native_coverage import ROOT, verify


def export(artifacts, profiles, output, llvm_bin, revision, toolchain):
    verify(artifacts, revision, toolchain)
    raw = sorted(profiles.rglob("*.profraw"))
    if not raw or not any("app" in file.parts for file in raw) or not any("unit" in file.parts for file in raw):
        raise ValueError("Coverage requires both Android JNI/application and Rust unit-test execution profiles")
    if any(file.stat().st_size < 16 for file in raw):
        raise ValueError("Empty or truncated native execution profile")
    suffix = ".exe" if os.name == "nt" else ""
    profdata, cov = (llvm_bin / (name + suffix) for name in ("llvm-profdata", "llvm-cov"))
    if not profdata.is_file() or not cov.is_file():
        raise ValueError("The pinned Rust LLVM tools are missing")
    output.parent.mkdir(parents=True, exist_ok=True)
    merged = output.with_suffix(".profdata")
    subprocess.run([str(profdata), "merge", "-sparse", "-failure-mode=any", "-o", str(merged)] + [str(file) for file in raw], check=True)
    sources = sorted((ROOT / "translation/native/src").rglob("*.rs"))
    result = subprocess.run([str(cov), "export", str(artifacts / "libpaperflow_runtime.so"),
                             "-object", str(artifacts / "paperflow-runtime-tests"),
                             "-format=lcov", "-instr-profile=" + str(merged)] + [str(file) for file in sources],
                            check=True, capture_output=True, text=True, encoding="utf-8")
    if "warning:" in result.stderr.lower() or "error:" in result.stderr.lower():
        raise ValueError("LLVM reported mismatched or invalid coverage: " + result.stderr.strip())
    output.write_text(result.stdout, encoding="utf-8")
    native = read_native(output, {file.relative_to(ROOT).as_posix() for file in sources})
    if native["status"] != "measured":
        raise ValueError("Android coverage is missing owned Rust sources or branch instrumentation")
    output.with_suffix(".json").write_text(json.dumps(native, indent=2) + "\n", encoding="utf-8")
    return native


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path, default=ROOT / "build/native-coverage/x86_64")
    parser.add_argument("--profiles", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/coverage/native.lcov")
    parser.add_argument("--llvm-bin", type=Path)
    args = parser.parse_args()
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    toolchain = (ROOT / "tools/ci/native_coverage_toolchain.txt").read_text("utf-8").strip()
    llvm_bin = args.llvm_bin
    if llvm_bin is None:
        sysroot = subprocess.check_output(["rustc", "+" + toolchain, "--print", "sysroot"], text=True).strip()
        version = subprocess.check_output(["rustc", "+" + toolchain, "-vV"], text=True)
        host = next(line.split(": ", 1)[1] for line in version.splitlines() if line.startswith("host: "))
        llvm_bin = Path(sysroot) / "lib/rustlib" / host / "bin"
    print(json.dumps(export(args.artifacts, args.profiles, args.output, llvm_bin, revision, toolchain), indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError, StopIteration) as error:
        print(f"Native coverage export failed: {error}", file=sys.stderr)
        sys.exit(1)
