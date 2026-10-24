#!/usr/bin/env python3
"""Contract checks for lane selection, quoting, report freshness and failure propagation."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("dev", Path(__file__).with_name("dev.py"))
dev = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dev)


class DevTest(unittest.TestCase):
    def test_fast_keeps_filter_as_one_argument_and_never_cleans(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "app/build/test-results/testDebugUnitTest").mkdir(parents=True)
            class Args:
                timeout = 20
            with patch.object(dev, "ROOT", root), patch.object(dev, "gradle", side_effect=lambda tasks, _: tasks), \
                    patch.object(dev, "run") as run, patch.object(dev, "verify_jvm"):
                dev.jvm(Args(), ["*PolicyTest.some method $literal`*"])
            self.assertEqual([":app:testDebugUnitTest", "--rerun", "--tests", "*PolicyTest.some method $literal`*"], run.call_args.args[0])

    def test_no_stale_or_empty_or_wrong_selector_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            with self.assertRaises(RuntimeError):
                dev.verify_jvm(folder, ["*PolicyTest*"])
            (folder / "TEST-a.xml").write_text('<testsuite tests="1"><testcase classname="a.PolicyTest" name="safe"/></testsuite>')
            dev.verify_jvm(folder, ["*PolicyTest.safe"])
            with self.assertRaises(RuntimeError):
                dev.verify_jvm(folder, ["*Missing*"])
            (folder / "TEST-a.xml").write_text('<testsuite tests="1" failures="1"/>')
            with self.assertRaises(RuntimeError):
                dev.verify_jvm(folder, [])

    def test_clears_reports_only_before_execution_and_propagates_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            reports = root / "app/build/test-results/testDebugUnitTest"
            reports.mkdir(parents=True)
            stale = reports / "TEST-stale.xml"
            stale.write_text("stale")
            compiled = root / "app/build/keep.class"
            compiled.write_text("compiled")
            class Args:
                timeout = 20
            with patch.object(dev, "ROOT", root), patch.object(dev, "gradle", return_value=["unused"]), \
                    patch.object(dev, "run", side_effect=SystemExit(7)), patch.object(dev, "verify_jvm") as verify:
                with self.assertRaises(SystemExit) as result:
                    dev.jvm(Args(), ["*PolicyTest*"])
                self.assertEqual(7, result.exception.code)
                verify.assert_not_called()
            self.assertFalse(stale.exists())
            self.assertTrue(compiled.exists())

    def test_subprocess_exit_status_and_missing_device_are_not_hidden(self):
        with self.assertRaises(SystemExit) as result:
            dev.run(["python3", "-c", "raise SystemExit(9)"], 5, True)
        self.assertEqual(9, result.exception.code)
        class Args:
            disposable = False
            serial = None
            test = None
        with patch.object(dev, "run") as run:
            with self.assertRaises(RuntimeError):
                dev.device(Args())
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
