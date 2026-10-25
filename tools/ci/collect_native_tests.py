"""Collect the actual Cargo test executable, never a release shared library."""
import json
from pathlib import Path
import shutil
import sys


def collect(messages, destination):
    executables = set()
    completed = []
    for line in Path(messages).read_text("utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("reason") == "build-finished":
            completed.append(row.get("success"))
        if (row.get("reason") == "compiler-artifact"
                and row.get("target", {}).get("name") == "paperflow_runtime"
                and row.get("profile", {}).get("test") is True
                and row.get("executable")):
            executables.add(Path(row["executable"]))
    # cargo-ndk-test 4.1.2 does not propagate Cargo's exit status. Check Cargo itself.
    if completed != [True]:
        raise ValueError("Cargo did not report one successfully completed test build")
    if len(executables) != 1:
        raise ValueError(f"Expected one native test executable, found {len(executables)}")
    source = executables.pop()
    if not source.is_file() or source.stat().st_size == 0:
        raise ValueError("Cargo test executable is missing or empty")
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


if __name__ == "__main__":
    try:
        collect(sys.argv[1], sys.argv[2])
    except (OSError, ValueError, IndexError) as error:
        print(f"Native test collection failed: {error}", file=sys.stderr)
        sys.exit(1)
