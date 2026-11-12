"""Portable wrapper, redacted secret, and resolved-Maven advisory checks (Python 3.11+).

No checks write to the source candidate. Reports belong outside the commit.
These checks complement native-library review and do not prove absence of vulnerabilities.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
GRADLE_SHA = "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
WRAPPER_HASHES = {
    "gradlew": "c76f16038b4e70b84ed0b3b9e08e82a8ecaf66ec0efdfdecdece92520fa3cecb",
    "gradlew.bat": "0f3ed8f03b50934cb8c48b15a470d5c20a30a5385825e48b55bcc8ea3d8f8e18",
    "gradle/wrapper/gradle-wrapper.jar": "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f",
}


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")


def wrapper() -> int:
    for name, expected in WRAPPER_HASHES.items():
        data = (ROOT / name).read_bytes()
        if name != "gradle/wrapper/gradle-wrapper.jar":
            data = data.replace(b"\r\n", b"\n")
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f"Pinned Gradle 8.13 wrapper checksum differs: {name}")
    properties = {}
    for line in (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            if key in properties:
                raise ValueError("Duplicate wrapper property")
            properties[key.strip()] = value.strip()
    if properties.get("distributionSha256Sum") != GRADLE_SHA:
        raise ValueError("Pinned Gradle 8.13 distribution checksum differs")
    if properties.get("distributionUrl") != r"https\://services.gradle.org/distributions/gradle-8.13-bin.zip":
        raise ValueError("Gradle distribution URL differs from the pinned official distribution")
    if not (ROOT / "gradle/verification-metadata.xml").is_file():
        raise ValueError("Dependency verification metadata is missing")
    print("PASS: official Gradle 8.13 wrapper and distribution checksums")
    return 0


def secrets(binary: Path, reports: Path) -> int:
    binary = binary.resolve(strict=True)
    reports.mkdir(parents=True, exist_ok=True)
    version = subprocess.run([str(binary), "version"], check=True, capture_output=True, text=True).stdout.strip()
    if version != "8.30.1":
        raise ValueError("Expected checksum-verified Gitleaks 8.30.1")
    listing = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                             cwd=ROOT, check=True, capture_output=True).stdout
    names = sorted(set(name.decode("utf-8") for name in listing.split(b"\0") if name))
    if not names:
        raise ValueError("Candidate inventory is empty")
    manifest = []
    with tempfile.TemporaryDirectory(prefix="duck-secret-scan-") as temporary:
        candidate = Path(temporary)
        for name in names:
            source = ROOT / name
            if not source.exists():
                continue  # Tracked deletion: history is still scanned below.
            if source.is_symlink() or not source.resolve().is_relative_to(ROOT) or not source.is_file():
                raise ValueError("Candidate contains a symlink, special file, or path outside the repository")
            destination = candidate / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
            manifest.append({"path": name, "sha256": hashlib.sha256(source.read_bytes()).hexdigest()})
        if not manifest:
            raise ValueError("Candidate export contains no files")
        statuses = []
        for label, command in [
            ("candidate", ["dir", str(candidate)]),
            ("history", ["git", str(ROOT), "--log-opts=--all"]),
        ]:
            result = subprocess.run([str(binary), *command, "--redact=100", "--no-banner", "--report-format=json",
                                     f"--report-path={reports / (label + '-secrets.json')}"],
                                    cwd=ROOT, capture_output=True)
            (reports / (label + "-secrets.log")).write_bytes(result.stdout + result.stderr)
            statuses.append({"scope": label, "exitCode": result.returncode})
        write_json(reports / "secret-scan-summary.json", {"gitleaks": version, "candidate": manifest, "runs": statuses})
    if any(status["exitCode"] != 0 for status in statuses):
        print("FAIL: secret scan found a finding or tool error; inspect redacted reports", file=sys.stderr)
        return 1
    print(f"PASS: redacted Gitleaks candidate ({len(manifest)} files) and complete Git history scans")
    return 0


def osv_json(url: str, payload: object | None = None) -> dict:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json", "User-Agent": "DuckNotes-security-check/1"})
    attempt = 0
    while True:
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except (TimeoutError, urllib.error.URLError) as failure:
            attempt += 1
            reason = failure.reason if isinstance(failure, urllib.error.URLError) else failure
            if attempt == 3 or not isinstance(reason, TimeoutError):
                raise
            time.sleep(attempt)


def validate_build_coverage(data: dict) -> None:
    required_tasks = {":app:testDebugUnitTest", ":app:testReleaseUnitTest", ":app:lint",
                      ":app:assembleDebug", ":app:assembleRelease", ":app:assembleDebugAndroidTest"}
    successful_tasks = {item["path"] for item in data.get("taskOutcomes", [])
                        if item.get("successful") is True and item.get("noSource") is False and
                        (item.get("skipped") is False or item.get("upToDate") is True)}
    if data.get("format") != 2 or not required_tasks.issubset(successful_tasks):
        raise ValueError("Dependency inventory lacks the complete build/test/lint task coverage")
    observations = {item["configuration"] for item in data.get("observedResolutions", []) if item.get("completed") is True}
    for group, name in [("com.android.tools.lint", "lint-gradle"), ("com.android.tools.build", "aapt2")]:
        if not any(item["group"] == group and item["name"] == name and
                   observations.intersection(item.get("configurations", [])) for item in data["components"]):
            raise ValueError("Dependency inventory lacks observed lint or AAPT tool resolution")
    projects = data.get("projects", [])
    if {project.get("path") for project in projects} != {":", ":app"}:
        raise ValueError("Dependency inventory lacks complete project evidence")
    for project in projects:
        if not project.get("pluginClasses") or not project.get("tasks"):
            raise ValueError("Dependency inventory lacks plugin or task evidence")
        if not isinstance(project.get("configurations"), list) or not project.get("pluginIds"):
            raise ValueError("Dependency inventory lacks configuration or plugin ID evidence")


def kapt_is_absent(data: dict) -> bool:
    # This applies only to the exact KAPT-cache advisory below, never to Kotlin as a package.
    projects = data["projects"]
    for project in projects:
        ids = project["pluginIds"]
        if ids.get("org.jetbrains.kotlin.kapt") is not False or ids.get("kotlin-kapt") is not False:
            return False
        if any("kapt" in name.lower() for name in project["pluginClasses"] + project["configurations"]):
            return False
        if any("kapt" in (task["name"] + task["type"]).lower() for task in project["tasks"]):
            return False
    if any(item["group"] == "org.jetbrains.kotlin" and
           ("kapt" in item["name"].lower() or item["name"].startswith("kotlin-annotation-processing"))
           for item in data["components"]):
        return False
    app = next(project for project in projects if project["path"] == ":app")
    return (app["pluginIds"].get("com.google.devtools.ksp") is True and
            any(".KspGradleSubplugin" in name for name in app["pluginClasses"]) and
            "kspPluginClasspath" in app["configurations"] and
            any(task["name"].startswith("ksp") and "Ksp" in task["type"] for task in app["tasks"]))


def advisories(inventory: Path, report: Path) -> int:
    data = json.loads(inventory.read_text(encoding="utf-8-sig"))
    components = data.get("components", [])
    if data.get("failures") or not components or not data.get("configurations"):
        raise ValueError("Resolved dependency inventory is incomplete")
    validate_build_coverage(data)
    findings = []
    dispositions = []
    queried = []
    records = {}
    for offset in range(0, len(components), 25):
        batch = components[offset:offset + 25]
        queries = [{"package": {"ecosystem": "Maven", "name": f"{item['group']}:{item['name']}"}, "version": item["version"]} for item in batch]
        results = osv_json("https://api.osv.dev/v1/querybatch", {"queries": queries}).get("results")
        if not isinstance(results, list) or len(results) != len(batch):
            raise ValueError("OSV returned an incomplete batch")
        for component, result in zip(batch, results):
            if result.get("next_page_token"):
                raise ValueError("OSV returned a paginated result; advisory scan is incomplete")
            if not component.get("configurations"):
                raise ValueError("Dependency lacks configuration provenance")
            coordinate = f"{component['group']}:{component['name']}:{component['version']}"
            queried.append(coordinate)
            for match in result.get("vulns", []):
                identifier = match["id"]
                if identifier not in records:
                    records[identifier] = osv_json("https://api.osv.dev/v1/vulns/" + urllib.parse.quote(identifier, safe=""))
                record = records[identifier]
                if record.get("id") != identifier:
                    raise ValueError("OSV returned a different vulnerability record")
                if record.get("withdrawn"):
                    continue
                finding = {"coordinate": coordinate, "configurations": component["configurations"], "id": identifier}
                findings.append(finding)
                if (identifier == "GHSA-r937-wjx7-w2jp" and
                        coordinate == "org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21" and kapt_is_absent(data)):
                    dispositions.append({**finding, "disposition": "not_applicable",
                                         "reason": "The fixed code is KAPT incremental-cache deserialization; evaluated projects use KSP and contain no KAPT plugin, configuration, task, or processor component.",
                                         "source": "https://github.com/JetBrains/kotlin/commit/bf51df665b458fda7c3eaf436c4d88dc119d7ec6"})
    write_json(report, {"provider": "https://osv.dev/", "queriedAtUtc": datetime.now(timezone.utc).isoformat(), "inventorySha256": hashlib.sha256(inventory.read_bytes()).hexdigest(), "coverage": "Resolved Maven coordinates only; native code and platform/toolchain require separate review", "queried": queried, "findings": findings, "dispositions": dispositions, "records": records})
    print(f"OSV: queried {len(queried)} resolved Maven coordinates; {len(findings)} current advisory matches; {len(dispositions)} exact non-applicable dispositions")
    return 1 if len(findings) != len(dispositions) else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("wrapper")
    secret_parser = commands.add_parser("secrets")
    secret_parser.add_argument("--gitleaks", type=Path, required=True)
    secret_parser.add_argument("--reports", type=Path, required=True)
    advisory_parser = commands.add_parser("advisories")
    advisory_parser.add_argument("--inventory", type=Path, required=True)
    advisory_parser.add_argument("--report", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        if arguments.command == "wrapper":
            return wrapper()
        if arguments.command == "secrets":
            return secrets(arguments.gitleaks, arguments.reports)
        return advisories(arguments.inventory, arguments.report)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError):
        # Do not print exception bodies: HTTP/resolver/tool diagnostics can contain sensitive data.
        print("FAIL: security check could not complete; no clean result is claimed", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
