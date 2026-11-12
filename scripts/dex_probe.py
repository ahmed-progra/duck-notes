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
import struct
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
    # 8c-c. SETUP.md asks for a phase's new classes to be added here permanently rather than passed
    # as arguments once; 8c-c shipped without doing it, so nothing proved folding reached release.
    "SectionFolding",
    "SectionFoldOverlay",
    "FoldSection",
    # 8d-b attachments. The strip, its use cases, DAO and data-layer impls — each a distinct class
    # name, so a rename or a stripped feature shows up as one MISSING line. isMinifyEnabled=false in
    # release keeps every name, so a present class is a present string.
    "AttachmentStrip",
    "AttachImage",
    "DeleteAttachment",
    "ObserveAttachments",
    "AttachmentDao",
    "AttachmentRepositoryImpl",
    "AttachmentStorageImpl",
    # 8d-c paste. The image-paste receiver + its extracted decision (isAttachableImage/shouldAttach).
    "ImagePaste",
    # 8d-d focus mode. The one new class the phase ships: the pure caret-to-paragraph resolver the
    # dimming rides on. A present string here means focus mode's core reached the release dex.
    "FocusParagraph",
    # 9a. The single home for the [[wiki link]] bracket rule, extracted so ResolveWikiLinks, the
    # editor scanner and DeriveTitle stop each carrying their own copy — the Phase 4 defect. A present
    # string here means the extraction reached the release dex, not just the source tree.
    "WikiLinkScanner",
    # 9b. The backlinks panel — the first thing to read the note_links rows 9b starts writing. Its
    # write half has no class of its own (the rows ride NoteDao's existing save transaction), so this
    # is the string that proves the phase reached the release dex rather than only the source tree.
    "DuckInfoPanel",
    # 9c. The [[title]] autocomplete strip. Its detection half lives inside WikiLinkScanner (already
    # above) because the source guard forbids the bracket literals anywhere higher, so this composable
    # is the string that proves the picker itself reached the release dex.
    "WikiLinkSuggestions",
    # 9d. Create-on-tap. The overlay is where a tap stops being a coordinate — placed at forward-mapped
    # rendered offsets so nothing has to be inverted. A present string here means the tap target itself
    # reached the release dex, not merely the mapping behind it.
    "WikiLinkOverlay",
    # 9e. Counts, timestamps and the table of contents. The only new *class* the phase ships: the
    # word/character counter. Everything else 9e adds is rows inside DuckInfoPanel (already above) and
    # one scroll effect inside MarkdownEditorScreen, neither of which mints a name to probe for.
    #
    # Note the trap-11 property when planting against this one: the entry substring-matches the
    # `NoteStats.kt` filename embedded in the dex debug info as well as the class descriptor, so a
    # plant that renames the object must rename the FILE too or it reports present and fakes a green.
    "NoteStats",
    # 9f. Descriptor rather than filename: the ordinary welcome content reached release.
    "Lapp/duck/notes/startup/WelcomeNotes;",
    # Independent prose controls for every body, not just the content holder's class.
    "Make room for your first idea.",
    "Give each idea a place.",
    "Connect a thought to another note.",
    "A quiet place for your notes.",
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
#
# FIND THE HITS, THEN SLICE. Do not go back to a windowed regex like `.{24}[Bb]ear.{24}`, which is
# what this was and which could not see 8 of the 12 occurrences in the release dex it was passed.
# Two independent blindnesses, both silent, both reporting PASS:
#
# - It never used FORBIDDEN_WORD. The constant declares re.IGNORECASE and the actual search was a
#   bare inline `re.finditer` without it, so `EXTRA_BEARING_ACCURACY` and `HAS_BEARING_ACCURACY_MASK`
#   were invisible. A named detector that nothing calls is worse than none: it reads as the check.
#
# - `.` does not match a newline without re.DOTALL, and dex strings are ULEB128 length-prefixed, so
#   every 10-character string carries a literal 0x0A immediately before it. The probe was therefore
#   blindest for SHORT strings — exactly the shape a rebranding leak takes. `"syntax, like Bear"` is
#   17 characters and was caught; that was luck of length, not design.
# - A fixed-width window cannot match within 24 bytes of either end of the blob.
# - `finditer` is non-overlapping, so two hits inside 48 bytes cost each other a match.
#   `getBearing`/`getBearingAccuracy` sit 11 bytes apart and did exactly that.
#
# Slicing the context by hand has none of those failure modes and is shorter.
#
# THE FRAMEWORK FILTER IS ANCHORED TO THE MATCH, NOT TO THE CONTEXT, and that is the whole ballgame.
# It used to ask "does `bearing` appear anywhere in this 52-byte window?", which is not the question.
# A dex string table is sorted, so ANY string starting `bear` sorts immediately next to the
# framework's own `bearingAccuracy` and was filtered as framework. Found by planting `"bear notes"`
# in src/main: it landed at `\nbear notes\x00\x0fbearingAccuracy` and the probe reported the APK
# clean. As written, that filter could never report a bear-prefixed string at all — the exact class
# of name a rebrand leaks. `.match(blob, p)` anchors at the hit, so only a hit that IS `bearing`
# is exempt.
FORBIDDEN_WORD = re.compile(rb"[Bb]ear", re.IGNORECASE)
FRAMEWORK_BEAR = re.compile(rb"[Bb]earing", re.IGNORECASE)

# Bytes of context printed either side of a hit. Display only — never used to decide.
CONTEXT = 24

# Whole seed strings are inspected separately, with no framework exemption.
WELCOME_PREFIXES = (
    b"# Get started\n",
    b"# Organize with tags\n",
    b"# Work faster\n",
    b"# Welcome to Duck\n",
)


def dex_strings(dex: bytes) -> list[bytes]:
    """Read complete MUTF-8 string values; ASCII checks need no Unicode decoding."""
    if len(dex) < 112 or not dex.startswith(b"dex\n"):
        raise ValueError("invalid dex header")
    count, table = struct.unpack_from("<II", dex, 56)
    if count == 0 or table < 112 or table + count * 4 > len(dex):
        raise ValueError("invalid or empty dex string table")
    values = []
    for index in range(count):
        (offset,) = struct.unpack_from("<I", dex, table + index * 4)
        # Skip the ULEB128 UTF-16 length. It is metadata, not part of the string.
        for _ in range(5):
            if offset >= len(dex):
                raise ValueError("truncated dex string length")
            byte = dex[offset]
            offset += 1
            if byte < 0x80:
                break
        else:
            raise ValueError("invalid dex string length")
        end = dex.find(b"\x00", offset)
        if end < 0:
            raise ValueError("unterminated dex string")
        values.append(dex[offset:end])
    return values


def welcome_failures(strings: list[bytes]) -> list[str]:
    failures = []
    for prefix in WELCOME_PREFIXES:
        bodies = [value for value in strings if value.startswith(prefix)]
        title = prefix.decode().strip("# \n")
        if len(bodies) != 1:
            failures.append(f"welcome body {title!r}: expected 1 complete dex string, found {len(bodies)}")
        for body in bodies:
            if FORBIDDEN_WORD.search(body):
                failures.append(f"welcome body {title!r} contains forbidden branding (no exemptions)")
            if b"\r" in body:
                failures.append(f"welcome body {title!r} contains CR")
    return failures


def load_dex(apk: Path) -> tuple[bytes, list[str], list[bytes]]:
    with zipfile.ZipFile(apk) as z:
        names = sorted(n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex"))
        if not names:
            sys.exit(f"FAIL  no classes*.dex inside {apk} — is this really an APK?")
        dexes = [z.read(n) for n in names]
        strings = [value for dex in dexes for value in dex_strings(dex)]
        return b"".join(dexes), names, strings


def main() -> int:
    if not APK.exists():
        sys.exit(f"FAIL  {APK} not found. Run: gradle -p . assembleRelease")

    try:
        blob, names, strings = load_dex(APK)
    except (ValueError, struct.error, zipfile.BadZipFile) as error:
        sys.exit(f"FAIL  cannot inspect dex strings: {error}")
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
    hits = [m.start() for m in FORBIDDEN_WORD.finditer(blob)]
    ours = [
        blob[max(0, p - CONTEXT):p + CONTEXT + 4]
        for p in hits
        if not FRAMEWORK_BEAR.match(blob, p)
    ]
    print(f"  scanned   {len(hits)} occurrence(s) of 'bear', {len(hits) - len(ours)} framework")
    if ours:
        for hit in sorted(set(ours)):
            print(f"  FOUND    {hit.decode('utf-8', 'replace')!r}")
        failures.append(f"{len(set(ours))} bear reference(s) shipped — see above")
    else:
        print("  ok       none outside android.location's bearingAccuracy")

    print("\nwelcome bodies (complete strings, no framework exemption)")
    seed_failures = welcome_failures(strings)
    failures.extend(seed_failures)
    if not seed_failures:
        print("  ok       four LF-only bodies, no forbidden branding")

    print()
    if failures:
        for f in failures:
            print(f"FAIL  {f}")
        return 1
    print("PASS  release dex is clean")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
