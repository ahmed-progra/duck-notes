"""Settings completion gate. Full/candidate modes clear the selected disposable app.

No emulator is started. Use an already booted disposable emulator, serialize source/
build/device access, and keep --out outside the repository. Flake mode uses one
already installed build, without rebuilding, reinstalling or replacing failures.
"""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
GRADLE = Path(r"C:\Users\zconsumers\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat")
JAVA = Path(r"C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot")
ADB = Path(os.environ.get("LOCALAPPDATA", r"C:\Users\zconsumers\AppData\Local")) / "Android/Sdk/platform-tools/adb.exe"
PACKAGE = "app.duck.notes"
SETTINGS_RUNNER = PACKAGE + ".test/app.duck.notes.settings.SettingsTestRunner"
STARTUP = PACKAGE + ".startup.WelcomeStartupTest"
STARTUP_ID = STARTUP + "#realApplicationSeedsOrdinaryNotesAndRetainsItsDecision"
STARTUP_RUNNER = PACKAGE + ".test/app.duck.notes.startup.WelcomeStartupTestRunner"
EXISTING_RUNNER = PACKAGE + ".test/app.duck.notes.startup.WelcomeExistingInstallTestRunner"
COUNTS = ("tests", "failures", "errors", "skipped")
TEST_PATTERN = r"^\s*@(?:org\.junit\.)?Test\b"
RETAINED = {"after_delete": "fresh", "existing": "prepare_existing", "existing_populated": "prepare_existing_populated"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def save(path, value):
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def artifact(path):
    stat = path.stat()
    return dict(path=str(path), size=stat.st_size, mtime_ns=stat.st_mtime_ns,
                modified_utc=datetime.datetime.fromtimestamp(stat.st_mtime, datetime.timezone.utc).isoformat(),
                sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def sources():
    result = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
                            cwd=ROOT, capture_output=True, check=True)
    return {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
            for name in sorted(set(result.stdout.decode("utf-8").split("\0")) - {""})
            if (ROOT / name).is_file()}


def discover_tests(directory, output):
    """Count anchored source annotations, then require one exact unique method per hit."""
    command = ["rg", "--json", "--glob", "*.kt", TEST_PATTERN, str(directory)]
    run = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    (output / "source-test-discovery.log").write_text(run.stdout + run.stderr, encoding="utf-8")
    require(run.returncode == 0, "No source tests found, or anchored rg discovery failed")
    ids, hits = [], []
    for line in run.stdout.splitlines():
        item = json.loads(line)
        if item["type"] != "match":
            continue
        data = item["data"]
        path = Path(data["path"]["text"])
        source = path.read_text(encoding="utf-8-sig")
        package = re.findall(r"^package\s+([\w.]+)\s*$", source, re.M)
        classes = re.findall(r"^(?:(?:public|internal|open|abstract)\s+)*class\s+(\w+)\b", source, re.M)
        require(len(package) == len(classes) == 1, "Ambiguous test owner: " + str(path))
        tail = "\n".join(source.splitlines()[data["line_number"] - 1:])
        method = re.search(r"\bfun\s+([A-Za-z_]\w*)\s*\(", tail)
        annotation = re.match(TEST_PATTERN, tail)
        require(method is not None and annotation is not None
                and not re.search(TEST_PATTERN, tail[annotation.end():method.start()], re.M),
                "Annotation has no distinct test method: " + str(path))
        identity = package[0] + "." + classes[0] + "#" + method[1]
        ids.append(identity)
        hits.append(dict(path=str(path), line=data["line_number"], id=identity))
    require(ids and len(ids) == len(set(ids)), "Empty or duplicate source test methods")
    value = dict(command=command, anchored_annotations=len(hits), unique_methods=len(set(ids)),
                 ids=sorted(ids), matches=hits)
    save(output / "source-test-discovery.json", value)
    return set(ids)


def ordinary_selection(ids, candidate=False):
    if candidate:
        require(not any(name.startswith(PACKAGE + ".startup.") for name in ids),
                "Settings candidate must contain no welcome startup tests")
        return ids, [SETTINGS_RUNNER]
    require(STARTUP_ID in ids and sum(name.startswith(STARTUP + "#") for name in ids) == 1,
            "Full gate requires exactly the frozen production startup method")
    return ids - {STARTUP_ID}, ["-e", "notClass", STARTUP, SETTINGS_RUNNER]


def candidate_welcome_absent(directory):
    offenders = []
    for path in sorted(directory.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(directory)
        if ("startup" in relative.parts or "welcome" in path.name.lower()
                or (path.suffix == ".kt" and re.search(
                    # HEAD's ordinary note/title fixtures contain the bare word Welcome.
                    r"\b(?:Welcome[A-Z]\w*|SeedWelcomeNotes|welcomeStartup|welcomeSeedingEnabled)\b",
                    path.read_text(encoding="utf-8-sig")))):
            offenders.append(str(relative))
    require(not offenders, "Settings candidate contains welcome source: " + repr(offenders))


def parse_instrumentation(raw, expected, expected_ids=None):
    fields, records, starts, codes, issues = {}, [], [], [], []
    stack, active, footer_started = False, None, False
    lines = raw.splitlines()
    for line in lines:
        if line.startswith("INSTRUMENTATION_STATUS: "):
            if footer_started:
                issues.append("test status after footer")
            item = line[len("INSTRUMENTATION_STATUS: "):]
            if "=" in item:
                key, value = item.split("=", 1)
                if key in fields:
                    issues.append("duplicate status field: " + key)
                fields[key] = value
                stack = key == "stack"
            else:
                issues.append("malformed status field")
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            if footer_started:
                issues.append("test status after footer")
            try:
                code = int(line.split(":", 1)[1])
            except ValueError:
                issues.append("malformed status code")
                fields, stack = {}, False
                continue
            codes.append(code)
            if code not in (1, 0, -1, -2, -3, -4):
                issues.append("unknown status code: " + str(code))
            identity = fields.get("class", "") + "#" + fields.get("test", "")
            if not fields.get("class") or not fields.get("test"):
                issues.append("status missing class/test")
            if code == 1:
                if active is not None:
                    issues.append("test started before previous terminal")
                active = identity
                starts.append(identity)
            else:
                if active != identity:
                    issues.append("terminal without matching active start")
                active = None
                records.append(dict(fields, id=identity, status_code=code))
            fields, stack = {}, False
        elif line.startswith(("OK (", "Tests run:", "FAILURES!!!", "INSTRUMENTATION_CODE:")):
            if fields or active is not None:
                issues.append("footer before test completion")
            footer_started = True
        elif stack and not line.startswith("INSTRUMENTATION_"):
            fields["stack"] += "\n" + line
    footer_codes = re.findall(r"^INSTRUMENTATION_CODE:[ \t]*(-?\d+)[ \t\r]*$", raw, re.M)
    footer_lines = [line for line in raw.splitlines() if line.startswith("INSTRUMENTATION_CODE:")]
    ok = re.findall(r"^OK \((\d+) tests?\)[ \t\r]*$", raw, re.M)
    ok_lines = [line for line in raw.splitlines() if line.startswith("OK ")]
    red = re.findall(r"^(?:Tests run:|FAILURES!!!).*$", raw, re.M)
    problems = re.findall(r"^.*(?:INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|FATAL EXCEPTION|Process crashed).*$", raw, re.M | re.I)
    ids = [record["id"] for record in records]
    failures = [record for record in records if record["status_code"] == -2]
    errors = [record for record in records if record["status_code"] == -1]
    skipped = [record for record in records if record["status_code"] in (-3, -4)]
    if expected <= 0:
        issues.append("expected count must be positive")
    if footer_codes != ["-1"] or len(footer_lines) != 1:
        issues.append("missing/duplicate/unsuccessful INSTRUMENTATION_CODE")
    if len(ok) != 1 or len(ok_lines) != 1 or int(ok[0]) != expected or red:
        issues.append("missing/duplicate/red/mismatched OK footer")
    if len(footer_lines) == len(ok_lines) == 1 and next(index for index, line in enumerate(lines) if line.startswith(
            "INSTRUMENTATION_CODE:")) < next(index for index, line in enumerate(lines) if line.startswith("OK ")):
        issues.append("INSTRUMENTATION_CODE before JUnit footer")
    if len(records) != expected:
        issues.append("terminal count mismatch")
    if starts != ids:
        issues.append("start/terminal identity or count mismatch")
    if len(ids) != len(set(ids)):
        issues.append("duplicate terminal method")
    if fields:
        issues.append("truncated status bundle")
    if active is not None:
        issues.append("test missing terminal")
    if failures or errors or skipped:
        issues.append("nonzero failures/errors/skips")
    if problems:
        issues.append("instrumentation crash/abort")
    if expected_ids is not None and set(ids) != set(expected_ids):
        issues.append("method identity mismatch")
    return dict(accepted=not issues, expected=expected, tests=len(records), passed=codes.count(0),
                failed=len(failures), errors=len(errors), skipped=len(skipped), issues=issues,
                instrumentation_codes=footer_codes, status_codes=codes, ok_counts=[int(n) for n in ok],
                records=records, failures=failures, error_records=errors, skipped_records=skipped,
                problems=problems, footer_lines=[line for line in raw.splitlines() if line.startswith(
                    ("OK (", "Tests run:", "FAILURES!!!", "INSTRUMENTATION_CODE:", "INSTRUMENTATION_RESULT:"))])


def normalized(output, label, parsed):
    save(output / (label + ".json"), parsed)
    suite = ET.Element("testsuite", name=label, tests=str(parsed["tests"]), failures=str(parsed["failed"]),
                       errors=str(parsed["errors"]), skipped=str(parsed["skipped"]))
    for record in parsed["records"]:
        case = ET.SubElement(suite, "testcase", classname=record.get("class", ""), name=record.get("test", ""))
        code = record["status_code"]
        if code < 0:
            tag = "failure" if code == -2 else "skipped" if code in (-3, -4) else "error"
            ET.SubElement(case, tag).text = record.get("stack", "status code " + str(code))
    if parsed["issues"]:
        ET.SubElement(suite, "system-err").text = "\n".join(parsed["issues"])
    ET.indent(suite)
    ET.ElementTree(suite).write(output / (label + ".xml"), encoding="utf-8", xml_declaration=True)


def device_plan(candidate=False):
    plan = [dict(action="clear"), dict(action="instrument", label="ordinary", runner=SETTINGS_RUNNER)]
    if not candidate:
        for scenario in ("fresh", "after_delete", "prepare_existing", "existing", "prepare_existing_populated", "existing_populated", "link_findings"):
            if scenario not in RETAINED:
                plan.append(dict(action="clear"))
            plan.append(dict(action="instrument", label=scenario,
                             runner=EXISTING_RUNNER if scenario.startswith("prepare_existing") else STARTUP_RUNNER))
    validate_retention(plan)
    return plan


def validate_retention(plan):
    previous = None
    intervening = []
    for step in plan:
        if step["action"] == "instrument":
            label = step["label"]
            if label in RETAINED:
                require(previous == RETAINED[label] and not set(intervening) & {"clear", "install"},
                        "Retention pair cleared/reinstalled or preparation missing: " + label)
            previous, intervening = label, []
        else:
            intervening.append(step["action"])


def environment():
    require(GRADLE.is_file() and (JAVA / "bin/java.exe").is_file() and ADB.is_file(), "Missing absolute Gradle/JDK21/adb binary")
    release = (JAVA / "release").read_text(encoding="utf-8")
    require(re.search(r'^JAVA_VERSION="21(?:\.|\")', release, re.M), "Selected Java is not JDK21")
    sockets = Path.home() / ".gradle/afunix-sockets"
    sockets.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["JAVA_HOME"] = str(JAVA)
    env["PATH"] = str(JAVA / "bin") + os.pathsep + env.get("PATH", "")
    env["JAVA_TOOL_OPTIONS"] = "-Djdk.net.unixdomain.tmpdir=" + str(sockets)
    return env


def native(command, label, output, env, timeout=1800):
    log = output / (label + ".log")
    result = dict(command=[str(part) for part in command], log=str(log), started_ns=time.time_ns())
    print("START " + label, flush=True)
    with log.open("wb") as stream:
        process = subprocess.Popen(result["command"], cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
        try:
            result["exit_code"] = process.wait(timeout=timeout)
        except BaseException as error:
            subprocess.run(["taskkill.exe", "/PID", str(process.pid), "/T", "/F"], capture_output=True, check=False)
            process.wait(timeout=30)
            result.update(exit_code=process.returncode, interrupted=repr(error))
    result["elapsed_seconds"] = round((time.time_ns() - result["started_ns"]) / 1e9, 3)
    save(output / (label + "-native.json"), result)
    return result, log.read_text(encoding="utf-8-sig", errors="replace")


def task_executed(raw, task):
    states = re.findall(r"^> Task " + re.escape(task) + r"(?=\s|$)([^\r\n]*)", raw, re.M)
    require(len(states) == 1 and not states[0].strip(), "Task was not freshly executed: " + task + ": " + repr(states))


def xml_summary(path):
    suite = ET.parse(path).getroot()
    counts = {key: int(suite.attrib[key]) for key in COUNTS}
    cases = suite.findall("testcase")
    require(counts["tests"] == len(cases), "JVM testcase tally mismatch: " + str(path))
    for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
        require(counts[key] == sum(case.find(tag) is not None for case in cases), "JVM status tally mismatch: " + str(path))
    ids = [case.get("classname", suite.attrib["name"]) + "#" + case.attrib["name"] for case in cases]
    return dict(name=suite.attrib["name"], counts=counts, ids=ids)


def fresh_xml(directory, before, started, output):
    copied = output / "unit-xml"
    copied.mkdir()
    suites, identities = [], []
    for path in sorted(directory.glob("TEST-*.xml")):
        stamp = path.stat().st_mtime_ns
        require(stamp >= started and stamp > before.get(str(path), 0), "Stale JVM XML: " + str(path))
        summary = xml_summary(path)
        identities.extend(summary["ids"])
        shutil.copy2(path, copied / path.name)
        suites.append(dict(source=artifact(path), archive=artifact(copied / path.name), **summary))
    totals = {key: sum(suite["counts"][key] for suite in suites) for key in COUNTS}
    result = dict(suites=suites, totals=totals, ids=sorted(identities))
    save(output / "unit-tally.json", result)
    require(totals["tests"] > 0 and len(identities) == len(set(identities)), "Empty or duplicate JVM tests")
    require(not any(totals[key] for key in COUNTS[1:]), "JVM failures/errors/skips: " + repr(totals))
    return result


def build(variant, output, env):
    output.mkdir()
    unit = ":app:test" + variant.title() + "UnitTest"
    packages = [":app:packageRelease"] if variant == "release" else [":app:packageDebug", ":app:packageDebugAndroidTest"]
    xml_dir = ROOT / "app/build/test-results" / unit.split(":")[-1]
    before = {str(path): path.stat().st_mtime_ns for path in xml_dir.glob("TEST-*.xml")}
    paths = [ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"] if variant == "release" else [
        ROOT / "app/build/outputs/apk/debug/app-debug.apk", ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
    apk_before = {str(path): path.stat().st_mtime_ns if path.exists() else 0 for path in paths}
    args = [item for task in [unit, *packages] for item in (task, "--rerun")]
    args += [":app:assembleRelease"] if variant == "release" else [":app:assembleDebug", ":app:assembleDebugAndroidTest"]
    command = ["cmd.exe", "/d", "/c", GRADLE, "-p", ROOT, *args, "--offline", "--daemon", "--max-workers=1",
               "-Dorg.gradle.jvmargs=-Xmx2048m -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=256m -Dfile.encoding=UTF-8",
               "-Dorg.gradle.daemon.idletimeout=10000", "--no-build-cache", "--console=plain"]
    run, raw = native(command, "build", output, env)
    require(run["exit_code"] == 0 and "interrupted" not in run, "Build failed; stale APKs must not be installed")
    for task in [unit, *packages]:
        task_executed(raw, task)
    tally = fresh_xml(xml_dir, before, run["started_ns"], output)
    apks = []
    for path in paths:
        require(path.stat().st_mtime_ns >= run["started_ns"] and path.stat().st_mtime_ns > apk_before[str(path)], "Stale APK: " + str(path))
        copy = output / path.name
        shutil.copy2(path, copy)
        apks.append(dict(source=artifact(path), archive=artifact(copy)))
    result = dict(native=run, unit=tally, apks=apks)
    save(output / "build.json", result)
    return result


def adb(arguments, label, output, env, serial, success=False, allow_absent=False):
    run, raw = native([ADB, "-s", serial, *arguments], label, output, env, timeout=180)
    require("interrupted" not in run and run["exit_code"] in ((0, 1) if allow_absent else (0,)), label + " failed")
    require(not success or re.search(r"^Success\s*$", raw, re.M), label + " missing Success")
    return raw


def instrument(label, arguments, expected_ids, output, env, serial):
    output.mkdir()
    previous = adb(["shell", "pidof", PACKAGE], "previous-pid", output, env, serial, allow_absent=True).split()
    adb(["shell", "am", "force-stop", PACKAGE], "force-stop", output, env, serial)
    require(not adb(["shell", "pidof", PACKAGE], "stopped-pid", output, env, serial, allow_absent=True).strip(), "Target did not stop")
    adb(["logcat", "-b", "all", "-c"], "clear-logcat", output, env, serial)
    run, raw = native([ADB, "-s", serial, "shell", "am", "instrument", "-w", "-r", *arguments], "instrument", output, env)
    parsed = parse_instrumentation(raw, len(expected_ids), expected_ids)
    if run["exit_code"] != 0 or "interrupted" in run:
        parsed["issues"].append("native instrumentation failure/interruption")
    logcat = adb(["logcat", "-b", "all", "-d", "-v", "threadtime"], "logcat", output, env, serial)
    pids = set(re.findall(r"Start proc (\d+):app\.duck\.notes(?:/|\s)", logcat))
    pids.update(re.findall(r"am_proc_start:.*\[\d+,(\d+),\d+,app\.duck\.notes,", logcat))
    parsed["process"] = dict(previous_pids=previous, started_pids=sorted(pids))
    if not pids or set(previous) & pids:
        parsed["issues"].append("no verified fresh target process")
    if label == "link_findings":
        for marker in ("LINK_FINDING_LATE_TARGET: before source save=0; after source save=1", "LINK_FINDING_RENAME: after target rename=1; after source save=0"):
            if marker not in raw + logcat:
                parsed["issues"].append("missing literal link finding: " + marker)
    parsed["accepted"] = not parsed["issues"]
    normalized(output, "instrument", parsed)
    return parsed


def installed_snapshot(label, output, env, serial):
    packages = {}
    for package in (PACKAGE, PACKAGE + ".test"):
        details = adb(["shell", "dumpsys", "package", package], label + "-" + package, output, env, serial)
        updated = re.findall(r"^[ \t]*lastUpdateTime=(.+)$", details, re.M)
        require(len(updated) == 1, "Missing/ambiguous installed package: " + package)
        raw_paths = adb(["shell", "pm", "path", package], label + "-paths-" + package, output, env, serial)
        paths = [line.removeprefix("package:") for line in raw_paths.splitlines()]
        require(paths and len(paths) == len(set(paths)) and all(
            line.startswith("package:/") for line in raw_paths.splitlines()), "Invalid installed APK paths: " + package)
        apks = {}
        for index, path in enumerate(paths):
            raw_hash = adb(["shell", "sha256sum", path], label + "-hash-" + package + "-" + str(index), output, env, serial)
            match = re.fullmatch(r"([0-9a-fA-F]{64})[ \t]+\*?" + re.escape(path) + r"[\r\n]*", raw_hash)
            require(match is not None, "Missing installed APK hash: " + path)
            apks[path] = match[1].lower()
        packages[package] = dict(last_update_time=updated[0].strip(), apks=apks)
    save(output / (label + "-installed.json"), packages)
    return packages


def validate_flake_build(manifest, current_sources, installed):
    require(Path(manifest["root"]).resolve() == ROOT and manifest.get("finished_utc"),
            "Flake build evidence must be a finished gate for this checkout")
    require(manifest["sources_before"] == manifest.get("sources_after") == current_sources,
            "Flake build source hashes do not match current checkout")
    debug = manifest["debug"]
    native_result = debug["native"]
    require(native_result["exit_code"] == 0 and "interrupted" not in native_result,
            "Flake build was unsuccessful/interrupted")
    for task in (":app:testDebugUnitTest", ":app:packageDebug", ":app:packageDebugAndroidTest"):
        task_executed(Path(native_result["log"]).read_text(encoding="utf-8-sig", errors="replace"), task)
    tally = debug["unit"]
    require(tally["totals"]["tests"] > 0 and not any(tally["totals"][key] for key in COUNTS[1:])
            and len(tally["ids"]) == tally["totals"]["tests"] == len(set(tally["ids"])),
            "Flake build JVM tests are empty/red/incomplete")
    require(tally["suites"], "Flake build has no JVM XML suites")
    totals, identities = {key: 0 for key in COUNTS}, []
    for suite in tally["suites"]:
        archived = suite["archive"]
        require(artifact(Path(archived["path"]))["sha256"] == archived["sha256"]
                and suite["source"]["mtime_ns"] >= native_result["started_ns"],
                "Flake build JVM evidence changed/stale")
        summary = xml_summary(Path(archived["path"]))
        require(all(summary[key] == suite[key] for key in ("name", "counts", "ids")),
                "Flake build JVM suite manifest mismatch")
        for key in COUNTS:
            totals[key] += summary["counts"][key]
        identities.extend(summary["ids"])
    require(totals == tally["totals"] and sorted(identities) == tally["ids"],
            "Flake build JVM aggregate manifest mismatch")
    require(len(debug["apks"]) == 2, "Flake build needs app and test APKs")
    for package, apk in zip((PACKAGE, PACKAGE + ".test"), debug["apks"]):
        archived = apk["archive"]
        require(artifact(Path(archived["path"]))["sha256"] == archived["sha256"]
                and apk["source"]["mtime_ns"] >= native_result["started_ns"],
                "Flake build APK evidence changed/stale")
        require(list(installed[package]["apks"].values()) == [archived["sha256"]],
                "Installed package differs from flake build evidence: " + package)


def execute(args, output, report):
    env = environment()
    report["environment"] = {key: env[key] for key in ("JAVA_HOME", "JAVA_TOOL_OPTIONS")}
    require(args.serial.startswith("emulator-"), "Select a disposable emulator serial")
    require(adb(["get-state"], "device-state", output, env, args.serial).strip() == "device", "Emulator is not online")
    require(adb(["shell", "getprop", "sys.boot_completed"], "boot-completed", output, env, args.serial).strip() == "1", "Emulator is not booted")
    ids = discover_tests(ROOT / "app/src/androidTest", output)
    if args.mode == "flake":
        require(args.method in ids, "Flake method is absent from source discovery")
        report["installed_before"] = installed_snapshot("before", output, env, args.serial)
        manifest = json.loads(args.build_evidence.read_text(encoding="utf-8-sig"))
        validate_flake_build(manifest, report["sources_before"], report["installed_before"])
        report["build_evidence"] = artifact(args.build_evidence)
        report["runs"] = []
        for index in range(1, 21):
            report["attempted_runs"] = index
            save(output / "results.json", report)
            result = instrument("flake", ["-e", "class", args.method, SETTINGS_RUNNER], {args.method}, output / ("run-%02d" % index), env, args.serial)
            report["runs"].append(result)
            try:
                result["installed_after"] = installed_snapshot("after", output / ("run-%02d" % index), env, args.serial)
            except Exception as error:
                result["installed_error"] = repr(error)
            if result.get("installed_after") != report["installed_before"]:
                result["issues"].append("Installed app/test APK tree changed during flake runs")
                result["accepted"] = False
            report["flake_totals"] = dict(requested=20, attempted=index, passed=sum(run["accepted"] for run in report["runs"]),
                                           failed_or_incomplete=sum(not run["accepted"] for run in report["runs"]),
                                           unresolved=any(not run["accepted"] for run in report["runs"]))
            save(output / "results.json", report)
            require(result.get("installed_after") == report["installed_before"], "Installed flake APK tree changed/unverified; remaining runs are unresolved")
        report["accepted"] = report["flake_totals"]["passed"] == 20
        return
    candidate = args.mode == "settings-candidate"
    if candidate:
        candidate_welcome_absent(ROOT / "app/src")
    ordinary_ids, ordinary_args = ordinary_selection(ids, candidate)
    plan = device_plan(candidate)
    report.update(source_methods=sorted(ids), source_method_count=len(ids), ordinary_expected=len(ordinary_ids), device_plan=plan)
    save(output / "results.json", report)
    report["debug"] = build("debug", output / "debug", env)
    require(sources() == report["sources_before"], "Sources changed during debug build")
    report["release"] = build("release", output / "release", env)
    require(report["debug"]["unit"]["ids"] == report["release"]["unit"]["ids"], "Debug/release JVM method sets differ")
    require(sources() == report["sources_before"], "Sources changed during release build")
    for index, apk in enumerate(report["debug"]["apks"]):
        adb(["install", "-r", "-t", apk["archive"]["path"]], "install-" + str(index), output, env, args.serial, success=True)
    report["instrumentation"] = {}
    for index, step in enumerate(plan):
        if step["action"] == "clear":
            adb(["shell", "am", "force-stop", PACKAGE], "clear-stop-" + str(index), output, env, args.serial)
            adb(["shell", "pm", "clear", PACKAGE], "clear-" + str(index), output, env, args.serial, success=True)
            continue
        label = step["label"]
        arguments = ordinary_args if label == "ordinary" else ["-e", "scenario", label, step["runner"]]
        expected = ordinary_ids if label == "ordinary" else {STARTUP_ID}
        report["instrumentation"][label] = instrument(label, arguments, expected, output / label, env, args.serial)
        save(output / "results.json", report)
    runs = report["instrumentation"].values()
    observed = {record["id"] for run in runs for record in run["records"]}
    executions = sum(run["tests"] for run in runs)
    report["counts"] = dict(source_unique=len(ids), observed_unique=len(observed), expected_unique=len(ids),
                            ordinary=len(report["instrumentation"]["ordinary"]["records"]),
                            dedicated_scenarios=0 if candidate else 7, dedicated_unique=0 if candidate else 1,
                            raw_executions=executions, expected_raw_executions=len(ids) if candidate else len(ids) + 6,
                            repeated_startup_executions=0 if candidate else 6)
    report["accepted"] = (all(run["accepted"] for run in runs) and observed == ids
                          and executions == report["counts"]["expected_raw_executions"])


def selfcheck():
    identity = "app.duck.notes.ExampleTest#one"
    checks = []
    def checked(name, condition):
        require(condition, "Selfcheck failed: " + name)
        checks.append(name)
    def rejects(name, operation, reason):
        try:
            operation()
        except ValueError as error:
            checked(name, reason in str(error))
        else:
            raise ValueError("Bad input accepted: " + name)
    def fixture(status=0, count=1, name="one"):
        return "\n".join(["INSTRUMENTATION_STATUS: class=app.duck.notes.ExampleTest\nINSTRUMENTATION_STATUS: test=" + name
                          + "\nINSTRUMENTATION_STATUS_CODE: " + str(code) for code in (1, status)]
                         + ["OK (" + str(count) + " tests)", "INSTRUMENTATION_CODE: -1", ""])
    green = fixture()
    checked("valid-instrumentation", parse_instrumentation(green, 1, {identity})["accepted"])
    protocol, footer = green.split("OK (", 1)
    footer = "OK (" + footer
    start, terminal = protocol.split("INSTRUMENTATION_STATUS_CODE: 1\n", 1)
    controls = {
        "red": (fixture(-2).replace("OK (1 tests)", "FAILURES!!!\nTests run: 1, Failures: 1"), "nonzero failures/errors/skips"),
        "empty": ("", "terminal count mismatch"),
        "truncated": (green.replace("INSTRUMENTATION_CODE: -1", ""), "missing/duplicate/unsuccessful INSTRUMENTATION_CODE"),
        "mismatch": (fixture(count=2), "missing/duplicate/red/mismatched OK footer"),
        "skip": (fixture(-3), "nonzero failures/errors/skips"),
        "assumption": (fixture(-4), "nonzero failures/errors/skips"),
        "error": (fixture(-1), "nonzero failures/errors/skips"),
        "duplicate": (protocol * 2 + "OK (2 tests)\nINSTRUMENTATION_CODE: -1\n", "duplicate terminal method"),
        "wrong-method": (fixture(name="two"), "method identity mismatch"),
        "missing-start": (terminal + footer, "terminal without matching active start"),
        "terminal-before-start": (terminal + start + "INSTRUMENTATION_STATUS_CODE: 1\n" + footer, "terminal without matching active start"),
        "overlapping-starts": (start + "INSTRUMENTATION_STATUS_CODE: 1\n" + protocol + footer, "test started before previous terminal"),
        "footer-before-tests": (footer + protocol, "test status after footer"),
        "reversed-footers": (protocol + "INSTRUMENTATION_CODE: -1\nOK (1 tests)\n", "INSTRUMENTATION_CODE before JUnit footer"),
        "crash": (green + "INSTRUMENTATION_RESULT: shortMsg=Process crashed\n", "instrumentation crash/abort"),
        "failure-banner": (green + "FAILURES!!!\n", "missing/duplicate/red/mismatched OK footer"),
        "failure-tally": (green + "Tests run: 1, Failures: 1\n", "missing/duplicate/red/mismatched OK footer"),
        "footer-code": (green.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"), "missing/duplicate/unsuccessful INSTRUMENTATION_CODE"),
        "duplicate-footer-code": (green + "INSTRUMENTATION_CODE: -1\n", "missing/duplicate/unsuccessful INSTRUMENTATION_CODE"),
        "malformed-footer-code": (green + "INSTRUMENTATION_CODE: malformed\n", "missing/duplicate/unsuccessful INSTRUMENTATION_CODE"),
        "duplicate-ok": (green + "OK (1 tests)\n", "missing/duplicate/red/mismatched OK footer"),
        "malformed-ok": (green + "OK (malformed tests)\n", "missing/duplicate/red/mismatched OK footer"),
        "bad-status": (green.replace("STATUS_CODE: 0", "STATUS_CODE: malformed"), "malformed status code"),
        "unknown-status": (fixture(5), "unknown status code: 5"),
        "duplicate-field": (green.replace("test=one", "test=two\nINSTRUMENTATION_STATUS: test=one", 1), "duplicate status field: test"),
        "missing-identity": (green.replace("INSTRUMENTATION_STATUS: class=app.duck.notes.ExampleTest\n", ""), "status missing class/test"),
        "leftover-bundle": (protocol + "INSTRUMENTATION_STATUS: test=one\n" + footer, "truncated status bundle"),
        "missing-terminal": (start + "INSTRUMENTATION_STATUS_CODE: 1\n" + footer, "test missing terminal"),
        "malformed-field": (green.replace("test=one", "test", 1), "malformed status field"),
    }
    for name, (raw, reason) in controls.items():
        parsed = parse_instrumentation(raw, 2 if name == "duplicate" else 1, {identity})
        checked(name, not parsed["accepted"] and reason in parsed["issues"])
    checked("nonpositive-expected", "expected count must be positive" in parse_instrumentation(green, 0)["issues"])
    checked("filtered-suite", "method identity mismatch" in parse_instrumentation(green, 2, {identity, "app.duck.notes.OtherTest#two"})["issues"])
    startup = "app.duck.notes.startup.WelcomeStartupTest#realApplicationSeedsOrdinaryNotesAndRetainsItsDecision"
    ordinary, arguments = ordinary_selection({identity, startup})
    checked("ordinary-filter", ordinary == {identity} and arguments == ["-e", "notClass", "app.duck.notes.startup.WelcomeStartupTest",
                                                                       "app.duck.notes.test/app.duck.notes.settings.SettingsTestRunner"])
    rejects("missing-startup", lambda: ordinary_selection({identity}), "exactly the frozen")
    rejects("extra-startup", lambda: ordinary_selection({identity, startup, "app.duck.notes.startup.WelcomeStartupTest#extra"}), "exactly the frozen")
    rejects("candidate-startup-id", lambda: ordinary_selection({identity, startup}, True), "no welcome startup tests")
    checked("candidate-filter", ordinary_selection({identity}, True) == ({identity}, ["app.duck.notes.test/app.duck.notes.settings.SettingsTestRunner"]))
    settings_runner = "app.duck.notes.test/app.duck.notes.settings.SettingsTestRunner"
    startup_runner = "app.duck.notes.test/app.duck.notes.startup.WelcomeStartupTestRunner"
    existing_runner = "app.duck.notes.test/app.duck.notes.startup.WelcomeExistingInstallTestRunner"
    expected_plan = [
        dict(action="clear"), dict(action="instrument", label="ordinary", runner=settings_runner),
        dict(action="clear"), dict(action="instrument", label="fresh", runner=startup_runner),
        dict(action="instrument", label="after_delete", runner=startup_runner),
        dict(action="clear"), dict(action="instrument", label="prepare_existing", runner=existing_runner),
        dict(action="instrument", label="existing", runner=startup_runner),
        dict(action="clear"), dict(action="instrument", label="prepare_existing_populated", runner=existing_runner),
        dict(action="instrument", label="existing_populated", runner=startup_runner),
        dict(action="clear"), dict(action="instrument", label="link_findings", runner=startup_runner),
    ]
    checked("literal-scenario-plan", device_plan() == expected_plan)
    checked("literal-candidate-plan", device_plan(True) == expected_plan[:2])
    literal_pairs = {"after_delete": "fresh", "existing": "prepare_existing", "existing_populated": "prepare_existing_populated"}
    checked("literal-retention-pairs", RETAINED == literal_pairs)
    for action in ("clear", "install"):
        for retained in literal_pairs:
            plan = [dict(step) for step in expected_plan]
            index = next(index for index, step in enumerate(plan) if step.get("label") == retained)
            plan.insert(index, dict(action=action))
            rejects(action + "/" + retained, lambda: validate_retention(plan), "Retention pair")
    for retained, preparation in literal_pairs.items():
        rejects("missing-preparation/" + retained,
                lambda: validate_retention([dict(action="instrument", label=retained)]), "Retention pair")
        rejects("wrong-preparation/" + retained,
                lambda: validate_retention([dict(action="instrument", label="wrong"), dict(action="instrument", label=retained)]), "Retention pair")
    task_executed("> Task :app:testDebugUnitTest\n", ":app:testDebugUnitTest")
    checked("fresh-task", True)
    for state in (" UP-TO-DATE", " FROM-CACHE", " NO-SOURCE", " SKIPPED"):
        rejects("task" + state, lambda: task_executed("> Task :app:testDebugUnitTest" + state + "\n", ":app:testDebugUnitTest"), "not freshly executed")
    rejects("missing-task", lambda: task_executed("", ":app:testDebugUnitTest"), "not freshly executed")
    rejects("duplicate-task", lambda: task_executed("> Task :app:testDebugUnitTest\n" * 2, ":app:testDebugUnitTest"), "not freshly executed")
    with tempfile.TemporaryDirectory(prefix="settings-gate-selfcheck-") as directory:
        output = Path(directory)
        normalized(output, "green", parse_instrumentation(green, 1, {identity}))
        checked("normalized-green-xml", ET.parse(output / "green.xml").getroot().attrib ==
                dict(name="green", tests="1", failures="0", errors="0", skipped="0"))
        error_raw = fixture(-1).replace("INSTRUMENTATION_STATUS_CODE: -1", "INSTRUMENTATION_STATUS: stack=first line\nsecond line\nINSTRUMENTATION_STATUS_CODE: -1")
        parsed = parse_instrumentation(error_raw, 1, {identity})
        checked("multiline-stack", parsed["error_records"][0]["stack"] == "first line\nsecond line")
        normalized(output, "error", parsed)
        checked("normalized-error-xml", ET.parse(output / "error.xml").getroot().find("testcase/error").text == "first line\nsecond line")
        source = output / "source"
        source.mkdir()
        test_source = source / "ExampleTest.kt"
        test_source.write_text("package app.duck.notes\nclass ExampleTest {\n    @Test fun one() {}\n    @org.junit.Test\n    @Other\n    fun two() {}\n    // @Test fun comment() {}\n}\n", encoding="utf-8")
        checked("anchored-discovery", discover_tests(source, output) == {identity, "app.duck.notes.ExampleTest#two"})
        for name, body, reason in (
                ("empty-discovery", "", "No source tests"),
                ("duplicate-discovery", "@Test fun one() {}\n@Test fun one() {}", "duplicate source test"),
                ("orphan-annotation", "@Test\n@Test fun one() {}", "no distinct test method"),
                ("ambiguous-owner", "@Test fun one() {}\n}\nclass Other {", "Ambiguous test owner")):
            test_source.write_text("package app.duck.notes\nclass ExampleTest {\n" + body + "\n}\n", encoding="utf-8")
            rejects(name, lambda: discover_tests(source, output), reason)
        test_source.write_text("package app.duck.notes\nclass ExampleTest {}\n", encoding="utf-8")
        candidate_welcome_absent(source)
        checked("candidate-welcome-absent", True)
        test_source.write_text('package app.duck.notes\nclass ExampleTest { val title = "Welcome to Duck" }\n', encoding="utf-8")
        candidate_welcome_absent(source)
        checked("candidate-head-welcome-title-allowed", True)
        for name, relative, content in (
                ("candidate-welcome-filename", "WelcomeHidden.kt", "class Hidden {}"),
                ("candidate-startup-directory", "startup/Hidden.kt", "class Hidden {}"),
                ("candidate-welcome-class", "Hidden.kt", "class WelcomeHidden {}"),
                ("candidate-welcome-import", "Hidden.kt", "import app.duck.notes.startup.WelcomeStartup\nclass Hidden {}"),
                ("candidate-welcome-reference", "Hidden.kt", "class Hidden { val startup: app.duck.notes.startup.WelcomeStartup? = null }"),
                ("candidate-welcome-adjacent-title", "Hidden.kt", 'class Hidden { val title = "Welcome to Duck"; val startup: WelcomeStartup? = null }'),
                ("candidate-welcome-caller", "Hidden.kt", "class Main { val welcomeStartup = 1 }")):
            path = source / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            rejects(name, lambda: candidate_welcome_absent(source), "contains welcome source")
            path.unlink()
        xml_source = output / "xml-source"
        xml_source.mkdir()
        xml_path = xml_source / "TEST-Example.xml"
        shutil.copy2(output / "green.xml", xml_path)
        xml_output = output / "xml-green"
        xml_output.mkdir()
        tally = fresh_xml(xml_source, {}, 1, xml_output)
        checked("fresh-jvm-xml", tally["totals"] == dict(tests=1, failures=0, errors=0, skipped=0) and tally["ids"] == [identity])
        def xml_rejection(name, before, started, reason):
            destination = output / name
            destination.mkdir()
            rejects(name, lambda: fresh_xml(xml_source, before, started, destination), reason)
        xml_rejection("unchanged-jvm-xml", {str(xml_path): xml_path.stat().st_mtime_ns}, 1, "Stale JVM XML")
        xml_rejection("old-jvm-xml", {}, xml_path.stat().st_mtime_ns + 1, "Stale JVM XML")
        shutil.copy2(xml_path, xml_source / "TEST-Duplicate.xml")
        xml_rejection("duplicate-jvm-tests", {}, 1, "duplicate JVM tests")
        (xml_source / "TEST-Duplicate.xml").unlink()
        original_xml = xml_path.read_text(encoding="utf-8")
        xml_path.write_text(original_xml.replace('tests="1"', 'tests="2"'), encoding="utf-8")
        rejects("jvm-testcase-tally", lambda: xml_summary(xml_path), "testcase tally mismatch")
        for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
            xml_path.write_text(original_xml.replace(key + '="0"', key + '="1"'), encoding="utf-8")
            rejects("jvm-status-tally/" + key, lambda: xml_summary(xml_path), "status tally mismatch")
            suite = ET.fromstring(original_xml)
            suite.set(key, "1")
            ET.SubElement(suite.find("testcase"), tag)
            ET.ElementTree(suite).write(xml_path, encoding="utf-8")
            xml_rejection("red-jvm-" + key, {}, 1, "JVM failures/errors/skips")
        xml_path.write_text(original_xml, encoding="utf-8")
        xml_path.unlink()
        xml_rejection("empty-jvm-tests", {}, 1, "Empty or duplicate JVM tests")
        build_log = output / "build.log"
        build_log.write_text("> Task :app:testDebugUnitTest\n> Task :app:packageDebug\n> Task :app:packageDebugAndroidTest\n", encoding="utf-8")
        apks = []
        installed = {}
        for package, content in (("app.duck.notes", b"app"), ("app.duck.notes.test", b"test")):
            path = output / (package + ".apk")
            path.write_bytes(content)
            metadata = artifact(path)
            apks.append(dict(source=metadata, archive=metadata))
            installed[package] = dict(last_update_time="2026-01-01", apks={"/data/app/" + package + "/base.apk": metadata["sha256"]})
        source_hashes = {"app/src/main/Example.kt": "0" * 64}
        manifest = dict(root=str(ROOT), finished_utc="2026-01-01T00:00:00Z", sources_before=source_hashes,
                        sources_after=source_hashes, debug=dict(native=dict(exit_code=0, started_ns=1, log=str(build_log)), unit=tally, apks=apks))
        validate_flake_build(manifest, source_hashes, installed)
        checked("valid-flake-build-provenance", True)
        controls_build = (
            ("unfinished-flake-build", lambda m: m.update(finished_utc=None), "finished gate"),
            ("wrong-flake-root", lambda m: m.update(root=str(ROOT.parent / "Other")), "finished gate"),
            ("changed-flake-sources", lambda m: m.update(sources_after={}), "source hashes"),
            ("failed-flake-build", lambda m: m["debug"]["native"].update(exit_code=1), "unsuccessful/interrupted"),
            ("interrupted-flake-build", lambda m: m["debug"]["native"].update(interrupted="timeout"), "unsuccessful/interrupted"),
            ("empty-flake-unit", lambda m: m["debug"]["unit"]["totals"].update(tests=0), "empty/red/incomplete"),
            ("red-flake-unit", lambda m: m["debug"]["unit"]["totals"].update(failures=1), "empty/red/incomplete"),
            ("missing-flake-unit", lambda m: m["debug"]["unit"].update(suites=[]), "no JVM XML suites"),
            ("changed-flake-xml", lambda m: m["debug"]["unit"]["suites"][0]["archive"].update(sha256="0" * 64), "JVM evidence changed/stale"),
            ("stale-flake-xml", lambda m: m["debug"]["unit"]["suites"][0]["source"].update(mtime_ns=0), "JVM evidence changed/stale"),
            ("wrong-flake-suite", lambda m: m["debug"]["unit"]["suites"][0].update(ids=["wrong#method"]), "suite manifest mismatch"),
            ("wrong-flake-aggregate", lambda m: m["debug"]["unit"].update(ids=["wrong#method"]), "aggregate manifest mismatch"),
            ("missing-flake-apk", lambda m: m["debug"].update(apks=[]), "app and test APKs"),
            ("changed-flake-apk", lambda m: m["debug"]["apks"][0]["archive"].update(sha256="0" * 64), "APK evidence changed/stale"),
            ("stale-flake-apk", lambda m: m["debug"]["apks"][0]["source"].update(mtime_ns=0), "APK evidence changed/stale"),
        )
        for name, mutate, reason in controls_build:
            bad = json.loads(json.dumps(manifest))
            mutate(bad)
            rejects(name, lambda: validate_flake_build(bad, source_hashes, installed), reason)
        rejects("current-flake-sources-mismatch", lambda: validate_flake_build(manifest, {}, installed), "source hashes")
        wrong_installed = json.loads(json.dumps(installed))
        wrong_installed["app.duck.notes"]["apks"] = {"/data/app/app/base.apk": "0" * 64}
        rejects("installed-flake-apk-mismatch", lambda: validate_flake_build(manifest, source_hashes, wrong_installed), "Installed package differs")
        build_log.write_text("> Task :app:testDebugUnitTest UP-TO-DATE\n", encoding="utf-8")
        rejects("cached-flake-build", lambda: validate_flake_build(manifest, source_hashes, installed), "not freshly executed")
    print(json.dumps(dict(status="PASS", checks=len(checks), parser_controls=len(controls), named_checks=checks,
                          build_device_source_plants=False)), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="mode", required=True)
    commands.add_parser("selfcheck")
    check = commands.add_parser("check-result")
    check.add_argument("log", type=Path)
    check.add_argument("--expected", type=int, required=True)
    check.add_argument("--out", type=Path)
    for mode in ("full", "settings-candidate", "flake"):
        command = commands.add_parser(mode)
        command.add_argument("--out", type=Path, required=True)
        command.add_argument("--serial", required=True)
        if mode == "flake":
            command.add_argument("--method", required=True, help="Exact class#method; already installed build")
            command.add_argument("--build-evidence", type=Path, required=True,
                                 help="Finished gate results.json with fresh green Debug build; device suite may have failed")
    args = parser.parse_args()
    if args.mode == "selfcheck":
        selfcheck()
        return 0
    if args.mode == "check-result":
        result = parse_instrumentation(args.log.read_text(encoding="utf-8-sig", errors="replace"), args.expected)
        if args.out:
            args.out.mkdir(parents=True, exist_ok=False)
            shutil.copy2(args.log, args.out / "raw.log")
            normalized(args.out, "instrument", result)
        print(json.dumps({key: result[key] for key in ("accepted", "tests", "passed", "failed", "errors", "skipped", "issues")}))
        return 0 if result["accepted"] else 1
    output = args.out.resolve()
    require(output != ROOT and ROOT not in output.parents, "Evidence output must be outside the repository")
    output.mkdir(parents=True, exist_ok=False)
    report = dict(mode=args.mode, root=str(ROOT), serial=args.serial, accepted=False, status="running",
                  started_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), sources_before=sources())
    save(output / "results.json", report)
    try:
        execute(args, output, report)
    except BaseException as error:
        report.update(accepted=False, error=repr(error))
        if args.mode == "flake":
            runs = report.get("runs", [])
            attempted = report.get("attempted_runs", len(runs))
            passed = sum(run["accepted"] for run in runs)
            report["flake_totals"] = dict(requested=20, attempted=attempted, passed=passed,
                                           failed_or_incomplete=attempted - passed, not_started=20 - attempted,
                                           uncompleted=20 - len(runs), unresolved=True)
    finally:
        try:
            report["sources_after"] = sources()
            if report["sources_after"] != report["sources_before"]:
                report.update(accepted=False, source_error="Source hashes changed during gate")
        except Exception as error:
            report.update(accepted=False, source_error=repr(error))
        report["status"] = "PASS" if report["accepted"] else "FAIL/INCOMPLETE; unresolved"
        report["finished_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        save(output / "results.json", report)
    print(report["status"] + "; " + str(output / "results.json"), flush=True)
    return 0 if report["accepted"] else 1


if __name__ == "__main__":
    sys.exit(main())
