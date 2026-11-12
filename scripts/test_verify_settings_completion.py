"""Harness-only tests: real OS spawn boundaries; controlled collector failure around the actual parser."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("completion_gate_under_test", Path(__file__).with_name("verify_settings_completion.py"))
G = importlib.util.module_from_spec(spec)
spec.loader.exec_module(G)
IDENTITY = "synthetic.Case#one"


def instrument_text(failed=False):
    fields = "INSTRUMENTATION_STATUS: class=synthetic.Case\nINSTRUMENTATION_STATUS: test=one\n"
    end = fields + ("INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: controlled guard\n" if failed else "")
    summary = "FAILURES!!!\nTests run: 1,  Failures: 1\n" if failed else "OK (1 test)\n"
    return fields + "INSTRUMENTATION_STATUS_CODE: 1\n" + end + "INSTRUMENTATION_STATUS_CODE: " + ("-2" if failed else "0") + "\n" + summary + "INSTRUMENTATION_CODE: -1\n"


class NativeReceiptTest(unittest.TestCase):
    def test_real_spawn_failure_is_recorded_without_fabricated_exit_and_propagates(self):
        with tempfile.TemporaryDirectory(prefix="duck-native-spawn-") as temporary:
            out = Path(temporary)
            missing = out / "nonexistent-native-executable.exe"
            self.assertFalse(missing.exists())
            with self.assertRaises(OSError) as caught:
                G.native([missing], "missing", out, os.environ.copy(), timeout=10)
            receipt = out / "missing-native.json"
            self.assertTrue(receipt.is_file(), "spawn failure receipt missing")
            value = json.loads(receipt.read_text())
            self.assertIsNone(value["exit_code"], "unstarted process must not have a native exit")
            self.assertEqual(repr(caught.exception), value["spawn_error"])
            self.assertEqual([str(missing)], value["command"])
            self.assertGreaterEqual(value["elapsed_seconds"], 0)
            self.assertNotIn("interrupted", value)

    def test_real_successful_child_keeps_complete_exit_and_output(self):
        with tempfile.TemporaryDirectory(prefix="duck-native-success-") as temporary:
            out = Path(temporary)
            result, raw = G.native([sys.executable, "-B", "-c", "print('bounded native positive control')"], "positive", out, os.environ.copy(), timeout=10)
            self.assertEqual(0, result["exit_code"])
            self.assertEqual("bounded native positive control", raw.strip())
            self.assertNotIn("spawn_error", result)
            self.assertEqual(result, json.loads((out / "positive-native.json").read_text()))

    def test_real_unsuccessful_child_preserves_its_actual_exit(self):
        with tempfile.TemporaryDirectory(prefix="duck-native-negative-") as temporary:
            result, _ = G.native([sys.executable, "-B", "-c", "raise SystemExit(7)"], "negative", Path(temporary), os.environ.copy(), timeout=10)
            self.assertEqual(7, result["exit_code"])
            self.assertNotIn("spawn_error", result)


class CollectorReceiptTest(unittest.TestCase):
    def drive(self, out, raw, exit_code=0, logcat="Start proc 222:app.duck.notes/u0a100", collector_error=None):
        def adb(arguments, label, output, env, serial, **kwargs):
            if label == "previous-pid":
                return "111"
            if label == "logcat":
                self.assertTrue((out / "instrument.json").is_file(), "parsed instrumentation checkpoint missing before logcat")
                checkpoint = json.loads((out / "instrument.json").read_text())
                self.assertFalse(checkpoint["accepted"], "pre-logcat checkpoint must reject incomplete proof")
                self.assertEqual([], checkpoint["process"]["started_pids"])
                if collector_error is not None:
                    raise collector_error
                return logcat
            return ""
        with patch.object(G, "adb", side_effect=adb), patch.object(G, "native", return_value=({"exit_code": exit_code}, raw)):
            return G.instrument("controlled", ["never-executed-runner"], {IDENTITY}, out, {}, "never-executed-emulator")

    def test_failed_native_records_survive_secondary_collector_error(self):
        with tempfile.TemporaryDirectory(prefix="duck-collector-failure-") as temporary:
            out = Path(temporary) / "native"
            error = OSError(87, "controlled collector spawn error")
            with self.assertRaises(OSError) as caught:
                self.drive(out, instrument_text(True), exit_code=255, collector_error=error)
            self.assertIs(error, caught.exception)
            receipt = out / "instrument.json"
            self.assertTrue(receipt.is_file(), "parsed instrumentation receipt missing")
            value = json.loads(receipt.read_text())
            self.assertFalse(value["accepted"])
            self.assertEqual(1, value["failed"])
            self.assertEqual([IDENTITY], [item["id"] for item in value["records"]])
            self.assertIn("native instrumentation failure/interruption", value["issues"])
            self.assertIn("logcat collection incomplete; no verified fresh target process", value["issues"])
            xml = ET.parse(out / "instrument.xml").getroot()
            self.assertEqual("1", xml.attrib["failures"])
            self.assertIsNotNone(xml.find("testcase/failure"))

    def test_green_footer_cannot_vouch_for_missing_logcat(self):
        with tempfile.TemporaryDirectory(prefix="duck-collector-green-") as temporary:
            out = Path(temporary) / "native"
            with self.assertRaises(OSError):
                self.drive(out, instrument_text(), collector_error=OSError(87, "controlled collector spawn error"))
            value = json.loads((out / "instrument.json").read_text())
            self.assertEqual(1, value["passed"])
            self.assertEqual([1], value["ok_counts"])
            self.assertFalse(value["accepted"])

    def test_complete_green_and_fresh_process_replace_rejected_checkpoint(self):
        with tempfile.TemporaryDirectory(prefix="duck-collector-complete-") as temporary:
            out = Path(temporary) / "native"
            value = self.drive(out, instrument_text())
            self.assertTrue(value["accepted"])
            self.assertEqual(["222"], value["process"]["started_pids"])
            self.assertEqual([], value["issues"])
            self.assertEqual(value, json.loads((out / "instrument.json").read_text()))

    def test_missing_or_stale_process_still_rejects_complete_green(self):
        for logcat in ("", "Start proc 111:app.duck.notes/u0a100", "Start proc 222:neighbor.package/u0a100"):
            with self.subTest(logcat=logcat), tempfile.TemporaryDirectory(prefix="duck-collector-pid-") as temporary:
                value = self.drive(Path(temporary) / "native", instrument_text(), logcat=logcat)
                self.assertFalse(value["accepted"])
                self.assertIn("no verified fresh target process", value["issues"])

    def test_missing_footer_still_rejects_fresh_process(self):
        with tempfile.TemporaryDirectory(prefix="duck-collector-footer-") as temporary:
            value = self.drive(Path(temporary) / "native", instrument_text().replace("INSTRUMENTATION_CODE: -1\n", ""))
            self.assertFalse(value["accepted"])
            self.assertIn("missing/duplicate/unsuccessful INSTRUMENTATION_CODE", value["issues"])


if __name__ == "__main__":
    unittest.main()
