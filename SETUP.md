# Build and verify Duck Notes

Quick reference:

```powershell
python -B scripts/security_checks.py wrapper
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat :app:testReleaseUnitTest :app:lint :app:assembleRelease
```

Use JDK 21, Android SDK platform 35, and the checked-in Gradle 8.13 wrapper. A release APK is unsigned; this setup does not create signing keys or publish anything. Device qualification is separate from JVM/build success.

## Environment

Install a JDK 21 distribution and [Android Studio or the Android command-line tools](https://developer.android.com/studio). Point `JAVA_HOME` at that JDK and `ANDROID_HOME` at the SDK directory. Install the required SDK packages and accept their licenses:

```text
sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0"
sdkmanager --licenses
```

Android Studio can create a local `local.properties` containing `sdk.dir`. Command-line builds can use `ANDROID_HOME` instead. Neither a local SDK path nor machine-specific Gradle trust-store/JVM settings belong in source control. Do not disable TLS certificate validation to resolve a download problem; configure the local machine's approved trust chain.

The wrapper launchers and JAR are checked in. The wrapper distribution URL and SHA-256 pin are in `gradle/wrapper/gradle-wrapper.properties`. `scripts/security_checks.py wrapper` checks their official Gradle 8.13 bytes (normalizing launcher line endings). Dependencies are declared in `gradle/libs.versions.toml`. Strict dependency verification uses `gradle/verification-metadata.xml`; missing or mismatched metadata must fail the build. Do not use `--dependency-verification=off` as a build workaround.

First builds need network access to Gradle, Google Maven, Maven Central, the plugin portal, and Android SDK repositories. After all required artifacts are cached, `--offline` can be used for local verification. A working cache is not evidence that a clean source checkout can download and build successfully.

On Windows, use `gradlew.bat`; on macOS/Linux, use `bash ./gradlew`. The commands below use PowerShell. Keep every command's native exit status; a later successful command must not hide an earlier failure.

## Build, JVM tests, lint, and release inspection

```powershell
$securityReports = Join-Path $env:TEMP 'duck-security'
.\gradlew.bat -I scripts/export_dependencies.gradle :app:testDebugUnitTest :app:testReleaseUnitTest :app:lint :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest exportDependencyInventory "-PdependencyInventoryOutput=$securityReports/resolved-dependencies.json" --rerun-tasks --no-build-cache --no-configuration-cache --console=plain --dependency-verification=strict
if ($LASTEXITCODE -ne 0) { throw 'Fresh verification build failed.' }

python -B -m unittest discover -s scripts -p 'test_*.py'
if ($LASTEXITCODE -ne 0) { throw 'Probe controls failed.' }
python -B scripts/dex_probe.py
if ($LASTEXITCODE -ne 0) { throw 'Release DEX inspection failed.' }
```

Read test totals, failures, errors, and skips from `app/build/test-results/testDebugUnitTest/TEST-*.xml` and `testReleaseUnitTest/TEST-*.xml`. `BUILD SUCCESSFUL` and `UP-TO-DATE` alone do not establish a fresh test pass. Inspect lint's XML/HTML reports under `app/build/reports/` and the generated release manifest/components and native libraries as part of release review.

Build outputs include:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Unsigned Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`
- Instrumentation APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

## Direct Android instrumentation

Use a disposable emulator or test device containing synthetic data. Select the device explicitly if more than one is attached. Configure `$adb` below from the SDK; it does not need to be on `PATH`.

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
& $adb devices
& $adb install -r -t 'app/build/outputs/apk/debug/app-debug.apk'
if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
& $adb install -r -t 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
if ($LASTEXITCODE -ne 0) { throw 'Instrumentation installation failed.' }

$ordinaryLog = Join-Path $env:TEMP 'duck-ordinary-instrumentation.log'
& $adb shell am instrument -w -r -e notClass 'app.duck.notes.startup.WelcomeStartupTest,app.duck.notes.startup.WelcomeStartupGateTest,app.duck.notes.startup.OfflineActivityTest,app.duck.notes.data.repository.BackupTransportTest' app.duck.notes.test/app.duck.notes.settings.SettingsTestRunner > $ordinaryLog
$instrumentExit = $LASTEXITCODE
Select-String -LiteralPath $ordinaryLog -Pattern '^OK \(', '^Tests run', '^FAILURES', 'INSTRUMENTATION_CODE'
if ($instrumentExit -ne 0) { throw 'Instrumentation command failed.' }
```

Inspect the complete saved output. Require the expected `OK (N tests)`, `INSTRUMENTATION_CODE: -1`, no failure/crash/abort, and complete native command termination. Android can return instrumentation code `-1` even when JUnit reports failures, so checking that code alone is insufficient. A killed run can leave an apparently successful tail. Never accept a partial log or an empty suite.

Use raw `adb shell am instrument -w -r`, rather than `connectedAndroidTest`, for the authoritative native result. The ordinary runner uses test dependency injection and deliberately excludes the startup and real-Activity classes. It does not establish production startup behavior by itself.

`WelcomeStartupGateTest` scenarios use `SettingsTestRunner`, with a fresh app-data directory and process for **each** method: `noStallReleasesBothGates`, `blockedStorageReleasesSplash`, and `blockedStorageReleasesCompose`. These scenarios delete app data. Use the project qualification script to retain the setup/run sequence and evidence:

```powershell
python -B scripts/verify_settings_completion.py --help
```

`OfflineActivityTest` uses `OfflineActivityTestRunner` with production Activity, ViewModel, repository, and Room. Run `-e offlineScenario create` after clearing only a disposable test installation, then force-stop the app and run `-e offlineScenario restart` without clearing its data. Keep the two complete native results as one persistence/restart scenario.

`WelcomeStartupTest` uses `WelcomeExistingInstallTestRunner` to prepare retained-install data, then the production-application `WelcomeStartupTestRunner` to verify it. Follow the script's scenario sequence without clearing between those steps. Record APK/source hashes and expected discovered methods alongside every complete result. Startup, Room reopening, process restart, backup restoration, controlled failures, layout widths, large text, English/Arabic, RTL, and accessibility checks remain required release qualification; no JVM suite substitutes for them.

## Secret and dependency checks

Provision **Gitleaks 8.30.1** from its [official release](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1). Verify the archive checksum before extraction or execution. For Windows x64, `gitleaks_8.30.1_windows_x64.zip` has SHA-256:

```text
d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e
```

Use an external report directory and the verified executable:

```powershell
python -B scripts/security_checks.py secrets --gitleaks '<verified-tool-directory>/gitleaks.exe' --reports '<external-report-directory>'
if ($LASTEXITCODE -ne 0) { throw 'Secret scan found a finding or tool error.' }

# Use the inventory from the complete build/test/lint invocation above.
python -B scripts/security_checks.py advisories --inventory "$securityReports/resolved-dependencies.json" --report "$securityReports/advisories.json"
if ($LASTEXITCODE -ne 0) { throw 'Advisory check found a finding or could not complete.' }
```

Replace angle-bracket placeholders with real local paths; never commit those paths or reports. Run only one Gradle invocation at a time. Apply the inventory init script to the same invocation as the complete build/test/lint tasks, with `exportDependencyInventory` last, so it also observes detached lint and AAPT dependencies. The exporter resolves project and buildscript configurations, including UTP, and records exact configuration provenance and failures. It excludes only raw Kotlin metadata declarations and named Native-only tools that no actual task resolved; every exclusion has a reason in the JSON. The advisory gate rejects incomplete task coverage or absent observed lint/AAPT tools. The OSV check retains full current matching records. Its only applicability disposition is the exact reviewed KAPT-cache advisory and Kotlin plugin version, and requires evaluated plugin, task, configuration, and component evidence that KAPT is absent and KSP is present. Review bundled SQLite, the Android platform, JDK, build tools, and other native code separately; Maven-only matching cannot establish their safety.

The source scan exports tracked and nonignored candidate files into a temporary directory, then removes it. Git history is scanned separately. Reports use 100% redaction, but filenames and metadata can still be private. Real credentials, local databases, signing keys, and private evidence must remain outside the commit regardless of scan results.

## CI scope

`.github/workflows/verify.yml` runs the checks above on a Windows runner with immutable action commit pins and read-only repository permissions. It uploads only JVM/lint reports, keeps no shared build cache, and does not sign or publish APKs. Its Android-test APK build is a compile check; the workflow does not launch an emulator or claim device qualification. Hosted CI execution can only be verified after a repository destination is approved and configured.

When dependencies or tools change, review official release/advisory information, update version/checksum pins and required notices deliberately, regenerate verification metadata in a trusted environment, and rerun the complete affected checks. A checksum generated from an existing cache is a reproducibility control, not independent proof of publisher authenticity.
