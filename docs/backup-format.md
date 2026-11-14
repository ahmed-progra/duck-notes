# Encrypted portable backups

Duck backups use the `.duckbackup` extension and contain the complete offline collection: note IDs, Markdown, creation/modification times, pin/archive/trash state, images, and tag customization. Caches, search indexes, app credentials, preferences, and device security settings are excluded. Titles, tags, links, and search rows are rebuilt from note bodies on restore.

Creating a backup requires matching passphrases of 12–1,024 UTF-16 code units. Restore uses the original passphrase verbatim, without trimming or normalization. Duck does not save the passphrase. There is no forgotten-passphrase recovery. This format protects the backup file; it does not encrypt the live note database. Whole-app lock is a separate device-credential access control.

## Version 1 envelope

Integer fields use big-endian byte order. The fixed prefix is 48 bytes.

| Field | Bytes | Value |
|---|---:|---|
| Magic | 8 | ASCII `DUCKBKUP` |
| Version | 4 | `1` |
| PBKDF2 iterations | 4 | `600000` |
| Salt | 16 | Fresh cryptographically random bytes |
| Wrapping nonce | 12 | Fresh cryptographically random bytes |
| Wrapped keyset length | 4 | Bounded to 17–4,096 bytes |
| Encrypted body length | 8 | Exact remaining length after the wrapped keyset |
| Wrapped keyset | Variable | Random Tink keyset, encrypted with AES-256-GCM |
| Encrypted body | Variable | Tink streaming ciphertext of the backup ZIP |

PBKDF2-HMAC-SHA256 derives a 256-bit wrapping key from the passphrase and salt. AES-GCM uses a 128-bit authentication tag and authenticates the first 56 bytes as associated data. Google Tink Android 1.24.0 generates the content keyset and encrypts the ZIP using `AES256_GCM_HKDF_1MB`; its associated data is the fixed 48-byte prefix. The two authenticated records bind every semantic header field. Restore accepts exactly one primary key with these streaming parameters.

The complete ciphertext is read to authenticated EOF into private staging before any ZIP parser or restore operation runs. Segment authentication alone is insufficient to authorize restore. Wrong passphrases, unsupported versions or iteration counts, inconsistent lengths, appended data, truncation, and authentication failures reject the file. The passphrase arrays and temporary serialized keysets are cleared after use; managed-runtime memory cannot be guaranteed to contain no residual copies.

## Inner archive and restore

The authenticated plaintext uses the strict [portable ZIP layout](portability.md), with manifest `kind` set to `backup`. Parsed image references retain `attachment:<UUID>` identities. Only tag rows with pin, icon, or order customization are included; ordinary tags are derived from restored note bodies.

Restore adds missing note IDs and skips IDs already present. It never overwrites existing note bodies, timestamps, or states. Existing tag customization wins by name. Conflicting attachment ownership, identity, or bytes reject the operation. Missing images, duplicate entries, traversal, malformed UTF-8, inconsistent metadata, and oversized collections also reject it. The interface reports restored and skipped note counts.

Limits are 10,000 notes, 10,000 images, 2 MiB per note, 64 MiB total note text, and 1 GiB for each archive and its expanded data. Existing per-image limits still apply. The manifest is limited to 16 MiB, ZIP central metadata to 8 MiB, and entries to 20,001. Tag names are limited to 256 UTF-16 code units and 32 path segments, with 10,000 distinct names per package. Limits reject the collection visibly; notes and images are never silently omitted.

Files are staged under Android's private `noBackupFilesDir`. New attachment publication uses durable recovery markers and the same lock as startup recovery. Restored database rows commit in one bundled-SQLite transaction. Failure before commit removes only newly published files; startup recovery retries marked cleanup after process death. Private cleanup failures cannot turn an already committed restore into a claim that no changes occurred.

Document-picker callbacks queue a URI without opening it. Reading the file and showing the passphrase prompt require ordinary app authentication and a resumed activity. Passphrases are excluded from saved state and background-job data. Backgrounding clears the input fields and their undo history and cancels active passphrase-based work before restore commits.

See Google's [streaming AEAD documentation](https://developers.google.com/tink/streaming-aead) and [Android setup documentation](https://developers.google.com/tink/setup/java).
