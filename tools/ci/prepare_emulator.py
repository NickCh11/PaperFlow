"""Repair the API 29 emulator's 16 MiB ART growth limit before running tests."""
import argparse
import subprocess

from prepare_models import wait_for_android


def command(adb, *args):
    return subprocess.run(adb + list(args), check=True, capture_output=True,
                          text=True, timeout=45).stdout.strip()


def prepare(adb):
    if command(adb, "shell", "getprop", "ro.build.version.sdk") != "29":
        print("No API 29 heap adjustment needed")
        return
    if command(adb, "shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Heap adjustment is restricted to an Android emulator")
    # heap-size on emulator-runner controls the maximum, not ART's growth limit.
    # Zygote must restart so system_server and subsequent apps inherit the change.
    command(adb, "root")
    try:
        command(adb, "wait-for-device")
        if command(adb, "shell", "id", "-u") != "0":
            raise RuntimeError("API 29 emulator must support temporary adb root")
        previous = command(adb, "shell", "pidof", "system_server")
        command(adb, "shell", "setprop", "dalvik.vm.heapgrowthlimit", "256m")
        command(adb, "shell", "setprop", "dalvik.vm.heapsize", "512m")
        command(adb, "shell", "stop")
        command(adb, "shell", "start")
        wait_for_android(adb)
        current = command(adb, "shell", "pidof", "system_server")
        if not current or current == previous:
            raise RuntimeError("Android framework did not restart after heap adjustment")
        for name, expected in (("heapgrowthlimit", "256m"), ("heapsize", "512m")):
            actual = command(adb, "shell", "getprop", "dalvik.vm." + name)
            if actual != expected:
                raise RuntimeError(f"Emulator {name}: expected {expected}, got {actual}")
    finally:
        command(adb, "unroot")
        command(adb, "wait-for-device")
    if command(adb, "shell", "id", "-u") == "0":
        raise RuntimeError("adb must be unprivileged before running tests")
    wait_for_android(adb)
    print("API 29 ready: ART growth limit 256 MiB, maximum 512 MiB; adb unprivileged")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    args = parser.parse_args()
    prepare([args.adb] + (["-s", args.serial] if args.serial else []))
