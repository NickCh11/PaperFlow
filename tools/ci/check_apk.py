"""Check that an architecture-specific APK contains its required native engines."""
from pathlib import Path
import struct
import sys
import zipfile

ARCHITECTURES = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40),
                 "x86_64": (2, 62), "x86": (1, 3)}
REQUIRED = {"libbergamot_jni.so", "libtensorflowlite_jni.so", "libopencv_java4.so",
            "libtesseract.so", "libleptonica.so", "libonnxruntime.so",
            "libonnxruntime4j_jni.so", "libc++_shared.so"}


def check(path, abi):
    if abi != "all" and abi not in ARCHITECTURES:
        raise ValueError(f"Unsupported ABI: {abi}")
    with zipfile.ZipFile(path) as apk:
        libraries = [n for n in apk.namelist() if n.startswith("lib/") and n.endswith(".so")]
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
            with apk.open(name) as source:
                header = source.read(20)
            if (len(header) != 20 or header[:4] != b"\x7fELF" or
                    header[4] != elf_class or header[5] != 1 or
                    struct.unpack_from("<H", header, 18)[0] != machine):
                raise ValueError(f"Native ELF architecture mismatch: {name}")
        return len(libraries)


if __name__ == "__main__":
    try:
        count = check(sys.argv[1], sys.argv[2])
        print(f"{sys.argv[1]}: {count} native libraries verified for {sys.argv[2]}.")
    except (ValueError, OSError, zipfile.BadZipFile, IndexError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
