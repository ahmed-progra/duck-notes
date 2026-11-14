# Duck Notes 2.0.0 qualification

These results apply to the local release candidate. Public release signing and download verification must be repeated after the reviewed source is merged.

- Debug and release JVM suites: 665 tests each, with no failures, errors, or skips. Android regression suite: 98 passing tests on the owned API 35 emulator. Debug/release builds and lint passed.
- Signed upgrades from public 0.1.0 and the local 0.1.1 candidate preserved the seeded notes, image bytes, tags and customization, links, Archive/Trash state, and preferences without clearing storage between versions.
- A signed-release backup restored notes and images onto a separate installation. Repeating restore skipped existing IDs and preserved newer local edits and preferences. Restore publication was also interrupted before and after database commit; startup recovered orphaned files and retained committed records respectively.
- Real editor, picker, document-provider, sharing, database-failure, and startup checks exercised success, failure, cancellation, and authentication boundaries. Invalid archives, wrong passphrases, tampering, truncation, and bounded-input rejection are covered by targeted tests. Named production mutations were run to demonstrate that selected security, data-preservation, and writing guards fail for their stated reasons, then reverted.
- Release checks verified the original signing certificate, package/version, checksums, bundled notices, no network permission, DEX content, and native library alignment. Candidate and Git-history secret scans passed. Dependency advisory applicability is documented in SECURITY.md.
- The installed TalkBack service remained enabled with touch exploration while the signed app delivered accessibility-focus and keyboard-focus events. The opt-in test restored the device's original accessibility settings afterward; it does not assess speech pronunciation or physical keyboard hardware.

Keyboard, scaling, resizing, English/Arabic, and light/dark checks use an emulator. Physical phones, OEM variations, spoken-audio quality, and the native reference app are not certified. These checks do not establish that every device or possible failure is covered, or that the app has no bugs.
