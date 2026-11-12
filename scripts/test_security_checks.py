"""Tooling controls; real tracked-script mutation evidence is recorded separately.

OSV transport is replaced here to assert failure/suppression policy deterministically.
This does not replace live advisory queries or real Gitleaks execution.
"""

import contextlib
import copy
import io
import json
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

import security_checks as checks


class SecurityChecksTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="duck-security-control-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.inventory = self.base / "inventory.json"
        self.report = self.base / "advisories.json"
        self.component = {"group": "example.synthetic", "name": "test-only", "version": "1.0", "configurations": [":app:project:releaseRuntimeClasspath"]}
        self.data = {
            "format": 2,
            "components": [self.component,
                           {"group": "com.android.tools.lint", "name": "lint-gradle", "version": "31.13.2", "configurations": ["observed::app:detachedConfiguration1#1"]},
                           {"group": "com.android.tools.build", "name": "aapt2", "version": "8.13.2-14304508", "configurations": ["observed::app:detachedConfiguration2#2"]}],
            "configurations": [":app:project:releaseRuntimeClasspath"], "failures": [],
            "taskOutcomes": [{"path": path, "successful": True, "skipped": False, "upToDate": False, "noSource": False} for path in
                             [":app:testDebugUnitTest", ":app:testReleaseUnitTest", ":app:lint", ":app:assembleDebug", ":app:assembleRelease", ":app:assembleDebugAndroidTest"]],
            "observedResolutions": [{"configuration": "observed::app:detachedConfiguration1#1", "completed": True},
                                    {"configuration": "observed::app:detachedConfiguration2#2", "completed": True}],
            "projects": [
                {"path": ":", "pluginClasses": ["org.gradle.api.plugins.HelpTasksPlugin"], "configurations": [],
                 "tasks": [{"name": "help", "type": "org.gradle.configuration.Help"}],
                 "pluginIds": {"com.google.devtools.ksp": False, "org.jetbrains.kotlin.kapt": False, "kotlin-kapt": False}},
                {"path": ":app", "pluginClasses": ["com.google.devtools.ksp.gradle.KspGradleSubplugin"],
                 "configurations": ["kspPluginClasspath", "releaseRuntimeClasspath"],
                 "tasks": [{"name": "kspDebugKotlin", "type": "com.google.devtools.ksp.gradle.KspTaskJvm"}],
                 "pluginIds": {"com.google.devtools.ksp": True, "org.jetbrains.kotlin.kapt": False, "kotlin-kapt": False}},
            ],
        }

    def run_advisories(self, responses):
        responses = copy.deepcopy(responses)
        if responses and len(responses[0].get("results", [])) == 1:
            responses[0]["results"].extend({} for _ in self.data["components"][1:])
        self.inventory.write_text(json.dumps(self.data), encoding="utf-8")
        with patch.object(checks, "osv_json", side_effect=responses), contextlib.redirect_stdout(io.StringIO()):
            return checks.advisories(self.inventory, self.report)

    def wrapper_copy(self):
        for name in [*checks.WRAPPER_HASHES, "gradle/wrapper/gradle-wrapper.properties", "gradle/verification-metadata.xml"]:
            target = self.base / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(checks.ROOT / name, target)

    def test_pinned_real_wrapper_passes(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, checks.wrapper())

    def test_missing_dependency_verification_file_is_rejected(self):
        """Plant TOOL_VERIFICATION_FILE_BYPASS: bypass the metadata file presence check."""
        self.wrapper_copy()
        (self.base / "gradle/verification-metadata.xml").unlink()
        with patch.object(checks, "ROOT", self.base), self.assertRaisesRegex(ValueError, "Dependency verification metadata is missing"):
            checks.wrapper()

    def test_modified_wrapper_jar_is_rejected(self):
        """Plant TOOL_WRAPPER_BYTES_BYPASS: replace the wrapper hash comparison with False."""
        self.wrapper_copy()
        jar = self.base / "gradle/wrapper/gradle-wrapper.jar"
        jar.write_bytes(jar.read_bytes() + b"synthetic corruption")
        with patch.object(checks, "ROOT", self.base), self.assertRaisesRegex(ValueError, "checksum differs"):
            checks.wrapper()

    def test_modified_distribution_pin_is_rejected(self):
        """Plant TOOL_DISTRIBUTION_PIN_BYPASS: replace the distribution SHA comparison with False."""
        self.wrapper_copy()
        properties = self.base / "gradle/wrapper/gradle-wrapper.properties"
        properties.write_text(properties.read_text().replace("20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78", "0" * 64))
        with patch.object(checks, "ROOT", self.base), self.assertRaisesRegex(ValueError, "distribution checksum differs"):
            checks.wrapper()

    def test_modified_distribution_url_is_rejected(self):
        """Plant TOOL_DISTRIBUTION_URL_BYPASS: replace the distribution URL comparison with False."""
        self.wrapper_copy()
        properties = self.base / "gradle/wrapper/gradle-wrapper.properties"
        properties.write_text(properties.read_text().replace("services.gradle.org", "example.invalid"))
        with patch.object(checks, "ROOT", self.base), self.assertRaisesRegex(ValueError, "distribution URL differs"):
            checks.wrapper()

    def test_duplicate_wrapper_property_is_rejected(self):
        """Plant TOOL_DUPLICATE_PROPERTY_ALLOWED: replace the duplicate-property check with False."""
        self.wrapper_copy()
        properties = self.base / "gradle/wrapper/gradle-wrapper.properties"
        properties.write_text(properties.read_text() + "\nnetworkTimeout=10000\n")
        with patch.object(checks, "ROOT", self.base), self.assertRaisesRegex(ValueError, "Duplicate wrapper property"):
            checks.wrapper()

    def test_current_match_fails_despite_withdrawn_neighbor(self):
        """Plants TOOL_OSV_CURRENT_ALLOWED and TOOL_OSV_WITHDRAWN_NEIGHBOR."""
        responses = [{"results": [{"vulns": [{"id": "SYNTHETIC-WITHDRAWN"}, {"id": "SYNTHETIC-CURRENT"}]}]},
                     {"id": "SYNTHETIC-WITHDRAWN", "withdrawn": "2020-01-01T00:00:00Z"},
                     {"id": "SYNTHETIC-CURRENT"}]
        self.assertEqual(1, self.run_advisories(responses), "A current match must fail even next to a withdrawn match")
        findings = json.loads(self.report.read_text())["findings"]
        self.assertEqual(["SYNTHETIC-CURRENT"], [finding["id"] for finding in findings])
        self.assertEqual([":app:project:releaseRuntimeClasspath"], findings[0]["configurations"])

    def test_only_withdrawn_match_passes(self):
        """Positive control for the exact withdrawn-record suppression branch."""
        self.assertEqual(0, self.run_advisories([{"results": [{"vulns": [{"id": "SYNTHETIC-WITHDRAWN"}]}]},
                                               {"id": "SYNTHETIC-WITHDRAWN", "withdrawn": "2020-01-01T00:00:00Z"}]))
        self.assertEqual([], json.loads(self.report.read_text())["findings"])

    def test_successful_osv_response_without_matches_passes(self):
        self.assertEqual(0, self.run_advisories([{"results": [{}]}]))
        self.assertEqual(["example.synthetic:test-only:1.0", "com.android.tools.lint:lint-gradle:31.13.2", "com.android.tools.build:aapt2:8.13.2-14304508"], json.loads(self.report.read_text())["queried"])

    def test_osv_batches_are_bounded_and_cover_the_tail_finding(self):
        """TOOL_OSV_BATCH_BOUND/GAP drive real request construction; HTTP responses are fake."""
        self.data["components"].extend({**self.component, "name": f"batch-{index}"} for index in range(48))
        self.assertEqual(51, len(self.data["components"]))
        expected = [f"{item['group']}:{item['name']}:{item['version']}" for item in self.data["components"]]
        sent, sizes, records = [], [], []

        def response(request, timeout):
            if request.full_url == "https://api.osv.dev/v1/querybatch":
                queries = json.loads(request.data)["queries"]
                self.assertLessEqual(len(queries), 25, "OSV transport requests must contain at most 25 coordinates")
                sizes.append(len(queries))
                sent.extend(f"{query['package']['name']}:{query['version']}" for query in queries)
                payload = {"results": [{"vulns": [{"id": "SYNTHETIC-TAIL"}]} if
                                       query["package"]["name"] == "example.synthetic:batch-47" else {} for query in queries]}
            else:
                self.assertEqual("https://api.osv.dev/v1/vulns/SYNTHETIC-TAIL", request.full_url)
                records.append(request.full_url)
                payload = {"id": "SYNTHETIC-TAIL"}
            return io.StringIO(json.dumps(payload))

        self.inventory.write_text(json.dumps(self.data), encoding="utf-8")
        with patch.object(checks.urllib.request, "urlopen", side_effect=response), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, checks.advisories(self.inventory, self.report), "The final coordinate's current finding must fail the scan")
        self.assertEqual(expected, sent, "OSV transport must query every coordinate exactly once, including the final item")
        self.assertEqual([25, 25, 1], sizes)
        report = json.loads(self.report.read_text())
        self.assertEqual(expected, report["queried"])
        self.assertEqual([{"coordinate": "example.synthetic:batch-47:1.0", "configurations": [":app:project:releaseRuntimeClasspath"],
                           "id": "SYNTHETIC-TAIL"}], report["findings"])
        self.assertEqual([], report["dispositions"])
        self.assertEqual(["https://api.osv.dev/v1/vulns/SYNTHETIC-TAIL"], records)
        self.assertEqual({"SYNTHETIC-TAIL": {"id": "SYNTHETIC-TAIL"}}, report["records"])

    def test_osv_timeout_retry_preserves_request_and_uses_short_backoff(self):
        """TOOL_OSV_RETRY_DELAY; simulated HTTP failures do not prove live service reliability."""
        payload = {"queries": [{"package": {"ecosystem": "Maven", "name": "example.synthetic:test-only"}, "version": "1.0"}]}
        for failure in [TimeoutError("controlled timeout"), urllib.error.URLError(TimeoutError("controlled wrapped timeout"))]:
            with self.subTest(failure=type(failure).__name__):
                with patch.object(checks.urllib.request, "urlopen", side_effect=[failure, failure, io.StringIO('{"results": [{}]}')]) as transport, \
                        patch("time.sleep") as pause:
                    self.assertEqual({"results": [{}]}, checks.osv_json("https://api.osv.dev/v1/querybatch", payload))
                self.assertEqual(3, transport.call_count, "Two timeouts must still allow the third attempt")
                self.assertEqual([(1,), (2,)], [call.args for call in pause.call_args_list], "OSV timeout backoff must be exactly 1 then 2 seconds")
                for call in transport.call_args_list:
                    self.assertEqual("https://api.osv.dev/v1/querybatch", call.args[0].full_url)
                    self.assertEqual(payload, json.loads(call.args[0].data))

    def test_osv_timeout_exhaustion_propagates_without_a_clean_report(self):
        """TOOL_OSV_RETRY_LIMIT: change the production attempt limit from 3 to 4."""
        self.inventory.write_text(json.dumps(self.data), encoding="utf-8")
        for failure in [TimeoutError("controlled timeout"), urllib.error.URLError(TimeoutError("controlled wrapped timeout"))]:
            with self.subTest(failure=type(failure).__name__):
                with patch.object(checks.urllib.request, "urlopen", side_effect=failure) as transport, patch("time.sleep") as pause:
                    with self.assertRaises(type(failure)) as raised:
                        checks.advisories(self.inventory, self.report)
                self.assertIs(failure, raised.exception, "Exhausted OSV timeouts must propagate unchanged")
                self.assertEqual(3, transport.call_count, "OSV timeout retries must stop after exactly 3 attempts")
                self.assertEqual([(1,), (2,)], [call.args for call in pause.call_args_list])
                self.assertFalse(self.report.exists(), "An incomplete OSV scan must not create a clean report")

    def test_osv_does_not_retry_http_or_tls_verification_failures(self):
        """TOOL_OSV_RETRY_NON_TIMEOUT: remove the timeout-type condition."""
        failures = [urllib.error.HTTPError("https://api.osv.dev/v1/querybatch", 400, "controlled bad request", {}, None),
                    ssl.SSLCertVerificationError("controlled certificate failure"),
                    urllib.error.URLError(ssl.SSLCertVerificationError("controlled wrapped certificate failure"))]
        for failure in failures:
            with self.subTest(failure=repr(failure)):
                with patch.object(checks.urllib.request, "urlopen", side_effect=failure) as transport, patch("time.sleep") as pause:
                    with self.assertRaises(type(failure)) as raised:
                        checks.osv_json("https://api.osv.dev/v1/querybatch", {"queries": []})
                self.assertIs(failure, raised.exception)
                self.assertEqual(1, transport.call_count, "Non-timeout HTTP/TLS failures must not be retried")
                pause.assert_not_called()

    def test_paginated_batch_is_rejected_until_all_pages_can_be_scanned(self):
        """Plant TOOL_OSV_PAGINATION_IGNORED: bypass the next-page guard."""
        with self.assertRaisesRegex(ValueError, "paginated"):
            self.run_advisories([{"results": [{"next_page_token": "synthetic-page-two"}]}])

    def test_empty_inventory_is_rejected(self):
        """TOOL_OSV_EMPTY_INVENTORY pins diagnostics; required-tool coverage also rejects emptiness."""
        self.data["components"] = []
        with self.assertRaisesRegex(ValueError, "inventory is incomplete"):
            self.run_advisories([])

    def test_unresolved_inventory_is_rejected(self):
        """Plant TOOL_OSV_UNRESOLVED_ALLOWED: remove failures from the inventory guard."""
        self.data["failures"] = [{"configuration": ":app:project:releaseRuntimeClasspath"}]
        with self.assertRaisesRegex(ValueError, "inventory is incomplete"):
            self.run_advisories([{"results": [{}]}])

    def test_short_batch_is_rejected(self):
        """Plant TOOL_OSV_SHORT_BATCH: remove the batch length comparison."""
        with self.assertRaisesRegex(ValueError, "incomplete batch"):
            self.run_advisories([{"results": []}])

    def test_missing_provenance_is_rejected(self):
        """Plant TOOL_OSV_NO_SCOPE: replace the component scope guard with False."""
        self.component["configurations"] = []
        with self.assertRaisesRegex(ValueError, "configuration provenance"):
            self.run_advisories([{"results": [{}]}])

    def test_wrong_record_is_rejected(self):
        """Plant TOOL_OSV_WRONG_RECORD: replace the record identity check with False."""
        with self.assertRaisesRegex(ValueError, "different vulnerability record"):
            self.run_advisories([{"results": [{"vulns": [{"id": "SYNTHETIC-CURRENT"}]}]},
                                 {"id": "SYNTHETIC-OTHER", "withdrawn": "2020-01-01T00:00:00Z"}])

    def test_incomplete_build_coverage_is_rejected(self):
        """Plants TOOL_COVERAGE_FORMAT, TASKS, TOOLS, PROJECTS, PLUGINS_TASKS, CONFIG_IDS."""
        cases = []
        for field in ["format", "taskOutcomes", "observedResolutions", "projects"]:
            changed = copy.deepcopy(self.data)
            del changed[field]
            cases.append((field, changed))
        for field in ["pluginClasses", "tasks", "configurations", "pluginIds"]:
            changed = copy.deepcopy(self.data)
            del changed["projects"][1][field]
            cases.append((field, changed))
        for tool in ["lint-gradle", "aapt2"]:
            changed = copy.deepcopy(self.data)
            changed["components"] = [item for item in changed["components"] if item["name"] != tool]
            cases.append((tool, changed))
        for field, value in [("successful", False), ("noSource", True), ("skipped", True)]:
            changed = copy.deepcopy(self.data)
            changed["taskOutcomes"][0][field] = value
            cases.append((field, changed))
        for name, data in cases:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Dependency inventory lacks"):
                checks.validate_build_coverage(data)

    def test_exact_kapt_match_kept_with_non_applicable_disposition(self):
        """Positive control: the exact reviewed match remains visible in the report."""
        self.component.update(group="org.jetbrains.kotlin", name="kotlin-gradle-plugin", version="2.0.21")
        self.assertEqual(0, self.run_advisories([
            {"results": [{"vulns": [{"id": "GHSA-r937-wjx7-w2jp"}]}]}, {"id": "GHSA-r937-wjx7-w2jp"}]))
        report = json.loads(self.report.read_text())
        self.assertEqual(["GHSA-r937-wjx7-w2jp"], [item["id"] for item in report["findings"]])
        self.assertEqual(["not_applicable"], [item["disposition"] for item in report["dispositions"]])

    def test_kapt_disposition_does_not_cover_another_match_or_coordinate(self):
        """Plants TOOL_KAPT_ADVISORY_WIDENED and TOOL_KAPT_COORDINATE_WIDENED."""
        self.component.update(group="org.jetbrains.kotlin", name="kotlin-gradle-plugin", version="2.0.21")
        self.assertEqual(1, self.run_advisories([
            {"results": [{"vulns": [{"id": "GHSA-r937-wjx7-w2jp"}, {"id": "SYNTHETIC-OTHER"}]}]},
            {"id": "GHSA-r937-wjx7-w2jp"}, {"id": "SYNTHETIC-OTHER"}]))
        for field, value in [("group", "example.synthetic"), ("name", "different-module"), ("version", "2.0.22")]:
            original = self.component[field]
            self.component[field] = value
            with self.subTest(field=field):
                self.assertEqual(1, self.run_advisories([
                    {"results": [{"vulns": [{"id": "GHSA-r937-wjx7-w2jp"}]}]}, {"id": "GHSA-r937-wjx7-w2jp"}]))
                self.assertEqual([], json.loads(self.report.read_text())["dispositions"])
            self.component[field] = original

    def test_kapt_presence_or_missing_ksp_proof_blocks_disposition(self):
        """Plants TOOL_KAPT_IDS, CLASSES_CONFIGS, TASKS, COMPONENTS and TOOL_KSP_POSITIVE_CONTROL."""
        self.component.update(group="org.jetbrains.kotlin", name="kotlin-gradle-plugin", version="2.0.21")
        pristine = copy.deepcopy(self.data)
        cases = []
        for plugin in ["org.jetbrains.kotlin.kapt", "kotlin-kapt"]:
            for value in [True, None]:
                changed = copy.deepcopy(pristine)
                changed["projects"][1]["pluginIds"][plugin] = value
                cases.append((plugin + str(value), changed))
        for field, entry in [("pluginClasses", "org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin"),
                             ("configurations", "kaptDebug")]:
            changed = copy.deepcopy(pristine)
            changed["projects"][1][field].append(entry)
            cases.append((field, changed))
        for task in [{"name": "kaptDebugKotlin", "type": "synthetic.Task"},
                     {"name": "renamedTask", "type": "org.jetbrains.kotlin.gradle.internal.KaptTask"}]:
            changed = copy.deepcopy(pristine)
            changed["projects"][1]["tasks"].append(task)
            cases.append((task["name"], changed))
        for component in ["kotlin-kapt", "kotlin-annotation-processing-embeddable"]:
            changed = copy.deepcopy(pristine)
            changed["components"].append({"group": "org.jetbrains.kotlin", "name": component, "version": "2.0.21", "configurations": [":app:project:kapt"]})
            cases.append((component, changed))
        for field, value in [("pluginClasses", ["synthetic.OtherPlugin"]), ("configurations", ["releaseRuntimeClasspath"]),
                             ("tasks", [{"name": "otherTask", "type": "synthetic.Task"}])]:
            changed = copy.deepcopy(pristine)
            changed["projects"][1][field] = value
            cases.append(("missing KSP " + field, changed))
        changed = copy.deepcopy(pristine)
        changed["projects"][1]["pluginIds"]["com.google.devtools.ksp"] = False
        cases.append(("missing KSP id", changed))
        for name, data in cases:
            self.data = data
            with self.subTest(name=name):
                self.assertEqual(1, self.run_advisories([
                    {"results": [{"vulns": [{"id": "GHSA-r937-wjx7-w2jp"}]}]}, {"id": "GHSA-r937-wjx7-w2jp"}]))
                self.assertEqual([], json.loads(self.report.read_text())["dispositions"])

    def test_secret_finding_fails_with_clean_history_and_redaction_is_requested(self):
        """Plant TOOL_SECRET_FINDING_ALLOWED: replace the nonzero scan-exit check with False."""
        (self.base / "README.md").write_text("synthetic source")
        executable = self.base / "gitleaks.exe"
        executable.write_bytes(b"test fixture only; never executed")
        results = [subprocess.CompletedProcess([], 0, "8.30.1\n", ""),
                   subprocess.CompletedProcess([], 0, b"README.md\0", b""),
                   subprocess.CompletedProcess([], 1, b"", b""),
                   subprocess.CompletedProcess([], 0, b"", b"")]
        with patch.object(checks, "ROOT", self.base), patch.object(checks.subprocess, "run", side_effect=results) as runner:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, checks.secrets(executable, self.base / "reports"))
        self.assertEqual(4, runner.call_count)
        for call in runner.call_args_list[2:]:
            self.assertIn("--redact=100", call.args[0])


if __name__ == "__main__":
    unittest.main()
