# Duck Notes 0.1.1

Duck Notes is an offline Markdown notes app for Android 8.0 and later. This update improves security and reliability and introduces a consistent Duck visual identity.

- Optional app lock uses your Android device PIN, pattern, or password. Leaving the foreground locks the session, and screenshots and Recents previews are protected.
- Malformed-link parsing avoids repeated suffix scans, and the embedded preferences parser is updated.
- Unreadable or corrupted security preferences fail closed.
- Backup rules require supported encryption capabilities or device transfer; temporary imports remain excluded.
- Tag-merge Undo restores original customization and is invalidated by later metadata changes.
- The Duck mark now appears consistently in the launcher, splash screen, empty states, and About screen.

Markdown editing, nested and pinned tags, search, wiki links/backlinks, image attachments, Archive/Trash, English/Arabic support, and themes remain available offline.

## Installation

Download `duck-notes-0.1.1.apk` from this release. Existing installations can update with the same signing certificate. Do not uninstall or clear app storage when upgrading real notes.

## Privacy and verification limits

App lock controls interface access; Duck does not separately encrypt its files. New backup rules do not erase older backups. Android controls transport behavior and recovery, and cloud backup has a 25 MB per-app quota. See SECURITY.md for dependency applicability and remaining limits.

The APK was checked on an Android 15 emulator, including a signed upgrade from 0.1.0. Physical-phone and OEM certification is not claimed. Public license, certificate, and checksum files accompany the APK.
