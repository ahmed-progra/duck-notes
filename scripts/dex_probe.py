#!/usr/bin/env python3
"""Assert what is and is not inside the release APK's dex.

Run it:

    python scripts/dex_probe.py

This exists because the same check has been hand-written three times and got it wrong twice.
`strings` is not on this machine (Git Bash ships no binutils), so
`unzip -p app.apk classes.dex | strings | grep -c DuckGallery` prints an error to stderr and **0** to
stdout — indistinguishable from "the class is absent". A check that reports success while measuring
nothing is worse than no check. Prose describing the right form did not stop it happening again, so
the right form is now a file that can be run and that fails loudly.

Three properties the ad-hoc versions kept losing:

1. **Scan `classes*.dex`, not `classes.dex`.** minSdk 26 has native multidex and the release APK has
   two. Probing only the first silently misses half the app.
2. **Positive controls.** Every run asserts that things which MUST be present are present. Without
   them a zero from a broken pipeline looks exactly like the zero you were hoping for.
3. **Exit non-zero.** Printing counts and leaving a human to read them is how the last two slipped.
"""

from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path

APK = Path("app/build/outputs/apk/release/app-release-unsigned.apk")

# Positive controls. If any of these is missing the probe itself is broken, not the APK.
MUST_BE_PRESENT = [
    "DuckShellViewModel",
    "DuckSidebar",
    "DuckNoteCard",
    "DuckTagChip",
    "MarkdownScanner",
]

# Debug-only code. src/debug must never reach a release build.
MUST_BE_ABSENT = [
    "DuckGallery",
    "GalleryPage",
    "ShellFixture",
    "TokenSheet",
]

# CLAUDE.md global contract: no bear references in code, strings or assets.
#
# Matched case-insensitively against the raw dex, then filtered: `bearingAccuracy` and friends come
# from the android.location framework stubs and are not ours. Anything surviving the filter is a
# string or identifier we shipped — that is how "syntax, like Bear" was found in SAMPLE_NOTE.
FORBIDDEN_WORD = re.compile(rb"[Bb]ear", re.IGNORECASE)
FRAMEWORK_BEAR = re.compile(rb"[Bb]earing", re.IGNORECASE)


def load_dex(apk: Path) -> tuple[bytes, list[str]]:
    with zipfile.ZipFile(apk) as z:
        names = sorted(n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex"))
        if not names:
            sys.exit(f"FAIL  no classes*.dex inside {apk} — is this really an APK?")
        return b"".join(z.read(n) for n in names), names


def main() -> int:
    if not APK.exists():
        sys.exit(f"FAIL  {APK} not found. Run: gradle -p . assembleRelease")

    blob, names = load_dex(APK)
    print(f"{APK}  ({len(names)} dex: {', '.join(names)}, {len(blob):,} bytes)\n")

    failures: list[str] = []

    print("positive controls (must be present)")
    for probe in MUST_BE_PRESENT + sys.argv[1:]:
        n = blob.count(probe.encode())
        print(f"  {'ok ' if n else 'MISSING'}  {probe:<24} {n}")
        if not n:
            failures.append(f"{probe} absent — probe is broken, or the class was renamed")

    print("\ndebug-only code (must be absent)")
    for probe in MUST_BE_ABSENT:
        n = blob.count(probe.encode())
        print(f"  {'ok ' if not n else 'LEAKED '}  {probe:<24} {n}")
        if n:
            failures.append(f"{probe} present in release — src/debug leaked into the release build")

    print("\nbear references (CLAUDE.md global contract)")
    ours = [
        m.group(0)
        for m in re.finditer(rb".{24}[Bb]ear.{24}", blob)
        if not FRAMEWORK_BEAR.search(m.group(0))
    ]
    if ours:
        for hit in sorted(set(ours)):
            print(f"  FOUND    {hit.decode('utf-8', 'replace')!r}")
        failures.append(f"{len(set(ours))} bear reference(s) shipped — see above")
    else:
        print("  ok       none outside android.location's bearingAccuracy")

    print()
    if failures:
        for f in failures:
            print(f"FAIL  {f}")
        return 1
    print("PASS  release dex is clean")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
