# DUCK NOTES — Claude Code Build Prompt

Android clone of Bear (iOS markdown notes). Rebranded: **Duck**.
Paste one phase per session. Do not skip phases.

---

## 0. GLOBAL CONTRACT

Applies to every phase. Never override.

**Identity**
- App name: `Duck Notes`. Package: `app.duck.notes`.
- Mascot: duck. Empty states, splash, app icon, onboarding.
- No bear references anywhere. Not in code, strings, or assets.

**Brand tokens**
```
accent      #D7494C   (215,73,76 · HSL 359,64%,56%)
accentPress #B93C3F
surface     #FFFFFF
surfaceDark #1C1C1E
text        #2C2C2E
textMuted   #8E8E93
divider     #E5E5EA
```
Accent used for: tag chips, links, active sidebar row, cursor, selection, note-list active bar, checkbox fill.

**Stack — fixed**
| Layer | Tech |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose (Material 3) |
| Arch | MVVM + Clean Architecture |
| DB | Room |
| Markdown | CommonMark (commonmark-java) |
| Search | SQLite FTS5 |
| Images | Coil |
| DI | Hilt |
| Network | Ktor client |
| Sync | Supabase |
| Prefs | DataStore |
| Crypto | Android Keystore |

**Working rules**
1. State assumptions before coding. Ask if unclear.
2. Minimum code. No speculative features. No abstraction without a second caller.
3. Surgical edits. Match existing style. Never refactor unrelated code.
4. Define verifiable success criteria before writing code.
5. Offline-first always. Network is optional, never required.
6. Every phase ends green: compiles, tests pass, app runs.

**Chain-of-thought protocol per phase**
```
STEP 1 THINK   — restate goal, list unknowns, state assumptions
STEP 2 PLAN    — files to create/modify, data flow, edge cases
STEP 3 CONFIRM — show plan, wait for approval if ambiguous
STEP 4 BUILD   — implement smallest correct version
STEP 5 VERIFY  — run build + tests, check success criteria
STEP 6 REPORT  — what changed, what is still open
```

---

## PHASE 1 — SKELETON

**Goal:** Empty app that runs. Correct module boundaries.

**Think:** Single module or multi-module? Choose multi-module Gradle only if build time justifies it. Default: single `:app` with strict package boundaries.

**Build**
```
app/src/main/java/app/duck/notes/
  core/          theme, ui components, extensions
  data/          room, datastore, remote, repository impls
  domain/        models, repository interfaces, usecases
  presentation/  screens, viewmodels, navigation
  di/            hilt modules
```
- Gradle version catalog (`libs.versions.toml`).
- Hilt setup, `@HiltAndroidApp`, single `MainActivity`.
- Compose BOM, Material 3, edge-to-edge.
- Empty Compose screen rendering "Duck".

**Success:** `./gradlew assembleDebug` passes. App launches. Hilt graph resolves.

**Do not:** Add Room, Ktor, or navigation yet.

---

## PHASE 2 — DATA MODEL + ROOM + FTS5

**Goal:** Persistent notes. Full-text search ready.

**Think:** Tags are derived from note body, not stored by hand. Decide: parse-on-save into a join table. Decide soft-delete strategy. Decide FTS sync trigger vs manual insert.

**Schema**
```
notes(id TEXT PK, title TEXT, body TEXT, created_at, modified_at,
      is_pinned, is_archived, is_trashed, is_locked, is_encrypted,
      trashed_at, sync_state, remote_rev)

tags(id TEXT PK, name TEXT UNIQUE, parent_id TEXT NULL,
     icon_key TEXT, is_pinned INT, sort_order INT)

note_tags(note_id, tag_id, PRIMARY KEY(note_id, tag_id))

note_links(source_id, target_id, link_text)    -- WikiLinks

attachments(id, note_id, uri, mime, width, height, size, created_at)

notes_fts USING fts5(title, body, content='notes', content_rowid=...)
```
- FTS5 external-content table + triggers on INSERT/UPDATE/DELETE.
- `unicode61` tokenizer, `remove_diacritics 2`.
- Index: `modified_at DESC`, `is_trashed`, `note_tags.tag_id`.

**Build:** Entities, DAOs, `DuckDatabase`, migration 1→2 stub, seed 3 sample notes in debug.

**Success:** Instrumented test — insert 5000 notes, FTS query under 50ms, tag filter under 30ms.

**Do not:** Touch UI.

---

## PHASE 3 — DOMAIN LAYER

**Goal:** Business rules independent of Android.

**Build**
- Models: `Note`, `Tag`, `Attachment`, `NoteLink`, `SearchQuery`.
- Repository interfaces: `NoteRepository`, `TagRepository`, `SearchRepository`.
- Use cases (one class, one verb):
  `ObserveNotes`, `ObserveNote`, `SaveNote`, `DeleteNote`, `RestoreNote`,
  `ParseTags`, `ObserveTagTree`, `PinTag`, `SearchNotes`, `ResolveWikiLinks`, `GetBacklinks`.
- Title rule: first non-empty line, markdown stripped. Fallback "Untitled".

**Success:** Pure JVM unit tests for every use case. No Android imports in `domain/`.

---

## PHASE 4 — MARKDOWN ENGINE

**Goal:** Parse and render. Concealed syntax like Bear.

**Think:** Compose has no markdown editor. The editor is a `BasicTextField` over raw markdown + a visual transformation that styles inline and hides syntax tokens when the cursor is outside the span. This is the hardest part of the app. Prototype it alone before wiring anything else.

**Build**
- CommonMark parser + extensions: tables, strikethrough, task lists, autolink, footnotes, YAML front matter.
- Custom extension: `#tag`, `#multi word tag#`, `#nested/tag`, `[[WikiLink]]`.
- `MarkdownVisualTransformation`: maps raw offsets → styled offsets.
- Conceal rules: hide `**`, `_`, `#`, backticks when caret not inside the node. Reveal on caret entry.
- Block renderers: heading, quote, code fence with syntax tint, divider, table, task line, image, LaTeX (later phase, stub now).

**Success:** Golden tests — 20 markdown samples → expected `AnnotatedString`. Typing at 60fps on a 5000-word note.

**Do not:** Build a WYSIWYG rich-text model. Plain text is the source of truth.

---

## PHASE 5 — DESIGN SYSTEM

**Goal:** Duck visual language. One source of truth.

**Build**
- `DuckTheme`: light, dark, plus 4 named themes (Duck Red default, Graphite, Solarized, Dieci).
- Typography: serif reading font + mono for code. User-swappable font family later.
- Spacing scale 4/8/12/16/24/32. Corner radius 8/12/16.
- Components: `DuckTagChip`, `DuckCheckbox`, `DuckDivider`, `DuckSearchField`, `DuckIconButton`, `DuckEmptyState` (duck illustration), `DuckBottomSheet`.
- **DuckCons**: tag icon set. ~60 vectors mapped by tag name keyword. Sidebar and inline in editor.

**Success:** Theme switch with no recomposition jank. Contrast passes WCAG AA on both modes.

**Do not:** Hardcode a color anywhere outside the theme file.

---

## PHASE 6 — NAVIGATION SHELL

**Goal:** Three-pane on tablet, stacked on phone. Matches the reference layout.

**Layout**
```
Sidebar          Note list           Editor
Notes            search field        title
 Untagged        note card:          body
 Todo             title              toolbar (bottom)
 Today            2-line preview
 Locked           thumbnail row
Archive           timestamp
Trash            accent bar = active
—————
pinned tags
tag tree (nested, collapsible)
```
- `WindowSizeClass`: Compact = one pane + navigation; Expanded = list-detail-detail.
- Navigation Compose, type-safe routes.
- Sidebar as `ModalNavigationDrawer` on phone.

**Success:** Rotation and split-screen preserve state. Back stack correct on both form factors.

---

## PHASE 7 — TAGS

**Goal:** Tags are the only organization system. No folders.

**Think:** Nested tags are a tree derived from `/` in flat names. Renaming a parent rewrites note bodies. That is a text edit across N notes — do it in one transaction with progress.

**Build**
- Parse tags on every save. Diff against `note_tags`. Delete orphan tags.
- Sidebar tree: expand/collapse, counts, pinned section on top.
- Tag chip inline in editor, tappable → filter list.
- Actions: pin, rename (rewrites bodies), change DuckCon, delete (untag, keep notes).
- Drag note onto tag = append tag to body.

**Success:** Rename `#work` → `#job` updates 200 notes in one transaction, undoable.

---

## PHASE 8 — EDITOR

**Goal:** The thing users spend 95% of their time in.

**Build**
- Autosave debounce 400ms. Never lose a keystroke on process death.
- Format toolbar above keyboard: H, checkbox, list, bold, italic, highlight, link, table, image, more.
- Tasks: `- [ ]` / `- [x]`, tap toggles, thin progress line under note card in list.
- Section folding by heading. Persist fold state per note.
- Images: pick, insert, Coil render inline, tap to zoom, resize, delete.
- Paste handling: URL → link, image → attachment, markdown → raw.
- Undo/redo stack.
- Focus mode: hide chrome, dim non-current paragraph.
- RTL support (Arabic). Bidi-safe markdown rendering.

**Success:** Kill the app mid-typing — no data loss. 10k-word note scrolls at 60fps.

---

## PHASE 9 — LINKING + INFO PANEL

**Goal:** Personal wiki without a graph database.

**Build**
- `[[Note title]]` autocomplete popup while typing.
- Resolve on save → `note_links` rows. Unresolved link = create-on-tap.
- Info panel (bottom sheet / side pane): word count, char count, created, modified, table of contents from headings, backlinks list, unlinked mentions (title appears as plain text elsewhere).
- ToC tap scrolls to heading.

**Success:** Backlinks correct after rename. Unlinked-mention query under 100ms at 5000 notes.

---

## PHASE 10 — SEARCH

**Goal:** Instant, tolerant, syntax-aware.

**Build**
- FTS5 `MATCH` with prefix queries, debounce 200ms.
- Operators: `@todo`, `@untagged`, `@today`, `@images`, `@locked`, `@files`, `tag:x`, `-exclude`, `"exact phrase"`.
- Result highlighting via `snippet()`.
- Recent searches, saved searches.
- Android app-search / quick settings tile integration.

**Success:** Typing a query returns first results under 100ms at 10k notes.

**Do not:** Implement OCR now. Stub it.

---

## PHASE 11 — EXPORT / IMPORT

**Goal:** No lock-in. Trust.

**Build**
- Export single or bulk: `.txt`, `.md`, `.html`, `.pdf`, `.docx`, `.jpg`, `.epub`.
- Bundle format `.duckbundle` = markdown + assets folder, zipped.
- Import: markdown files, folders, Bear `.bearnote`/`.textbundle`, plain text.
- Share sheet target: receive text, URL, image → new note.

**Success:** Round trip — export 50 notes with images, wipe, import, byte-identical bodies.

---

## PHASE 12 — SECURITY

**Goal:** Locked notes are actually locked.

**Think:** Encryption is easy to get wrong. Be explicit about the key hierarchy before writing code.

**Build**
- Android Keystore master key, AES-256-GCM, `setUserAuthenticationRequired(true)` for locked notes.
- Per-note DEK wrapped by master key. Encrypt body + attachments.
- BiometricPrompt with device-credential fallback.
- Locked note excluded from FTS index and from previews.
- `FLAG_SECURE` when a locked note is open. Blur in recents.
- App lock timeout in settings.

**Success:** Locked note body unreadable in a DB dump. Fails closed if biometrics are removed.

---

## PHASE 13 — SYNC

**Goal:** Offline-first. Cross-device. Last edit wins with conflict copies.

**Build**
- Supabase: `notes`, `tags`, `note_tags`, `attachments` tables + Row Level Security by user id.
- Ktor client, auth token in encrypted DataStore.
- Outbox pattern: local writes queue `sync_state = PENDING`.
- WorkManager periodic + on-connectivity sync.
- Conflict: compare `remote_rev` and `modified_at`. On divergence keep both — create "Conflicted copy" note. Never silently discard.
- Attachments to Supabase Storage, lazy download.
- E2E-encrypted notes sync as ciphertext only.

**Success:** Two emulators, airplane mode edits on both, reconnect → both converge, zero data loss.

**Do not:** Block the UI on network. Ever.

---

## PHASE 14 — SETTINGS + PERSONALIZATION

**Build**
- DataStore-backed: theme, font family, font size, line height, editor width, first-day-of-week, autosave, app lock timeout, default new-note tag.
- App icon variants (duck set) via activity-alias.
- Sort options: modified, created, title, pinned first.
- Trash auto-purge after 30 days.

**Success:** Every setting applies without restart.

---

## PHASE 15 — CAPTURE + WIDGETS

**Build**
- Glance widgets: new note, search, last edited, random note.
- Quick Settings tile → new note.
- Share target + text selection "Save to Duck".
- Assistant shortcut for voice capture.

**Success:** Cold-start-to-typing under 800ms from widget.

---

## PHASE 16 — HARDENING

**Build**
- Unit tests: domain 90%+. Instrumented: DAO, FTS, crypto, sync.
- Compose UI tests for editor, search, tag rename.
- Baseline Profile + R8 config.
- StrictMode clean. LeakCanary clean.
- Crash-free startup, ANR check on 10k-note DB.
- Accessibility: TalkBack labels, 48dp targets, dynamic type.

**Success:** Release APK, no P0 lint, cold start under 500ms on mid-range device.

---

## EXECUTION ORDER

```
1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12 → 13 → 14 → 15 → 16
```
Phases 4 and 8 carry the most risk. Budget the most time there.
Phases 12 and 13 are the most dangerous to get wrong. Write the threat model first.

---

## PER-PHASE PROMPT TEMPLATE

Paste this wrapper around each phase.

```
Read GLOBAL CONTRACT above. It overrides your defaults.

We are on PHASE <n>: <name>.

STEP 1 — THINK
Restate the goal in your words.
List every unknown. List every assumption you are making.
Name the two riskiest parts of this phase.

STEP 2 — PLAN
List exact files to create and modify.
Describe the data flow in 5 lines or fewer.
List edge cases you will handle and ones you will deliberately skip.

STEP 3 — CONFIRM
Show me the plan. Stop. Wait.

STEP 4 — BUILD
Smallest correct implementation. No extras.

STEP 5 — VERIFY
Run ./gradlew assembleDebug and the tests.
Check each success criterion explicitly. Show output.

STEP 6 — REPORT
Changed files. Open issues. What Phase <n+1> now assumes.
```
