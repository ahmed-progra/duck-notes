# Duck Notes 2.0.0

Duck Notes is an offline Markdown notes app for Android 8.0 and later. Version 2 improves writing, organization, and recovery while preserving local notes and Duck's visual identity.

- Responsive navigation keeps the sidebar accessible on phones and wider windows. Focus mode hides secondary panes and formatting controls; Back exits Focus first. Editor scroll position survives pane and Focus changes.
- Undo/Redo includes formatting, tasks, and literal Find/Replace. Replace All is one undoable edit. Primary formatting controls have an accessible overflow menu.
- System, English, and Arabic language selection; right-to-left layouts; light/dark/system themes; serif, sans-serif, and monospace editor fonts at 14-24sp with Android font scaling.
- Pin notes without changing their modification timestamps. Sort by modified date, created date, or title, with deterministic ties. Search combines tag subtree, pinned, unfinished-task, image, and active/archive filters.
- Tap images to zoom; long-press for actions, with confirmation before removal. Pending image-picker results wait for ordinary app authentication.
- Import UTF-8 Markdown/plain text and Duck Markdown ZIP packages as new notes. Export/share individual notes or the filtered collection, including relative image files in ZIP packages. Failed saves block export with Retry.
- Create passphrase-protected `.duckbackup` files containing notes, timestamps, pin/archive/trash state, tag customization, and images. Restore adds missing note IDs and skips existing IDs without overwriting them. Existing tag customization wins. Complete authentication and archive validation happen before restore.
- Security hardening retains device-credential app lock, protected screenshots and Recents, private staging, restricted Android backup rules, and no network permission. Save failure and Retry remain visible in Settings.

## Installation

Download `duck-notes-2.0.0.apk` from this release. Upgrade in place; do not uninstall or clear app storage when keeping real notes. The release uses the original signing certificate. Public certificate, checksums, and license notices accompany the APK.

## Privacy and recovery

App lock controls interface access and does not encrypt the live database or images. Manual backups use authenticated streaming encryption; forgotten backup passphrases cannot be recovered. Backups include Archive and Trash, but exclude credentials and device security settings. Android-managed backup remains subject to transport behavior and the 25 MB cloud quota. See [SECURITY.md](../../SECURITY.md), [portability](../portability.md), and [backup format](../backup-format.md).

Cloud sync, accounts, rich tables/formulas, OCR, widgets, permanent Trash deletion, and live-database encryption are not included. Physical-phone/OEM and native reference-app behavior are not certified; no zero-bug guarantee is implied.
