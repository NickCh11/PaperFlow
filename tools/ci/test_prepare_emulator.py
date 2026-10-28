import unittest
import subprocess
from unittest.mock import patch

from prepare_emulator import prepare


class EmulatorPreparationTest(unittest.TestCase):
    def emulator(self, adb, *args):
        self.assertEqual(["adb", "-s", "emulator-5554"], adb)
        self.commands.append(args)
        if args == ("shell", "getprop", "ro.build.version.sdk"):
            return "29"
        if args == ("shell", "getprop", "ro.kernel.qemu"):
            return "1"
        if args == ("root",):
            self.root = True
        if args == ("unroot",):
            self.root = False
        if args == ("shell", "id", "-u"):
            return "0" if self.root else "2000"
        if args == ("shell", "pidof", "system_server"):
            return "200" if self.restarted else "100"
        if args == ("shell", "start"):
            self.restarted = True
        if args[:2] == ("shell", "getprop"):
            return "256m" if args[2].endswith("heapgrowthlimit") else "512m"
        return ""

    def setUp(self):
        self.commands = []
        self.root = False
        self.restarted = False
        self.adb = ["adb", "-s", "emulator-5554"]

    def test_other_api_does_not_change_device(self):
        with patch("prepare_emulator.command", return_value="37") as command:
            prepare(self.adb)
        command.assert_called_once_with(self.adb, "shell", "getprop", "ro.build.version.sdk")

    def test_physical_device_is_rejected_before_mutation(self):
        with patch("prepare_emulator.command", side_effect=["29", "0"]) as command:
            with self.assertRaisesRegex(RuntimeError, "restricted"):
                prepare(self.adb)
        self.assertEqual(2, command.call_count)

    def test_restart_applies_limits_before_unprivileged_tests(self):
        with patch("prepare_emulator.command", side_effect=self.emulator), patch("prepare_emulator.wait_for_android") as wait:
            prepare(self.adb)
        self.assertFalse(self.root)
        self.assertTrue(self.restarted)
        self.assertLess(self.commands.index(("shell", "setprop", "dalvik.vm.heapgrowthlimit", "256m")), self.commands.index(("shell", "stop")))
        self.assertLess(self.commands.index(("shell", "start")), self.commands.index(("unroot",)))
        self.assertEqual(2, wait.call_count)

    def test_readiness_failure_is_not_ignored_and_root_is_dropped(self):
        with patch("prepare_emulator.command", side_effect=self.emulator), patch("prepare_emulator.wait_for_android", side_effect=RuntimeError("package manager timeout")):
            with self.assertRaisesRegex(RuntimeError, "timeout"):
                prepare(self.adb)
        self.assertFalse(self.root)
        self.assertIn(("unroot",), self.commands)

    def test_root_timeout_after_device_accepts_request_still_drops_root(self):
        def timeout(adb, *args):
            result = self.emulator(adb, *args)
            if args == ("root",):
                raise subprocess.TimeoutExpired("adb root", 45)
            return result
        with patch("prepare_emulator.command", side_effect=timeout), patch("prepare_emulator.wait_for_android"):
            with self.assertRaises(subprocess.TimeoutExpired):
                prepare(self.adb)
        self.assertFalse(self.root)
        self.assertIn(("unroot",), self.commands)

    def test_unchanged_system_server_fails_and_drops_root(self):
        def unchanged(adb, *args):
            result = self.emulator(adb, *args)
            return "100" if args == ("shell", "pidof", "system_server") else result
        with patch("prepare_emulator.command", side_effect=unchanged), patch("prepare_emulator.wait_for_android"):
            with self.assertRaisesRegex(RuntimeError, "did not restart"):
                prepare(self.adb)
        self.assertFalse(self.root)

    def test_ignored_growth_limit_fails_and_drops_root(self):
        def ignored(adb, *args):
            result = self.emulator(adb, *args)
            return "16m" if args == ("shell", "getprop", "dalvik.vm.heapgrowthlimit") else result
        with patch("prepare_emulator.command", side_effect=ignored), patch("prepare_emulator.wait_for_android"):
            with self.assertRaisesRegex(RuntimeError, "expected 256m, got 16m"):
                prepare(self.adb)
        self.assertFalse(self.root)
