"""Check that an architecture-specific APK contains its required native engines."""
from pathlib import Path
import argparse
import struct
import sys
import zipfile

ARCHITECTURES = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40),
                 "x86_64": (2, 62), "x86": (1, 3)}
REQUIRED = {"libpaperflow_runtime.so", "libtensorflowlite_jni.so", "libopencv_java4.so",
            "libonnxruntime.so",
            "libonnxruntime4j_jni.so", "libc++_shared.so"}


def check(path, abi):
    with zipfile.ZipFile(path) as apk:
        libraries = [n for n in apk.namelist() if n.startswith("lib/") and n.endswith(".so")]
        def header(name):
            with apk.open(name) as source:
                return source.read(20)
        return check_libraries(libraries, header, abi)


def check_directory(path, abi):
    root = Path(path)
    # AGP's merge output still contains unselected AAR ABIs. abiFilters are
    # applied during APK packaging; validate only the configured target set here.
    selected = {"arm64-v8a", "armeabi-v7a", "x86_64"} if abi == "all" else {abi}
    libraries = {"lib/" + p.relative_to(root).as_posix(): p for p in root.rglob("*.so")
                 if p.relative_to(root).parts[0] in selected}
    def header(name):
        with libraries[name].open("rb") as source:
            return source.read(20)
    return check_libraries(list(libraries), header, abi)


def check_libraries(libraries, read_header, abi):
    if abi != "all" and abi not in ARCHITECTURES:
        raise ValueError(f"Unsupported ABI: {abi}")
    if any(len(n.split("/")) != 3 for n in libraries):
        raise ValueError("Unexpected native library layout")
    actual = {n.split("/")[1] for n in libraries}
    expected = {"arm64-v8a", "armeabi-v7a", "x86_64"} if abi == "all" else {abi}
    if actual != expected:
        raise ValueError(f"Expected {sorted(expected)}, found {sorted(actual)}")
    for architecture in expected:
        missing = REQUIRED - {Path(n).name for n in libraries if n.split("/")[1] == architecture}
        if missing:
            raise ValueError(f"Missing native engines for {architecture}: {sorted(missing)}")
    for name in libraries:
        elf_class, machine = ARCHITECTURES[name.split("/")[1]]
        header = read_header(name)
        if (len(header) != 20 or header[:4] != b"\x7fELF" or
                header[4] != elf_class or header[5] != 1 or
                struct.unpack_from("<H", header, 18)[0] != machine):
            raise ValueError(f"Native ELF architecture mismatch: {name}")
    return len(libraries)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-dir", action="store_true")
    parser.add_argument("path")
    parser.add_argument("abi")
    args = parser.parse_args()
    try:
        count = (check_directory if args.native_dir else check)(args.path, args.abi)
        print(f"{args.path}: {count} native libraries verified for {args.abi}.")
    except (ValueError, OSError, zipfile.BadZipFile, IndexError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
