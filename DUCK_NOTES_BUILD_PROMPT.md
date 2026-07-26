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
- `DuckTheme`: light, dark, plus 4 named themes (Duck Red default, Graphite, Sepia, Midnight).
  *Was "Solarized, Dieci". Renamed in Phase 5a: Solarized's published values do not clear WCAG AA for
  body text, and a Solarized retuned until they do is not Solarized. Dieci's would have been invented
  outright. Both slots are now palettes we authored, named for what they are — Sepia keeps the warm
  cream / cold slate character, Midnight the dark blue-grey. Renaming avoids a per-theme AA exemption,
  which would have made the contrast criterion mean less than it appears to.*
- Typography: serif reading font + mono for code. User-swappable font family later.
- Spacing scale 4/8/12/16/24/32. Corner radius 8/12/16.
- Components: `DuckTagChip`, `DuckCheckbox`, `DuckDivider`, `DuckSearchField`, `DuckIconButton`, `DuckEmptyState` (duck illustration), `DuckBottomSheet`.
- **DuckIcons**: tag icon set. Curated keyword table, exact-match after canonicalization — no fuzzy
  matching. **8 vectors shipped in Phase 5b**, growing toward ~60 with no logic change, since the
  resolver is set-size-independent. Sidebar and inline in editor.

**Success:** Theme switch with no recomposition jank. Contrast passes WCAG AA on both modes.

**Do not:** Hardcode a color anywhere outside the theme file.

*Split into **5a** (token layer — shipped) and **5b** (icons + components — below), so the riskiest
change, threading the theme through the markdown engine, landed in a commit by itself. 4a/4b precedent.*

---

## PHASE 5b — DuckIcons + COMPONENTS

**Goal:** The components, on top of 5a's finished token contract.

**Build**
- `DuckIcons`: tag icon set. Curated keyword table, resolver set-size-independent. **8 drawables to
  start**, growing toward ~60 without logic change. The eight are `Tag` (the mandatory fallback),
  `Todo`, `Work`, `Home`, `Idea`, `Book`, `Code`, `People` — the widest keyword families in personal
  note-taking, and the ones drawable as legible 20dp single-tint glyphs offline.
- Components: `DuckTagChip`, `DuckCheckbox`, `DuckDivider`, `DuckIconButton`, `DuckEmptyState`.
  Deferred to their consumers: `DuckSearchField` (Phase 10 owns debounce, operators, `snippet()`
  highlighting), `DuckBottomSheet` (Phase 9 owns the info-panel contents that drive sheet behaviour).
- Duck mascot for empty states — an acknowledged placeholder, same terms as the launcher icon.

### Blocking prerequisite — the label-on-fill floor is missing

**Do this before building any component.** 5a's Duck floor table asserts `accent on surface` at
**3:1** — a fill on a page. It never assigns the **4.5** floor to that same pair used as *a label on
that fill*. Contrast is symmetric, so the number is already computed; it is filed under the wrong
rule. This is a missing **floor assignment**, not a missing measurement.

`DuckGalleryScreen`'s `Pill` in `src/debug` does exactly the unasserted thing — `background =
colors.accent`, `color = colors.surface`, `uiLabel`. Harmless there, but `DuckTagChip` is specced as
accent fill + label. If it copies the gallery, the defect Blocker 1 took three rounds to remove ships
into `src/main`, and **neither test can see it**: `ContrastTest` does not know component pairs, and
`TokenSourceTest` does not scan `src/debug`.

Measured, so 5b starts from data:

| palette | `surface` on `accent` | `surface` on `accentPress` | `accentText` on `tagSurface` |
|---|---|---|---|
| DuckRedLight | **4.25 FAIL** | 5.55 | 4.53 |
| DuckRedDark | **4.01 FAIL** | **3.06 FAIL** | 5.46 |
| GraphiteLight | 6.15 | 8.66 | 6.59 |
| GraphiteDark | 5.63 | **3.87 FAIL** | 6.31 |
| SepiaLight | **3.35 FAIL** | 4.77 | 4.55 |
| SepiaDark | 5.46 | **3.71 FAIL** | 6.05 |
| MidnightLight | 5.19 | 7.36 | 6.03 |
| MidnightDark | 5.20 | **3.55 FAIL** | 6.28 |
| **failures at 4.5** | **3 of 8** | **4 of 8** | **0 of 8** |

**`accentPress` is not the escape hatch.** It clears 4.5 in DuckRedLight (5.55), which is what makes it
look like one, but it is *worse overall* — **4 of 8** fail against accent's 3 of 8. In dark mode
`accentPress` is darker than `accent`, so the dark `surface` label collapses onto it. Swapping the chip
fill to `accentPress` trades three failures for four.

**The safe pattern already exists and is already asserted.** `accentText` on `tagSurface` passes
**8 of 8**, and it is exactly what `MarkdownStyles` already paints for an inline `#tag`
(`StyleKey.TAG → color = accentText, background = tagSurface`). The editor has been right all along;
the gallery `Pill` invented a pattern nothing checks. Making the chip match the editor is the right
answer for product consistency as much as for contrast.

**So, in order:**
1. `DuckTagChip` uses tint + `accentText`, matching `StyleKey.TAG`.
2. Fix the gallery `Pill` in the same change, so the reference implementation stops being the bad one.
3. Add `surface on accent` at 4.5 to the Duck floor table **only if** some component genuinely needs a
   solid accent fill under a text label, and re-tune the three failing palettes — a palette input,
   same class as 5a's `proseMuted`/`codeSurface` coupling. Worked, not discovered.
4. **Tune `DuckRedLight.tagSurface`/`accentText` first.** That pair is 4.53 — a 0.03 margin.
   Numerically fine, but it is about to become load-bearing for every chip, and 0.03 leaves no design
   room if 5b wants `tagSurface` lighter for a border or hover state.
5. The checkbox tick is a **meaningful graphic under WCAG 1.4.11 — 3:1, not 4.5.** All eight palettes
   already clear 3:1 on `surface`/`accent`, so the tick is fine as an accent fill. Do not over-tune it
   into a floor it does not owe.

**Success:** Every component pair asserted at its correct floor. Icon resolver total — unmatched tag
falls back to the generic glyph, `#a/b/c` resolves leaf-first with walk-up.

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

### Blocking prerequisite — disabled states

Two things go wrong at the first disabled state, and only one of them is about contrast.

**A disabled control owes no contrast floor.** WCAG 1.4.11 exempts *"inactive user interface
components"* outright, and 1.4.3 exempts incidental text including inactive controls. Nothing below is
an accessibility floor and nothing here should be read as one. `ContrastTest` does assert 3:1 on the
disabled checkbox tick, via `chromeMuted on surface` — that is **over-assertion**. Harmless, it passes,
and it is not a precedent.

**The real requirement is a Duck design rule: a disabled control must be *distinguishable* from an
enabled one.** A control that looks live and does nothing invites a tap. A dim control still reads as
disabled, so *no* dimming is worse than poor dimming — which makes the alpha problem below and the
affordance problem here pull in opposite directions, and is why both need stating.

#### 1. Material's disabled path reintroduces alpha

Phase 5a stores every tint **pre-composited and opaque** because `ContrastTest` measures two opaque
colours; an alpha tint has no measurable colour until it is drawn over something. Material composites a
colour that is on screen and in no palette — structurally the same hole as `surfaceTint` reaching
elevated surfaces through an unmeasured blend, and just as invisible to the suite.

**State of play at the end of 5b** (read from the `material3 1.4.0` bytecode, not from memory):

| component | `enabled`? | composite on screen? | distinguishable? |
|---|---|---|---|
| `DuckIconButton` | yes | no | yes — 2.56 worst |
| `DuckCheckbox`, unchecked | yes | no | yes, **after a 5b fix** — was 1.0000 |
| `DuckCheckbox`, checked | yes | no | **no — open defect, 1.0024** |
| `DuckTagChip` | **no** | unreachable, and this is the one that bites | n/a |
| `DuckDivider`, `DuckEmptyState` | no | n/a | n/a |

- **`DuckIconButton` is clean, and one decision is why.** `IconButton` computes
  `disabledContentColor = LocalContentColor × 0.38` and provides it to its content. Nothing reads it:
  `DuckIconImage` paints through foundation's `Image` with an unconditional `ColorFilter.tint`, not
  material3's `Icon`, so `LocalContentColor` is unreachable rather than merely unused — and the required
  `tint` parameter is what forces `DuckIconButton` to name its own opaque disabled colour. Container is
  `Transparent` in both states. **Do not give that parameter a default.**
- **`DuckCheckbox` is clean on purpose.** `CheckboxColors` has **12** colour fields; the
  `CheckboxDefaults.colors(...)` convenience overload accepts 6 and fills the rest from tokens at
  `0.38`. Our five arguments populate seven fields; three more are literal `Transparent`. The two left
  at `0.38` are `disabledIndeterminateBoxColor` and `disabledIndeterminateBorderColor`, reachable only
  from `ToggleableState.Indeterminate`, which a `Boolean` checkbox cannot produce.

#### 2. Two affordance defects, found by measuring foreground against foreground

Both states passed their own background check and then rendered the same. That axis —
enabled colour vs disabled colour for the same element — is now
`ContrastTest.everyDisabledStateIsDistinguishableFromEnabled`.

- **Fixed in 5b:** disabled + unchecked was `chromeMuted` vs `chromeMuted`, **exactly 1.0000 in all
  eight palettes**. An unchecked box is a border and nothing else, and `uncheckedColor` and
  `disabledUncheckedColor` were the same token, so a disabled checkbox was pixel-identical to a live
  one. `disabledUncheckedColor` is now `divider`: 2.60 from `chromeMuted` at worst, and it recedes,
  which is the direction a disabled control should move.
- **Open, deferred, and measured:** disabled + checked is `chromeMuted` against an enabled `accent`,
  which clears 1.2 in four palettes and fails in four — SepiaLight 1.0024, GraphiteDark 1.0872,
  SepiaDark 1.1014, MidnightDark 1.0051. M3 1.4 exposes no `disabledCheckedCheckmarkColor`, so the tick
  is shared by both states and the disabled fill must recede toward the page *and* keep that tick
  legible. **Those oppose each other per mode, not absolutely** — receding means darker on a dark page
  and lighter on a light one:

  | mode | answer |
  |---|---|
  | dark ×4 | `accentPress`, the only palette slot inside the window (1.31–1.47, tick 3.06–3.87) |
  | light ×3 | **already correct** — `chromeMuted` is lighter than `accent` and recedes |
  | SepiaLight | empty by 0.0241, because `surface` is cream `#FBF5E6` not white; needs an `onAccent` tick, which `onSurface` satisfies at 3.61 with `divider` as the fill at 2.67 |

  | tier | cost | result |
  |---|---|---|
  | 0 — today | none | 4 of 8 fail |
  | 1 | one slot, 8 values | 8 of 8 clear 1.2; direction right in 7 |
  | 2 | two slots, 16 values | 8 of 8 clear **and** recede; SepiaLight becomes the only theme with a dark tick |

  **Tier 0 is the current decision**, on the ground that nothing disables a checked box, so a 17th slot
  and eight hand-tuned values would be paid for a state that may never ship — Phase 12 may render a
  locked note without checkboxes at all.

  **Trigger:** the first screen that disables a checked checkbox must either apply tier 1 or 2, or
  record why the state does not arise there. Do not let it ship on tier 0 by default — that is the
  decision expiring silently, which is the failure mode this whole section exists to prevent.

  An earlier version of this section called the state *structurally impossible*. It is not, and the
  error is worth keeping: that verdict came from minimising each constraint across all eight palettes at
  once, which collapsed two opposite directions into one unsatisfiable constraint and hid an answer that
  was already sitting in three palettes. **Palettes are per-mode data. A constraint that inverts with
  mode must be solved per mode.**

#### 3. The prerequisite proper — `DuckTagChip`

It has no `enabled` today, which is the only reason it is safe. `SelectableChipColors` has **13** fields
and `FilterChipDefaults.filterChipColors(...)` sets four of ours; the day a chip greys out, these go
live from `FilterChipTokens`:

| field | alpha |
|---|---|
| `disabledLabelColor` | **0.38** |
| `disabledLeadingIconColor` | **0.38** |
| `disabledSelectedContainerColor` | **0.12** |
| `disabledUnselectedOutlineColor` | **0.12** |

At 0.12 the selected fill and the unselected border are all but gone — and the border is what carries
1.4.11 for this component, because the fill itself is only 1.04–1.16 against an elevated container.

**Before adding `enabled` to any component, both of these — not one:**

1. **Pass the disabled colours in explicitly, opaque.** Not to hit a contrast floor — a disabled control
   owes none — but so the colour on screen is a palette value that `ContrastTest` can see at all. A
   0.12 composite is unmeasurable by construction, so "is this still visible?" becomes unanswerable
   rather than merely unanswered.
2. **Add the enabled/disabled pair to `distinguishability`.** This is the assertion that carries the
   real requirement, and it is the one that catches the failure the alpha check cannot: two colours that
   both pass against their background and are identical to each other. Note the failure direction — the
   0.12 composites make the chip *too faint*, while the defects actually found in 5b made controls *not
   faint enough*. Assert the pair, do not reason about which risk applies.

The exemption question is already settled and does not need re-deciding: WCAG owes nothing here. What
needs deciding each time is whether the control still reads as inactive, and that is a number.

---

## PHASE 7 — TAGS

**Goal:** Tags are the only organization system. No folders.

**Think:** Nested tags are a tree derived from `/` in flat names. Renaming a parent rewrites note bodies. That is a text edit across N notes — do it in one transaction with progress.

**Build**
- Parse tags on every save. Diff against `note_tags`. Delete orphan tags.
- Sidebar tree: expand/collapse, counts, pinned section on top.
- Tag chip inline in editor, tappable → filter list.
- Actions: pin, rename (rewrites bodies), change DuckIcon, delete (untag, keep notes).
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

**Known defect carried from Phase 4 — fix before writing `note_links` rows**

`[[WikiLink]]` has two implementations that disagree, which is the exact failure `TagScanner` was
extracted to prevent — in the other syntax.

- `ResolveWikiLinks` is a bare `Regex("\\[\\[([^\\[\\]]+)]]")` over the whole body. **No code
  masking, no fence tracking.** It resolves `` `[[X]]` `` and a fenced `[[X]]` as real links.
- `MarkdownScanner` correctly refuses both: a fenced line yields only a `FENCE_BODY` token and never
  reaches inline scanning, and `scanInline` skips offsets masked by `TagScanner.codeSpanMask`.

So a link written inside a code sample will be painted as plain text but still written to
`note_links`, producing a backlink the user cannot see or click.

**This is currently failing by omission, not by test.** `TagAgreementTest.
theEditorPaintsTheWikiLinksResolveWikiLinksFinds` passes only because no `MarkdownCorpus` sample
contains a `[[...]]` inside a code span or fence. Adding one is the first step — it should go red.

**Fix:** extract a `WikiLinkScanner` beside `TagScanner` in `domain/markdown/`, owning the bracket
rule and reusing `codeSpanMask` plus the fence carry. `ResolveWikiLinks` becomes a thin view over it,
exactly as `ParseTags` is over `TagScanner`, and `MarkdownScanner` calls it instead of reimplementing
the rule. One implementation, three views. Do this **before** `note_links` rows are written, or the
bad rows outlive the fix.

**Also from Phase 4 — the CommonMark side.** `[[WikiLink]]` is deliberately absent from
`DuckSyntaxExtension`. A `customInlineContentParserFactory` triggered on `[` never fires: `[` is
CommonMark's own bracket machinery and is resolved before custom inline parsers run. Changing what
brackets mean requires `Parser.Builder.linkProcessor(...)` with the beta
`LinkProcessor`/`LinkInfo`/`LinkResult` API. That was left undone because nothing read the resulting
AST node yet — Phase 9 is the first consumer, so build it here and have it delegate to
`WikiLinkScanner` too.

**Success:** Backlinks correct after rename. Unlinked-mention query under 100ms at 5000 notes.
A `[[link]]` inside a code fence produces no `note_links` row.

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
