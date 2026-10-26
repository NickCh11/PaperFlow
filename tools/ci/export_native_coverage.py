"""Export matching Android unit/JNI execution profiles with the pinned Rust LLVM tools."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib

from check_coverage import read_native
from native_coverage import ROOT, verify


def diagnostics(stderr, dependencies):
    """Audit LLVM's unused dependency definitions; never ignore an owned mismatch."""
    expected = 0
    ignored = []
    for line in stderr.splitlines():
        if not line.strip():
            continue
        summary = re.fullmatch(r"warning: (\d+) functions have mismatched data", line)
        mismatch = re.fullmatch(r"hash-mismatch: No profile record found for '([^']+)' with hash = 0x([0-9a-fA-F]+)", line)
        if summary:
            expected += int(summary[1])
        elif mismatch:
            symbol, digest = mismatch.groups()
            crate = re.search(r"Cs[A-Za-z0-9]+_(\d+)", symbol)
            owner = symbol[crate.end():crate.end() + int(crate[1])] if crate else None
            if (int(digest, 16) != 0 or "paperflow_runtime" in symbol or "Java_" in symbol
                    or not symbol.startswith("_R") or owner not in dependencies):
                raise ValueError("Unusable native function profile: " + line)
            ignored.append(dict(dependency=owner, symbol=symbol, hash=0))
        else:
            raise ValueError("Unexpected LLVM coverage diagnostic: " + line)
    if expected != len(ignored):
        raise ValueError("LLVM mismatch summary does not match its audited function records")
    return ignored


def owned_path(filename, sources):
    path = filename.replace("\\", "/")
    matches = [source for source in sources if path == source or path.endswith("/" + source)]
    return matches[0] if len(matches) == 1 else None


def combine(exports, sources):
    """Union real hits using source coordinates, not object-specific branch IDs."""
    files = {source: dict(lines={}, branches={}) for source in sources}
    for lcov, metadata in exports:
        current = None
        for line in lcov.splitlines():
            if line.startswith("SF:"):
                current = files.get(owned_path(line[3:], sources))
            elif line == "end_of_record":
                current = None
            elif current is not None and line.startswith("DA:"):
                location, count = map(int, line[3:].split(",")[:2])
                if location <= 0 or count < 0:
                    raise ValueError("Invalid native line execution record")
                current["lines"][location] = max(current["lines"].get(location, 0), count)
        if metadata.get("type") != "llvm.coverage.json.export":
            raise ValueError("Not LLVM source coverage metadata")
        seen_files = set()
        for data in metadata["data"]:
            for file in data["files"]:
                source = owned_path(file["filename"], sources)
                if source is None:
                    continue
                if source in seen_files:
                    raise ValueError("Duplicate native source file mapping")
                seen_files.add(source)
                branch_mappings = {}
                for branch in file["branches"]:
                    if len(branch) != 9 or any(type(value) is not int or value < 0 for value in branch):
                        raise ValueError("Invalid native branch source coordinates or counters")
                    start_line, start_column, end_line, end_column, true, false, _, _, kind = branch
                    if min(start_line, start_column, end_line, end_column) < 1 or (end_line, end_column) < (start_line, start_column):
                        raise ValueError("Invalid native branch source range")
                    key = (start_line, start_column, end_line, end_column, kind)
                    mapping = tuple(branch[6:8])
                    if key in branch_mappings and branch_mappings[key] != mapping:
                        raise ValueError("Ambiguous native branch expansion mapping")
                    branch_mappings[key] = mapping
                    # LLVM repeats generic source branches for each concrete
                    # instantiation. Union their real side hits at this source
                    # location, just as we do between app and unit objects.
                    before = files[source]["branches"].get(key, (0, 0))
                    files[source]["branches"][key] = (max(before[0], true), max(before[1], false))
    rows = []
    for source, file in sorted(files.items()):
        if not file["lines"]:
            raise ValueError("Native source has no execution mapping: " + source)
        rows.append("SF:" + source)
        rows.extend(f"DA:{line},{count}" for line, count in sorted(file["lines"].items()))
        for block, (location, counts) in enumerate(sorted(file["branches"].items())):
            rows.extend(f"BRDA:{location[0]},{block},{side},{count}" for side, count in enumerate(counts))
        rows.extend([f"LF:{len(file['lines'])}", f"LH:{sum(count > 0 for count in file['lines'].values())}",
                     f"BRF:{2 * len(file['branches'])}",
                     f"BRH:{sum(count > 0 for pair in file['branches'].values() for count in pair)}", "end_of_record"])
    return "\n".join(rows) + "\n"


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
    sources = sorted((ROOT / "translation/native/src").rglob("*.rs"))
    scope = {file.relative_to(ROOT).as_posix() for file in sources}
    locked = tomllib.loads((ROOT / "translation/native/Cargo.lock").read_text("utf-8"))
    dependencies = {package["name"].replace("-", "_") for package in locked["package"]} - {"paperflow_runtime"}
    dependencies.update(("core", "alloc", "std", "test"))
    reports, audit = [], {}
    # A test executable and a cdylib have different monomorphized definitions.
    # Never ask one object's mappings to interpret the other object's counters.
    for kind, binary in (("app", "libpaperflow_runtime.so"), ("unit", "paperflow-runtime-tests")):
        matching = [file for file in raw if kind in file.relative_to(profiles).parts]
        if not matching:
            raise ValueError("Missing native execution profiles for " + kind)
        merged = output.with_name(output.stem + "-" + kind + ".profdata")
        subprocess.run([str(profdata), "merge", "-sparse", "-failure-mode=any", "-o", str(merged)] + [str(file) for file in matching], check=True)
        base = [str(cov), "export", str(artifacts / binary), "-num-threads=1", "-instr-profile=" + str(merged)]
        dump = output.with_name(output.stem + "-" + kind + "-mapping-dump.txt")
        dump_log = dump.with_suffix(".log")
        # -dump writes InstantiationGroup debug text to stdout as well as the
        # requested format. Audit it separately so LCOV/JSON remain parseable.
        with dump.open("w", encoding="utf-8") as stream, dump_log.open("w", encoding="utf-8") as errors:
            subprocess.run(base + ["-dump", "-format=lcov"] + [str(file) for file in sources], check=True, stdout=stream, stderr=errors)
        audited = diagnostics(dump_log.read_text("utf-8"), dependencies)
        audit[kind] = audited
        expected_warning = f"warning: {len(audited)} functions have mismatched data" if audited else ""
        exported = {}
        for format, extension in (("lcov", "lcov"), ("text", "json")):
            target = output.with_name(output.stem + "-" + kind + "." + extension)
            log = target.with_suffix(target.suffix + ".log")
            command = base + ["-format=" + format]
            if format == "text":
                command.append("-skip-functions")
            with target.open("w", encoding="utf-8") as stream, log.open("w", encoding="utf-8") as errors:
                subprocess.run(command + [str(file) for file in sources], check=True, stdout=stream, stderr=errors)
            if log.read_text("utf-8").strip() != expected_warning:
                raise ValueError("LLVM export diagnostics differ from the audited object mapping")
            exported[extension] = target.read_text("utf-8")
        reports.append((exported["lcov"], json.loads(exported["json"])))
    output.write_text(combine(reports, scope), encoding="utf-8")
    output.with_name(output.stem + "-profile-diagnostics.json").write_text(json.dumps(audit, indent=2) + "\n", encoding="utf-8")
    native = read_native(output, scope)
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
