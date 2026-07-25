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
| Real duck launcher icon | Design work is Phase 5 | Placeholder accent icon ships now |

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
