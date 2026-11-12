# Duck Notes

Duck Notes is an offline Markdown notes app for Android 8.0 and later. Notes live in a local Room database; images stay in app-owned storage. No account or network connection is required.

This repository contains the initial `0.1.0` offline release. See [SECURITY.md](SECURITY.md) for known dependency risks and verification limits.

## Install

Download the signed APK from the [latest GitHub release](https://github.com/ahmed-progra/duck-notes/releases/latest). Open the APK on your Android device; Android may ask you to allow installation from your browser or file manager.

## Build

Install JDK 21 and Android SDK platform 35, then run from the repository root:

```powershell
python -B scripts/security_checks.py wrapper
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

On macOS/Linux, use `bash ./gradlew` in place of `.\gradlew.bat`. See [SETUP.md](SETUP.md) for SDK configuration, release builds, native tests, and dependency checks.

## Implemented behavior

- Create a persisted blank `Untitled` note from the list header, edit Markdown, and recover failed saves with a note-specific Retry action.
- Organize notes with tags, pinning, Archive, and Trash. Trash is read-only. Restore preserves a note's archived state; v1 has no permanent deletion or automatic purge. Undoing a tag merge may leave the destination tag pinned; it can be unpinned manually.
- Search the title and body of every nontrashed note, including Archive. Search uses literal prefix terms after a 200 ms pause. Clearing the query restores the sidebar view underneath it.
- Follow `[[wiki links]]` and view backlinks. Notes keep UUID identities and can share titles. Lookup tries the literal title before its derived title; duplicate targets prefer the most recently modified note, then the smallest ID. Renaming never rewrites another note's body.
- Import validated PNG, JPEG, WebP, or GIF image content, with a 20 MiB limit per image. App-owned attachment paths survive restoration to a different app-data directory.
- Use English or Arabic system localization, right-to-left layouts, compact through expanded panes, and light, dark, or system theme. A dedicated in-app language picker is deferred.

Cloud sync, widgets, encrypted notes, biometric locking, advanced search, import/export, and broader personalization are deferred.

## Privacy and backup

Notes and attachments are **not encrypted by Duck Notes**. Android's app sandbox and device protection apply; they do not provide an app lock. Android backup remains enabled for the database, attachments, and required preferences, with temporary imports excluded. Cloud backup has a **25 MB per-app quota**; a collection of images can exceed it. Do not treat backup as guaranteed recovery. See [SECURITY.md](SECURITY.md) and [Android's backup guidance](https://developer.android.com/identity/data/autobackup).

## Verification and contributions

The GitHub workflow builds Debug and unsigned Release APKs, runs both JVM suites and lint, inspects release DEX, scans source/history with pinned Gitleaks, and checks resolved Maven coordinates against OSV. It does not run an emulator or publish APKs. Device, restart, migration, backup, layout, and controlled-failure qualification must also pass before a release claim.

Keep real notes, databases, credentials, signing files, machine configuration, and private test evidence out of commits. Before changing behavior, define a reproducible check and its expected result; new guards need a named production mutation that proves they can fail for their stated reason. Preserve existing work and propose exact commit file lists for review.

Duck Notes is available under the [MIT License](LICENSE). Dependencies retain their own licenses, listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md); the app and runtime dependency license texts are also packaged in the APK.
