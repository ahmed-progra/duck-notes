# Security and privacy

Duck Notes is an offline Android notes app. The production app has no `INTERNET` permission, account login, analytics, or cloud-sync implementation. Local note text, derived search/link data, preferences, and images remain sensitive even when no server is involved.

## Storage and recovery limits

Duck Notes stores notes in Room/SQLite and images in its private app files directory. The app does not encrypt these files or implement biometric locking. A person with an unlocked device, privileged access, a compromised operating system, or access to the user's backups may be able to read them. Trash is a reversible state, not secure erasure; archived and trashed content remains in storage and backup.

Unsupported database downgrades must fail without destructive fallback. Keep the current app and its data if a downgrade is rejected; clearing app data or uninstalling can destroy local notes. Save failures keep the originating editor's recoverable text and expose Retry. Recovery depends on the app process still retaining unsaved text; v1 does not promise recovery of text that never reached persistent storage after a forced process kill or power loss.

New image imports accept PNG, JPEG, WebP, or GIF content after checking the actual image data. Each image is limited to 20 MiB of input; dimensions and decode sizes are bounded. Files are copied into a private temporary directory before publication. Publication uses a same-filesystem atomic move under the shared process lock and rejects an existing destination. The attachment row and note reference are committed in one database transaction. Image formats and their platform decoders remain a security boundary, so keep Android and dependencies updated.

Image removal records a durable cleanup intent before removing its row and note reference. Failed unlinks remain queued; main-process startup retries only recorded files whose attachment row is absent. These recovery records are included in backup. Persistent storage failures can postpone byte removal; deleting an image is not secure erasure.

## Android backup

Backup is enabled. Explicit rules include `duck.db` and its SQLite sidecars, `attachments/`, and the settings/welcome DataStore files. Import files under `attachments/.pending/` are excluded. Both legacy backup rules and Android 12+ cloud/device-transfer rules are present. Attachment references are portable app-owned keys; supported legacy paths are rebased into the restored app directory rather than opened at the old absolute location.

Android controls when backup runs, where the selected transport stores it, and how restoration is offered. Google cloud Auto Backup has a **25 MB quota per app**; exceeding it stops that cloud backup until data falls below the quota. A 20 MiB image limit does not ensure an entire notes collection fits. Device-to-device transfer and OEM transports can behave differently. User backup settings and Android account/device protection also affect recovery and confidentiality. See [Android Auto Backup](https://developer.android.com/identity/data/autobackup).

## Repository and dependency checks

- `scripts/security_checks.py wrapper` verifies the pinned official Gradle 8.13 wrapper and distribution checksums and requires the dependency verification file before executing Gradle. Presence activates verification; Gradle checks the configured dependency bytes.
- Gitleaks 8.30.1 scans the source candidate and complete available Git history with 100% redaction. Reports and temporary source exports stay outside the commit.
- Gradle dependency verification must remain in strict mode. Checksum metadata constrains accepted bytes; generating metadata alone does not independently establish publisher authenticity.
- The resolved dependency inventory includes Android runtime, compile/test, buildscript, UTP, and observed task-time lint/AAPT dependencies. It records excluded raw metadata declarations and unused Native-only configurations by exact name and reason. Missing build coverage, resolution failures, and failed advisory queries fail the check.
- Guava JRE dependencies used by Gradle, annotation processing, and Android test tools are pinned to 33.7.2-jre, including the detached lint graph, to address [GHSA-xxph-c9ww-hj94](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94). The current app runtime does not include the full Guava library; future dependency changes still require a fresh resolved-graph advisory scan.
- Lint's detached tool graph has strict transitive constraints for patched Bouncy Castle, Commons Compress, and HttpClient versions. Its observed resolved graph must still pass the advisory scan; ordinary project configuration rules do not constrain that detached graph.
- Current OSV matches remain visible. The only nonblocking applicability disposition is `GHSA-r937-wjx7-w2jp` for `org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21`: the [upstream fix](https://github.com/JetBrains/kotlin/commit/bf51df665b458fda7c3eaf436c4d88dc119d7ec6) changes KAPT cache deserialization, while this build uses KSP. The disposition requires nonempty evaluated plugin/task/configuration evidence, no KAPT use or processor components, and positive KSP evidence. Introducing KAPT, changing the component version, or matching another advisory fails the check. This is an applicability decision, not a claim that the Kotlin version is patched.
- DataStore 1.1.1 embeds relocated `protobuf-javalite` 3.22.3, which matches [CVE-2024-7254 / GHSA-735f-pc8j-v9w8](https://github.com/protocolbuffers/protobuf/security/advisories/GHSA-735f-pc8j-v9w8): malicious nested fields can cause a stack overflow during parsing. The affected 3.x line is fixed in 3.25.5. This embedded copy is absent as a standalone Maven coordinate, so the resolved-graph advisory scan and separate protobuf dependency constraints do not cover or replace it. Duck's production DataStore callers read fixed app-private settings and welcome files, which also participate in Android backup. Malformed or tampered private/restored files remain a conditional crash risk; remote exploitability has not been demonstrated. Qualify an official patched DataStore release before closing this risk.
- Maven matching does not cover every bundled native-library, Android-platform, JDK, or build-tool vulnerability. Bundled SQLite 3.46.0 has upstream version-range matches requiring attacker-controlled SQL/database metadata or concurrent WAL writers/checkpoints. Current production uses fixed bound SQL, a private database with no database import API, and Room's single writer. Those prerequisites were not found in this app; future raw SQL, database import, additional processes/builders, or checkpoints require a new assessment. See [SQLite advisories](https://sqlite.org/cves.html) and the [WAL-reset race](https://sqlite.org/wal.html#walreset). This conditional risk remains visible until a compatible patched official driver is qualified.
- CI has read-only repository permissions, immutable action commit pins, no persisted checkout credentials, and no signing or publishing step. Security finding reports are not uploaded as public workflow artifacts.

Secret scanning is not proof that no secret or private artifact exists. Do not commit real notes, database copies, credentials, signing keys, provider grants, private evidence, or machine-local configuration. Use synthetic data in test fixtures and logs.

## Reporting a vulnerability

Report vulnerabilities through [GitHub private vulnerability reporting](https://github.com/ahmed-progra/duck-notes/security/advisories/new). Do not post real note text, credentials, or a working data-exposure payload in a public issue.

Provide the affected commit/version, Android version, minimal steps using synthetic data, expected and actual behavior, and the security impact. No response-time or supported-release commitment is currently published.
