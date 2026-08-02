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
adb shell am instrument -w -r app.duck.notes.test/androidx.test.runner.AndroidJUnitRunner

# ...or one class:
adb shell am instrument -w -r -e class app.duck.notes.markdown.OutputTransformationCaretSpikeTest \
  app.duck.notes.test/androidx.test.runner.AndroidJUnitRunner
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

Three things cost time in Phase 6. None is a project defect; all are easy to hit again.

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
