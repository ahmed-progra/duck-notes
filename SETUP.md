# Duck Notes — Toolchain Setup (Phase 1)

This project was scaffolded on a machine with **no Android SDK, no Gradle, and no network access to
the build hosts**. Everything except the Gradle wrapper binaries has been authored. Follow this once to
get to a green Phase 1 build (`./gradlew assembleDebug` passing + the app launching showing "Duck").

The fastest path is **Android Studio** (it bundles a JDK and the SDK manager). A pure command-line path
is also given.

---

## 0. What is intentionally missing

| Missing | Why | How it gets created |
|---|---|---|
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` | You asked to own wrapper generation | Android Studio creates them on first open, or run `gradle wrapper` (step 3) |
| `local.properties` | Machine-specific, git-ignored | Android Studio writes it on open, or create it by hand (step 2) |
| Real duck launcher icon | Design work is **Phase 14**, with the icon variants and activity-alias set. Phase 5b ships a placeholder mascot for empty states only, and does not own the launcher. | Placeholder accent icon ships now |

`gradle/wrapper/gradle-wrapper.properties` **is** present and pins **Gradle 8.13** — the version AGP 8.13.2
requires. Keep it.

---

## 1. Prerequisites

- **JDK 21** must be resolvable by Gradle. This project sets `kotlin { jvmToolchain(21) }` and
  `sourceCompatibility`/`targetCompatibility = VERSION_21`, matching the JDK 21 on this machine.
  - Android Studio: its embedded JDK (21+) satisfies this — nothing to do.
  - Command line / fully offline: JDK 21 must already be installed and auto-detectable by Gradle.
- **Network:** the following hosts must be reachable:
  - `dl.google.com` (Android SDK + Google Maven)
  - `repo1.maven.org` / `repo.maven.apache.org` (Maven Central)
  - `services.gradle.org` (Gradle distribution)
  - `github.com` / `objects.githubusercontent.com`

- **TLS interception (this machine).** The Avast Web/Mail Shield intercepts TLS and presents its own
  root CA. Windows trusts that CA; the Temurin JDK ships its own `cacerts` and does not — so without
  a fix Gradle cannot resolve a single dependency and fails with
  `PKIX path building failed … unable to find valid certification path`.

  **Fix (already applied):** `C:\Users\zconsumers\.gradle\gradle.properties` contains
  ```
  org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8 -Djavax.net.ssl.trustStoreType=Windows-ROOT
  ```
  This points the build JVM at the Windows certificate store, so it trusts exactly what Windows
  already trusts — no new trust, no admin rights, and it survives JDK updates.

  It lives in the **user-level** Gradle home, deliberately **not** in the repo: `Windows-ROOT` is a
  Windows-only trust store type and would break the build on any other platform. `GRADLE_USER_HOME`
  wins over the project file for `org.gradle.jvmargs`, so the repo's value is overridden, not edited.

  Do **not** "fix" this by importing the CA into the JDK's `cacerts` or by disabling the shield.

---

## 2. Android SDK

### Option A — Android Studio (recommended)
1. Install Android Studio.
2. `File > Open` this folder. Studio will:
   - prompt to install missing SDK packages (accept),
   - generate `local.properties` with `sdk.dir`,
   - generate the Gradle wrapper,
   - run a Gradle sync (downloads AGP/Kotlin/Compose/Hilt).

### Option B — Command-line SDK
1. Download **commandline-tools** from <https://developer.android.com/studio#command-tools> and unzip to
   `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\`.
2. Set env vars:
   ```
   setx ANDROID_HOME "%LOCALAPPDATA%\Android\Sdk"
   ```
   (open a new shell afterward)
3. Install packages and accept licenses:
   ```
   sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   sdkmanager --licenses
   ```
4. Create `local.properties` in the project root:
   ```
   sdk.dir=C\:\\Users\\zconsumers\\AppData\\Local\\Android\\Sdk
   ```
   (use double backslashes; adjust to your actual path)

---

## 3. Gradle wrapper (command-line path only)

Android Studio does this for you. Otherwise, install Gradle 8.13 once
(<https://gradle.org/releases/>), then from the project root:
```
gradle wrapper --gradle-version 8.13
```
This mints `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`.

---

## 4. Build + run (verify Phase 1 green)

```
# Build the debug APK
./gradlew assembleDebug          # Windows cmd: gradlew.bat assembleDebug

# Run the unit test
./gradlew testDebugUnitTest

# Install + launch on a running emulator or a USB device with debugging on
./gradlew installDebug
adb shell am start -n app.duck.notes/app.duck.notes.presentation.MainActivity
```

### Instrumented tests — do not use `connectedAndroidTest`

`./gradlew connectedAndroidTest` **reports failure even when every test passes** on this machine.
AGP runs instrumented tests through Unified Test Platform, which streams results back to Gradle over
gRPC/TLS; the Avast shield intercepts that channel too, so UTP logs
`recordTestResultEvent failed … UNAVAILABLE: io exception` and Gradle reports
`Failed to receive the UTP test results`. The result proto says `test_status: PASSED` while the build
says `BUILD FAILED`. UTP runs in its own JVM, so the trust-store setting above does not reach it, and
AGP 8 has no non-UTP path to fall back to.

Use either of these instead — both report correctly:

```
# CLI: build the APKs once, then drive the runner directly
gradle assembleDebug assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r app.duck.notes.test/app.duck.notes.settings.SettingsTestRunner

# ...or one class:
adb shell am instrument -w -r -e class app.duck.notes.markdown.OutputTransformationCaretSpikeTest \
  app.duck.notes.test/app.duck.notes.settings.SettingsTestRunner
```

Android Studio's own test runner also reports correctly — use it for interactive runs.

### Screenshots — use `exec-out`, never `shell` with `>`

Capturing a screenshot the obvious way produces a **corrupt file, silently**:

```
# WRONG — especially from PowerShell. Produces unopenable garbage, exit code 0.
adb shell screencap -p > shot.png
```

PowerShell's `>` re-encodes the stream as UTF-16LE, which destroys binary data. The file is written,
the command succeeds, and nothing warns you. A corrupt capture starts with `ff fe` (a UTF-16LE BOM);
a valid PNG starts with `89 50 4e 47 0d 0a 1a 0a`.

```
# RIGHT — from bash (Git Bash), binary-safe:
adb exec-out screencap -p > shot.png

# Verify before trusting it:
file shot.png                      # must say "PNG image data"
head -c 8 shot.png | od -An -tx1   # must be 89 50 4e 47 0d 0a 1a 0a
```

This bit us in Phase 4: three capture files in the repo root were UTF-16-mangled and unopenable, so
any visual verification based on them was not actually looking at anything. **Check the magic bytes
before reporting on a screenshot.** The same trap applies to `adb pull` of binaries, `bugreport`, and
anything else streaming bytes through a redirect — it will produce silent garbage again during
Phase 8's editor work and Phase 16's screenshot testing.

### Dex membership: run `scripts/dex_probe.py`. Do not hand-write it.

```
gradle -p . assembleRelease
python scripts/dex_probe.py
```

Exits non-zero and prints `FAIL` on any violation. Nothing to remember, nothing to retype.

**Why this is a file and not a snippet.** `strings` is not on this machine (Git Bash ships no
binutils), so `unzip -p app.apk classes.dex | strings | grep -c DuckGallery` prints
`strings: command not found` to stderr and **`0`** to stdout — indistinguishable from "the class is
absent". This section previously described the correct Python form in prose. **It did not work:** the
check was hand-written a third time in Phase 6, `strings` was reached for again, and the fix was a
*fourth* ad-hoc form (`grep -a -o`). A doc that has to be remembered before it helps is not a control.
So the prose is gone and the executable stays.

The three properties the ad-hoc versions kept losing, all now enforced by the script:

- **`classes*.dex`, not `classes.dex`** — minSdk 26 has native multidex; the release APK has two.
- **Positive controls** — a zero from a broken pipeline looks exactly like the zero you wanted. The
  script asserts five classes are *present* and fails if any is not.
- **A non-zero exit** — printing counts for a human to eyeball is how the last two slipped through.

It also enforces CLAUDE.md's no-bear rule against the shipped bytes, filtering `android.location`'s
`bearingAccuracy`. That check is what found `"syntax, like Bear"` inside `SAMPLE_NOTE` in Phase 6 — a
string that had been shipping in the release APK since Phase 4.

Add probes for a phase's new classes as arguments: `python scripts/dex_probe.py DuckShell MyNewThing`.
Permanent ones belong in `MUST_BE_PRESENT` / `MUST_BE_ABSENT` at the top of the file.

### Emulator instability — class-level instrumented runs dying

**Symptom, precisely:** a *class-level* instrumented run dies partway through or reports
`Expected N tests, received 0`, while the same test methods pass when run individually. Seen during
Phase 4 with `ConcealMappingTest`.

**Cause:** the emulator, not the tests.

**Fix:**
1. Cold-boot the AVD.
2. Exclude the Android SDK folder from Avast (or your AV) Web/Mail/File shields.

After that fix, `ConcealMappingTest` passed 10/10 class-level and the full instrumented suite 19/19.

**What this does and does not rule out.** Reach for the two steps above first — they resolved it.
But nothing about the tests themselves was disproved by experiment; fixing the emulator made the
test-side hypotheses *moot*, not *false*. Worth keeping in mind if a Compose test misbehaves again:

- Per-test `Activity` churn under `createComposeRule` and IME wedging from `performTextInput` were
  plausible and remain untested.
- A recomposition loop — an `OutputTransformation` invalidating every frame so `waitForIdle` never
  settles — was never excluded by evidence. It is unlikely, since single-method runs settle in ~3s,
  but it is the one with product consequences rather than harness ones. If a future Compose test
  **hangs** rather than dying abruptly, check that before assuming the emulator.

Unit tests are unaffected: `gradle testDebugUnitTest` works normally.

To create an emulator via command line:
```
sdkmanager --install "system-images;android-35;google_apis;x86_64"
avdmanager create avd -n duck35 -k "system-images;android-35;google_apis;x86_64"
emulator -avd duck35
```

---

## 5. Phase 1 success checklist

- [ ] `./gradlew assembleDebug` → `BUILD SUCCESSFUL`
- [ ] `./gradlew testDebugUnitTest` → passes
- [ ] App launches and shows **"Duck"** centered, in the accent red
- [ ] No crash on start = the Hilt graph (`@HiltAndroidApp` + `@AndroidEntryPoint`) resolved
- [ ] Launcher icon renders (placeholder accent disc)

When all boxes are checked, Phase 1 is green. Phase 2 (Room + FTS5) assumes the `data/` and `domain/`
package boundaries and the Hilt app scaffold created here already exist.

---

## Pinned versions (from `gradle/libs.versions.toml`)

AGP 8.13.2 · Gradle 8.13 · Kotlin 2.0.21 · KSP 2.0.21-1.0.28 · Hilt 2.52 ·
Compose BOM 2025.10.01 (foundation/ui 1.9.4, material3 1.4.0) · commonmark 0.29.0 ·
compileSdk 35 · targetSdk 35 · minSdk 26 · JVM target 21.

The Compose BOM was raised in Phase 4: `TextFieldBuffer.addStyle` — the styled `OutputTransformation`
the markdown editor is built on — first ships in Compose Foundation 1.9.0. Newer BOMs (2026.x,
foundation 1.11+) require AGP 9.2 and are out of reach until AGP is upgraded.

---

## Build environment traps on this machine

The following have cost time here — the first three in Phase 6, the fourth and fifth in the 8b
audit-fix pass, the next two in the 8c-b spike, the eighth in the 8c-b re-verify pass, the ninth in
the 8c-c-fix pass, the tenth in the 8d-a pass, the eleventh in 8d-b, the twelfth in 9b, the
thirteenth across 9a, 9b and 9d. None is a project defect; all are easy to hit again.

**1. Neither `gradle` nor `adb` is on `PATH`.** Both are installed; neither is findable. Use the
absolute paths — these two lines are the ones to copy:

```sh
GRADLE=/c/Users/zconsumers/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle
ADB=/c/Users/zconsumers/AppData/Local/Android/Sdk/platform-tools/adb.exe
```

(Windows-shell form: `…\gradle-8.13\bin\gradle.bat` and `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.)

`adb` is missing from `PATH` in the same way and for the same reason as `gradle`, and it costs the
same rediscovery every time — a bare `adb devices` returns *"command not found"*, which reads exactly
like "no device attached" if you are not looking closely. **That is the trap: an absent binary and an
absent emulator produce the same conclusion.** Phase 8a reported the instrumented suite as blocked on
"no device" when the emulator was in fact running.

Pass `-p "<repo root>"` to Gradle rather than relying on the working directory — a tool that has
`cd`'d elsewhere will otherwise fail with *"not part of the build defined by settings file"*.

**Emulator already running?** `emulator -avd Pixel_7` fails with
*"Running multiple emulators with the same AVD is an experimental feature"* when an instance is
already up. That message means **success, not failure** — check `$ADB devices` before launching, and
before concluding anything from the error.

**2. Do not point `JAVA_HOME` at Android Studio's JBR.** `JAVA_HOME` is already set correctly to
Temurin 21. The JBR lacks the `SunMSCAPI` security provider, so the `javax.net.ssl.trustStoreType=Windows-ROOT`
setting in the user `gradle.properties` — the fix for TLS interception on this network — fails with
`SSLInitializationException: Windows-ROOT not found` and every dependency download dies. Symptom looks
like a network outage; cause is the JDK.

**3. The Gradle module cache holds stale versions alongside the resolved one.** `material3-android`
has both `1.3.0` and `1.4.0` in `modules-2/files-2.1`, and a `find | head -1` picks the wrong one.
When reading library bytecode to justify a decision — which `ContrastTest` and `DuckShell` both do for
Material's alpha values — confirm the version first:

```
gradle -p <root> -q app:dependencyInsight --configuration debugRuntimeClasspath --dependency material3
```

**PowerShell note:** `adb exec-out screencap -p > file.png` corrupts the PNG (a BOM and text encoding
are prepended). Use `adb shell screencap -p /sdcard/x.png` followed by `adb pull`.

**4. An editing tool can write a NUL byte into a `.kt` file.** It happened to
`TagRepositoryImpl.kt` during the 8b audit-fix pass: a `\0` landed inside a string literal, and
**Kotlin compiled it and the tests ran** — a NUL is legal in a Kotlin string. What broke was the
tooling around it. `grep` classifies any file containing a NUL as binary and prints
*"Binary file … matches"* instead of the matching lines, so the line could not be found by search.

That is the trap: **the compiler is happy and the search tool is silently useless**, which reads like
"my edit didn't apply" and sends you looking in the wrong place.

```sh
file app/src/.../Suspect.kt          # "data" instead of "C source, Unicode text, UTF-8 text"
grep -c $'\0' path                   # or: python -c "print(open(p,'rb').read().count(b'\0'))"
grep -an pattern path                # -a forces text mode, if you only need to *see* the line
```

Repair with a byte-level replace rather than a text edit — a tool that mangled the byte once can do it
again, and `git checkout` of a tracked file is banned by the global contract:

```sh
python -c "
p='path/to/File.kt'; d=open(p,'rb').read()
assert d.count(b'\x00')==1
open(p,'wb').write(d.replace(b'\x00',b''))"
file path/to/File.kt                 # confirm it reads as UTF-8 text again
```

**5. Plant-then-revert can leave lint's Kotlin index stale, and it fails loudly for the wrong reason.**
After a few plant → run → revert cycles in one daemon session, `lintAnalyzeDebugUnitTest` and
`lintAnalyzeDebugAndroidTest` died with a **`FileNotFoundException`** deep in
`KotlinLocalVirtualFile.contentsToByteArray` — hundreds of lines of `LintDriver` stack and not one
lint finding. It is an index holding a path whose content moved underneath it, not a problem with the
code.

**Re-running `lint` on its own passes.** Do that before investigating; a stack trace this large reads
like a real defect and is not one. If it persists across a fresh invocation, then it is worth reading.

**6. A conceal test on a single-line body measures the *revealed* rendering, and looks like a Compose
finding.** This is a project-wide trap, not a note about one test. `SpanPlanner` reveals a token's
markers whenever the selection **touches** the token, and a task or heading token spans its **whole
line** — so if the body is one line, the caret is inside the token no matter where it sits, and the
conceal never runs.

**Symptom: the concealed field and a raw one lay out at identical width.** In the 8c-b spike that read
as *"the `OutputTransformation` never reaches layout"* — a Compose finding, written up and nearly
committed. It was the fixture. Two iterations.

**Fix: give the body a tail and park the caret off the token.** `GoldenSpanTest` has done this since
Phase 4 via a private `CARET_AWAY = Int.MAX_VALUE / 2`, which is why the convention was invisible to
everything written after it.

```kotlin
const val TASK_NOTE = "- [ ] milk\n\nplain tail line"   // the tail is load-bearing
val CARET_AWAY = TASK_NOTE.length                        // park here between measurements
```

Re-park **between** taps in a sweep, not just once: the first tap reveals the markers, and every
later tap is then measuring a different rendering than the first.

**The second form is worse, because it is silent.** `ConcealInvariantTest` swept `0..body.length` and
looked exhaustive. For a single-line block sample it was not: `> - [ ] x` concealed **one** range at
every swept caret, and the ordering and overlap checks run under `zipWithNext`, which does nothing
with one element. Two *block* markers had therefore never been checked against each other — found in
8c-b by a plant that overlapped them and stayed green. A sweep bounded by the body length cannot
reach the parked state; add `CARET_AWAY` to the sweep, not just to the spot checks.

Corollary for the assertion itself — a single number cannot tell "the conceal did not run" from "the
conceal ran and this offset maps back to the deleted range's start". Both give `selection == 0` at the
left edge. Lay two fields out side by side and assert the results **differ**, or the test passes for
the wrong reason. (`TaskTapSpikeTest.theSameTapLandsDifferentlyWithAndWithoutTheConceal`.)

**7. A `Modifier.toggleable` placed as a *sibling* of a scroll container appears to swallow drags.**
`toggleable` builds on `clickable`, which cancels its gesture on drag rather than consuming it — and
the cancelled drag then travels to a scrollable **ancestor**. A sibling has no such ancestor, so the
drag reaches nothing and the content does not scroll. That reads as *"toggleable eats scrolls, use a
raw pointer interceptor instead"*, which is the wrong conclusion and a much larger change.

**Nest the toggleable inside the scroll container.** It then consumes taps and passes drags through
correctly. Production wants it there regardless: an overlay pinned to text has to scroll with it.

Anything testing "consumes the tap but not the drag" must therefore assert **both halves** — that the
drag did not toggle *and* that `scrollState.value` moved. Asserting only the first is green on a
miswired fixture.

**8. A hard-killed emulator corrupts its snapshot; it then "boots" and dies.** An emulator launched as
a child of an agent/background task is killed by session teardown, not a clean `adb emu kill`. The
kill lands mid-snapshot-write and `<avd>.avd`'s `default_boot` snapshot comes out internally
inconsistent. Every boot after that **reports `sys.boot_completed=1` after a single `getprop` poll** —
far faster than a real boot — **then the device goes `offline` within seconds**, sometimes
immediately, sometimes mid-suite. A killed instrumentation run with no `INSTRUMENTATION_CODE` /
`OK (N tests)` line looks like a pass if the log is only tailed rather than grepped for a summary
line — check for the summary explicitly.

This produced a deterministic false red in `TaskTapSpikeTest.aRenderedOffsetHasABoundingBoxATapCan​BeTestedAgainst`
in the 8c-b re-verify pass: `Tests run: 56, Failures: 1`, always the same assertion, always
`got 10` (end-of-text — the tap never focused the field). Cross-class and intra-class contamination
and a coordinate-space error were both ruled out by controlled re-runs before the snapshot was
suspected; see the test's own KDoc for the elimination.

Recovery, confirmed stable across 5 consecutive runs after 3 snapshot-boot failures in a row:

```sh
emulator -avd <name> -no-snapshot-load -no-snapshot-save
```

Cold boot took 110 polls here, versus 1 for the corrupt snapshot — that gap is itself the tell. Launch
future emulators **detached from any agent-managed process** (e.g. PowerShell `Start-Process`, not a
backgrounded shell job) so a session/task teardown can't kill them mid-run in the first place.

**9. Redirect an instrumented run to a file. Never filter the stream inline.** Trap 8 says to grep
explicitly for the summary line rather than tailing the log. Do that **against a file**, not against
the live stream:

```powershell
$out = "instrument_full.log"
& $ADB shell am instrument -w -r app.duck.notes.test/app.duck.notes.settings.SettingsTestRunner > $out
Select-String -Path $out -Pattern "^OK \(|^Tests run|^FAILURES|INSTRUMENTATION_CODE"
Select-String -Path $out -Pattern "^\d+\) "      # the failing test NAMES
```

**A stream can be read once; a file can be read as many times as the question changes.** Piping
`am instrument` straight into `Select-String` decides, before you have seen anything, which lines are
worth keeping — and when the run comes back red the interesting lines are exactly the ones the filter
was not written to expect.

**What it cost.** In the 8c-c-fix pass a full run reported `Tests run: 62, Failures: 3` while the
filter caught the summary and **not the numbered failure list**. Three cold-boot re-runs afterwards
were all green, so the failures could not be reproduced and their names were never recovered. The
result had to be written into the commit as unexplained flakiness rather than diagnosed — a
conclusion the log almost certainly contained and the pipe threw away.

The same rule covers `logcat` and `dumpsys`. Redirect first, ask questions second.

**10. Gate every device install on the build's own exit code. Never trust `adb install`'s "Success".**
`adb install` prints `Success` when the APK file exists and installs cleanly — it says **nothing** about
whether that file was just rebuilt from the source in front of you or is a stale artifact left by a
build that failed a second earlier. If the `assembleDebug` / `assembleDebugAndroidTest` step ahead of
it failed and the install runs anyway, the runner exercises the *previous* APK and the suite reports
against bytes your edit never reached.

Capture the exit code **before** any filter, and make the install unreachable on a non-zero build:

```powershell
$build = & $G -p $R assembleDebugAndroidTest; $code = $LASTEXITCODE
"gradle exit=$code"; ($build | Select-String -Pattern "^e:") -join "`n"
if ($code -ne 0) { "BUILD FAILED"; return }        # the gate — install cannot run on a red build
& $ADB install -r -t "$R\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" | Select-Object -Last 1
```

Two mistakes compound here, and both were live at once. `& $G … | Select-String … | Select-Object -First N`
runs the build *inside* a pipeline, and `Select-Object -First N` tears that pipeline down the moment it
has N lines — which both hides the trailing `BUILD SUCCESSFUL` / `BUILD FAILED` line **and** corrupts
`$LASTEXITCODE`, because you then read the exit of the terminated pipe, not of gradle. Assign the build
output to a variable first (`$build = & $G …`) so gradle runs to completion and `$LASTEXITCODE` is its
real code; filter `$build` afterwards, when the exit is already banked.

**This is strictly worse than trap 9, because it reports green.** Trap 9 *loses* the failure names — you
know something is wrong, you just can't see what. A stale-bytes install is affirmative: the guard runs,
passes, and prints `OK`, and nothing on screen looks like a lie you'd catch by looking harder. It is the
silent form of the global contract's *"a plant is code, and can be wrong in the direction that fakes a
red"* — except here it faked a **green**.

**What it cost.** In the 8d-a pass, Plant A restored a pre-fix defect but also changed an import, so it
**did not compile**. The install ran unconditionally on the previous debug APK, `Select-Object -First N`
had already zeroed the exit code, and `RtlLayoutTest` reported `OK (2 tests)` — a pass certified against
a binary that did not contain the plant. A plant exists to prove the guard *can* go red; this one proved
only that the harness would call stale bytes green. Every install from that point gates on
`$LASTEXITCODE`. The same ungated `assembleDebug | Select-Object -First 3` + unconditional-install shape
ran throughout the 8c-c-fix plant cycles too and did **not** bite there — but only because a containment
plant is expected to go *red*, and its red carried the guard's freshly-computed geometry (at the 48dp
gutter, `the chevron spans -24.0.dp..24.0.dp, the text starts at 48.0.dp`), a signature stale bytes
cannot forge. The hazard was equally present; it simply had nothing green to hide behind until a plant
whose correct result was a pass.

**11. Run `dex_probe.py` against a `--rerun-tasks` release build, and know that `MUST_BE_PRESENT`
matches source *filenames*, not only class descriptors.** Two ways the probe can certify a dex that
does not say what you think, both found in the 8d-b pass by a `MUST_BE_PRESENT` plant that renamed a
class and refused to go red:

- **`MUST_BE_PRESENT` is a substring match, and a class's `.kt` filename is embedded in the dex debug
  info (`SourceFile` / Kotlin `SMAP`).** Renaming `class AttachmentRepositoryImpl` → `AttachmentRepoImpl`
  left the probe reporting `AttachmentRepositoryImpl` **present, count 6** — every hit was the string
  `AttachmentRepositoryImpl.kt`, not the type. The class rename had taken (`AttachmentRepoImpl` appeared
  14×); the *filename* had not. So a positive control here means **"a class of this name, or a file of
  this name, reached the dex"** — for every entry whose class name equals its filename, the file alone
  satisfies it. A plant that renames a class **must rename the file too**, or it fakes a green. (This is
  a property of the probe, not a bug to fix: for a *presence* control, "the file compiled in" is a
  reasonable signal. It is written down so the next plant is designed knowing it.)
- **`org.gradle.caching=true`, so a plain `assembleRelease` — even `clean assembleRelease` — can return
  a byte-identical dex from the build cache in ~6 s, and the probe reads whatever the cache hands back.**
  **Use `--rerun-tasks`. `--no-build-cache` alone is NOT enough:**

  ```
  gradle -p . assembleRelease --rerun-tasks --no-build-cache
  python scripts/dex_probe.py
  ```

  Without it, the probe is trap 10 one layer down: it certifies bytes your edit never reached.

  **The two flags are not equivalent, and this section used to say they were.** `--no-build-cache`
  disables the *build cache* — it does not disable Gradle's **incremental up-to-date checks**. If the
  inputs hash the same as the last release build, the task is **skipped entirely**, nothing is
  compiled, and the probe reads whatever dex is already sitting in `outputs/`. That is the identical
  failure the flag was added to prevent, arrived at by a different route. `--rerun-tasks` is the one
  that forces the work.

  **The tell is the clock, and it is the part a reader can actually spot: a release assemble that
  finishes in about a second did not compile anything.** A real one here takes ~60–70 s.

  **What it cost.** In 9c's final gate, `assembleRelease --no-build-cache` returned
  `BUILD SUCCESSFUL in 1s` — *after* three plants had been applied and reverted. The reverts had
  restored the inputs to the hash of an earlier release build, so Gradle skipped the task and the
  probe would have certified a dex predating every one of them. It happened to be the right bytes;
  nothing on screen said so, and nothing would have said otherwise. Re-run with `--rerun-tasks` it
  took 1m 10s and probed a dex that had actually been built. Same shape as trap 10 — **a green that
  certifies bytes the change never reached** — one layer further down.

**12. `Get-Content -Raw | Set-Content -Encoding utf8` silently corrupts em-dashes into mojibake.**
`Get-Content -Raw` decodes as the system ANSI codepage unless told otherwise, so a UTF-8 `—`
(`E2 80 94`) is read as three CP1252 characters and written back as `â€"`. **This repo's KDoc is
dense with em-dashes**, so very nearly every source file is a candidate.

**What makes it dangerous is that the corruption compiles.** Mojibake inside a comment or a KDoc is
valid Kotlin. Nothing goes red, no test notices, lint says nothing, and the release dex is unaffected
— it surfaces only when a human next reads the file, possibly months later, in a paragraph that is
now unquotable. Every other trap here fails visibly; this one fails into the source tree and waits.

**Patch source through Python instead, never through that pipeline:**

```sh
python - <<'PY'
p = 'app/src/main/java/app/duck/notes/Example.kt'
s = open(p, encoding='utf-8').read()
s = s.replace('old', 'new')
open(p, 'w', encoding='utf-8', newline='').write(s)
PY
```

`newline=''` matters as much as the encoding: without it Python rewrites every line ending to `\n`,
so a one-word edit lands as a whole-file CRLF→LF diff and buries itself.

**The concrete instance.** It hit `NoteLinkDao.kt` during 9b's plant sequence. A `Set-Content` used
to strip `AND n.is_trashed = 0` for a plant also rewrote the KDoc line
`## Trash changes no rows — it is hidden here, not deleted` as `## Trash changes no rows â€" it is
hidden here, not deleted`. The plant built and ran correctly, and the file stayed corrupted after the
revert. Caught only by grepping the file for `â€` on the way past; repaired with the Python form
above, after which every remaining plant in that sequence used it.

**Note the interaction with CLAUDE.md's git rules.** `git checkout` of a tracked file is forbidden
here, so mojibake in a *tracked* file cannot be undone by discarding the change — it has to be
repaired in place. Grep for `â€` and `Â` before committing anything a PowerShell pipeline touched.

**13. Confirm a plant actually landed before you run anything. A plant that fails to apply produces
a green, and a green is what a working guard looks like.**

This has now happened three times, in three different ways:

- **9a's Plant R** patched only one of the two `starts += start` appends in `splitLinesWithOffsets`.
  The fixture's link was on the *last* line, whose start comes from the append that was missed. Whole
  suite green.
- **9b's plant on the same function** had the same shape for the same reason.
- **9d's Plant 11** was meant to rename `WikiLinkOverlay.kt` to break a `MUST_BE_PRESENT` control.
  `git mv` **refuses an untracked file**, the `&&` short-circuited the rename that followed it, and
  the probe then reported `WikiLinkOverlay` **present** — certifying a build with no plant in it.

**Why it is dangerous rather than merely annoying.** CLAUDE.md already warns that a plant is code and
can be wrong in the direction that fakes a red. This is the *other* direction, and it is worse,
because a fake red is at least investigated. A plant that never applied is indistinguishable from a
guard that is genuinely load-bearing — both print green — so it is filed as *"guard confirmed"* and
the guard is never tested again.

**The tell:** a plant that produces no red is one of exactly two things — a finding worth writing
down, or a plant that never applied. **Those look identical until you check**, so check before
concluding either. 9d had one of each in the same session: the outer-vs-inner span plant was a real
finding (the two are provably equal under concealment), and Plant 11 was a non-application.

**Confirm the edit, not the command.** Any one of these is enough, and all are cheap:

```sh
git diff --stat -- <file>                 # tracked files: the plant should show up here
grep -n "<the planted text>" <file>       # untracked files, or a rename: look for it directly
python - <<'PY'                           # a patch that asserts it matched
s = open(p, encoding='utf-8').read()
assert OLD in s, "plant did not match — nothing was changed"
open(p, 'w', encoding='utf-8', newline='').write(s.replace(OLD, NEW))
PY
```

**Do not chain a plant behind `&&`.** `git mv old new && python patch.py` silently skips the patch
when the move fails, which is precisely how Plant 11 got through. Run the steps separately and read
the exit code, or use plain `mv` — **an untracked file is exactly the situation a class added by the
current phase is in**, which is when this fires.


## Settings completion audit (2026-10-09)

Settings candidates are 14a (persistence), 14b (UI and readiness), 14c (scoped
autosave lifecycle harness), then 14d (gate and tracking), before welcome 9f.
Commit file lists and mixed hunks require explicit approval. Welcome-owned hunks
remain frozen.

Evidence inventory refreshed on 2026-10-10. Final acceptance is determined by
`settings-completion-20261009/closure-final/closure-tally.json`, outside the repository.
Its `accepted` field must be true; saved intermediate gates do not replace it.
Commit approval remains separate.

The strict plant tally (`all-plant-review-20261010T145945Z/verification.json`)
accepts 26 tracked-repository proofs covering all 26 Settings methods: 30 intended
assertion failures, zero RED errors/skips, 30 restored GREEN tests with zero
failures/errors/skips, and 26 exact restorations. Every proof contains four
whole-tree maps of 284 files bound to C09, edit-landed evidence, its own XML
assertion reason, before/planted/reverted SHA-256 and the dirty status after
restoration. Ten complete pairs recovered from a failed publisher batch retain
individual credit; that batch itself remains failed and its unstarted record
remains excluded.

The Settings-only 14a cumulative candidate
(`settings-candidate-builds/14a/results.json`) contains 538 JVM tests in each of
Debug and Release, with zero failures, errors or skips. Its source discovery
records 109 Android methods; that result contains no instrumentation execution.
Its accepted result is a JVM/build check for the recorded candidate, not a
109-test device pass.

Saved C09 preflight evidence (`candidate-09/results.json`) contains 546 Debug JVM
tests with zero failures, errors or skips, and 12 Settings device tests with
12 passed, zero failed, errors or skips, `OK (12 tests)` and
`INSTRUMENTATION_CODE: -1`. This is a preflight result for its recorded source
hashes, not final completion evidence.

The Settings-only 14b candidate evidence
(`settings-candidate-builds/14b/results.json`) contains 539 JVM tests in each of
Debug and Release with zero failures, errors or skips, plus all 118 candidate
Android methods: 118 passed, zero failed, errors or skips, `OK (118 tests)` and
`INSTRUMENTATION_CODE: -1`. Its ordinary instrumentation run has no welcome
filter or dedicated welcome scenarios. It proves that saved cumulative candidate,
not a plant run or the final combined working tree.

The Settings-only 14c candidate (`settings-candidate-builds/14c/results.json`)
contains 539 JVM tests in each of Debug and Release and all 121 candidate device
methods, with zero failures/errors/skips, `OK (121 tests)` and
`INSTRUMENTATION_CODE: -1`. It includes the scoped lifecycle harness and the
existing real autosave timer controls, with no welcome filter.

| Item | Status and remaining closure evidence | Repository location |
|---|---|---|
| SETTINGS-STARTUP-FALLBACK | Own FALLBACK_LOADING_RESET plant is credited with assertion RED, exact restoration and fresh GREEN; final combined gate remains required. C09's stuck-store test reached the actual System window, bars and captured pixel after the literal 1000ms unresolved-read cap, then cleared the notice after the real read. Platform configuration can settle afterward. | MainActivity.kt; FirstFrameThemeTest.stuckRealStoreReleasesAtTheLiteralCapAndLateReadClearsTheNotice |
| SETTINGS-ACTION-TEXT | Historical virtualized Palette target was absent before color measurement. C09 exercises the actual launcher color assertions; INFO_CONTENTS_TEXT_ROLE now has its own assertion RED, exact restoration and fresh GREEN; final combined gate remains required. | SettingsActivityTest.actionTextUsesLiteralAccessibleColorInBothRealThemes; DuckInfoPanel.kt |
| SETTINGS-LIFECYCLE-EDITOR | Historical ComposeNotIdle occurred in setup. C09 exercises actual Room ON_STOP persistence at autosave virtual time 0 and the active autosave control. ON_STOP_EDITOR_NO_FLUSH now has its own Room assertion RED, exact restoration and fresh GREEN; final combined gate remains required. | SettingsLifecycleTest.editorStopWritesActualRoomWhileRealDebounceRemainsAtZero; DuckScreen.kt |
| SETTINGS-LIFECYCLE-SETTINGS | Historical ComposeNotIdle occurred in setup. Same requirements with Settings open and the editor absent; ON_STOP_SETTINGS_NO_FLUSH now has its own Room assertion RED, exact restoration and fresh GREEN; final combined gate remains required. | SettingsLifecycleTest.settingsOpenStopWritesActualRoomWhileRealDebounceRemainsAtZero; DuckScreen.kt |
| SETTINGS-DELAYED-THEME | Open. C07 observed Ready at OnDraw with resource mask 16 where 32 was expected. That observation may have followed an earlier Loading cap release; the saved trace does not establish the exact race. Require 20 sequential isolated runs of this single method on one unchanged installed build, recording source and APK hashes and pass/fail/incomplete counts. Any failure or incomplete run leaves this open. | FirstFrameThemeTest.delayedOppositePreferenceIsReadyAtTheFirstPermittedAppDraw; MainActivity.kt |
| SETTINGS-RETAINED-ORIGIN | C09 compares the actual retained ViewModel origin before and after real recreation and selects that recreated Activity's OnDraw and PixelCopy. STARTUP_ORIGIN_DEVICE is credited for its direct origin assertion RED, exact restoration and fresh GREEN; final combined gate remains required. | SettingsViewModel.firstLaunchElapsedMs/retainFirstLaunch/shouldHoldStartup; FirstFrameThemeTest.recreationDuringThePausedReadDoesNotRestartTheOriginalCap |
| SETTINGS-COLD-RENDER | Open limitation. Readiness evidence does not bound actual frame/render latency. Historical cold draws exceeded 1000ms; no performance fix or every-frame capture is claimed. | FirstFrameThemeTest timing logs; MainActivity.kt |
| F8 | Open, separate scope: expanded info-pane geometry at 839/840/900/1200 widths, LTR/RTL, font bounds and taps. Settings text colors do not close it. | DuckInfoPanel.kt; DuckShell.kt; DUCK_NOTES_BUILD_PROMPT.md |
| F9 | Open, separate scope: attachment, wiki-link and note-save failure feedback and partial-state policies. Settings DataStore retry does not close it. | AttachmentRepositoryImpl.kt; OpenWikiLink.kt; DuckShellViewModel.kt; DUCK_NOTES_BUILD_PROMPT.md |
| SAVE-RECOVERY | Pending explicit scope: note-id failure state, retry and departure policy, cancellation, real Room rollback and surviving collectors. No implementation in this batch. | DuckShellViewModel.kt; SaveNote.kt; DUCK_NOTES_BUILD_PROMPT.md |
| F1/F2 provenance | Saved HEAD d781678 does not contain repair 5908be4f87f1c67eb6bb595a660280e5b46302e1; that object was absent from this checkout. External recovered-stage results do not establish current repair. | MarkdownTextCache.kt; DUCK_NOTES_BUILD_PROMPT.md |

The early-read fixture changed. The 300ms pause is now measured from actual
Activity entry rather than completion of onCreate. C09 independently requires
both worker release and observed Ready before the literal 1000ms cap, and release
no earlier than 300ms. C07 only bounded release; its saved delayed run logged
release=619ms, draw=2480ms, mask=16 and two creations. C09 logged release=302ms,
Ready observed=470ms, draw=530ms and mask=32. These are individual recorded runs,
not a 20-run flake result. The saved `flake-20-final/results.json` contains a
historical 20/20 pass bound to candidate-04, whose FirstFrameThemeTest hash is
`cff0bbc31719853a5a980827957e6b9ecba9fa4029761c7123377ab88777cbd3`.
C09 uses `ccee186fdf6c4999f2caab41d4e4c7390d1828a1ace932c31c55ca23f1ab529c`.
That earlier protocol does not satisfy the final unchanged-build requirement
for this changed fixture. A read that becomes Ready after cap release is outside
the early-read fixture and may explain C07; that remains an inference.

The old assertions requiring a delayed draw before 1000ms and a recreated child's
draw within its own 1000ms deadline were removed. They mixed physical rendering
latency with the retained unresolved-read cap. The replacement checks the actual
retained ViewModel origin and its real `shouldHoldStartup(origin + 1000, false)`
decision, while keeping actual recreated-Activity observation and literal
window/bar/pixel assertions. This changes the coverage claim; it does not establish
a physical-frame latency ceiling.

C07's completed Settings result was 12 tests, 8 passed and 4 failed, with
`FAILURES!!!`, `Tests run: 12, Failures: 4` and `INSTRUMENTATION_CODE: -1`.
Besides delayed-mask and child-render-deadline assertions, it recorded literal
dark-surface failures in the theme-row and real retry tests. Those are historical
C07 failures, distinct from the original four Settings findings above. The
combined repository runs included welcome changes and cannot establish that any
Settings failure pre-dated welcome work.

Final acceptance requires all four cumulative candidate result files, the combined
Debug/Release and complete instrumentation gate, the final fixed 20-run protocol,
and the fresh frozen-scope audit to satisfy the closure aggregator. The accepted
plant proofs and candidate 14a/14b/14c results above remain bound to their saved
source hashes; no later edit inherits their credit. The final gate is
`scripts/verify_settings_completion.py`. Its ordinary suite and dedicated welcome
scenarios must cover the grep-discovered methods and exact expected raw count.
No data is cleared between the preparation and verification of retained-install
scenarios. Any failure or incomplete delayed-theme run leaves that item unresolved.

Both the repository gate and repaired historical verifier rejected the known
126-test result containing four failures, each with native exit 1; controls are
saved in `gate-script-review-20261010T141442Z/verification.json`. Runtime acceptance
comes from result-file tallies, `OK (N tests)` and `INSTRUMENTATION_CODE`, rather
than a successful build message.

F3-F7 remain deferred in their own batches. Stable welcome identities and restore
remain out of scope. Existing welcome scenarios may run as verification without
editing their welcome-owned sources or clearing data in retained-install steps.
