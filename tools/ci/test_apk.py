import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from check_apk import ARCHITECTURES, REQUIRED, check


class ApkArchitectureTests(unittest.TestCase):
    def make_apk(self, path, abi, missing=None, wrong_header=False):
        elf_class, machine = ARCHITECTURES[abi]
        header = bytearray(20)
        header[:6] = b"\x7fELF" + bytes([elf_class, 1])
        struct.pack_into("<H", header, 18, 0 if wrong_header else machine)
        with zipfile.ZipFile(path, "w") as archive:
            for name in REQUIRED - ({missing} if missing else set()):
                archive.writestr(f"lib/{abi}/{name}", header)

    def test_both_arm_architectures(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory, "app.apk")
            for abi in ("arm64-v8a", "armeabi-v7a"):
                self.make_apk(apk, abi)
                self.assertEqual(len(REQUIRED), check(apk, abi))

    def test_rejects_missing_translation_or_ocr_engine(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory, "app.apk")
            for missing in ("libpaperflow_runtime.so", "libc++_shared.so"):
                self.make_apk(apk, "armeabi-v7a", missing=missing)
                with self.assertRaisesRegex(ValueError, "Missing native engines"):
                    check(apk, "armeabi-v7a")

    def test_rejects_wrong_or_mislabelled_architecture(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory, "app.apk")
            self.make_apk(apk, "arm64-v8a")
            with self.assertRaises(ValueError):
                check(apk, "armeabi-v7a")
            self.make_apk(apk, "armeabi-v7a", wrong_header=True)
            with self.assertRaisesRegex(ValueError, "ELF architecture mismatch"):
                check(apk, "armeabi-v7a")

    def test_universal_requires_every_engine_for_every_architecture(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory, "all.apk")
            parts = []
            for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
                part = Path(directory, abi + ".apk")
                self.make_apk(part, abi)
                parts.append(part)
            with zipfile.ZipFile(apk, "w") as output:
                for part in parts:
                    with zipfile.ZipFile(part) as source:
                        for name in source.namelist():
                            output.writestr(name, source.read(name))
            self.assertEqual(3 * len(REQUIRED), check(apk, "all"))
            self.make_apk(parts[0], "arm64-v8a", missing="libpaperflow_runtime.so")
            with zipfile.ZipFile(apk, "w") as output:
                for part in parts:
                    with zipfile.ZipFile(part) as source:
                        for name in source.namelist():
                            output.writestr(name, source.read(name))
            with self.assertRaisesRegex(ValueError, "Missing native engines for arm64-v8a"):
                check(apk, "all")

    def test_rejects_apk_without_native_libraries(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory, "app.apk")
            with zipfile.ZipFile(apk, "w"):
                pass
            with self.assertRaises(ValueError):
                check(apk, "armeabi-v7a")


if __name__ == "__main__":
    unittest.main()
