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
- **Network:** the following hosts must be reachable. On this machine they are blocked (an Avast SSL
  filter is present) — whitelist them in Avast Web Shield **and** the firewall, or build on a network
  that allows them:
  - `dl.google.com` (Android SDK + Google Maven)
  - `repo1.maven.org` / `repo.maven.apache.org` (Maven Central)
  - `services.gradle.org` (Gradle distribution)
  - `github.com` / `objects.githubusercontent.com`

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
Compose BOM 2024.09.03 · compileSdk 35 · targetSdk 35 · minSdk 26 · JVM target 21.
