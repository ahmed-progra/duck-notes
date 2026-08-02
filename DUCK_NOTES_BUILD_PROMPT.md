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

## PHASE 5 — CLOSED

Token layer, icons and components shipped across `bb2ec56` (5a) and `e4fcbfd` (5b), with four
follow-up corrections. 205 unit tests, 21 instrumented, release APK proven free of the debug gallery.
**No open visual items.**

**Criterion 6 is met.** All eight glyphs were named correctly **blind** on device at high confidence —
checkmark, briefcase, house, lightbulb, book, brackets, people, tag. No drawable needs cutting. They
remain acknowledged placeholders in craft terms, but legibility at 20dp is verified, not assumed.

### What is deferred, and on what ground

**Disabled + checked on `DuckCheckbox`** fails the 1.2 distinguishability rule in 4 of 8 palettes. The
fix is measured and sits in the Phase 6 prerequisite above. The tier table there is a real trade —
**tier 1 buys correctness with a hand-tuned hex, tier 2 buys it with a permanent slot and no new hexes
at all** — but the tiers are not why it is deferred. **Tier 0 stands on there being no caller, and it
would stand at any price.** Do not re-open this as a cost question.

### Two limits on the device check — read these before trusting a screenshot

1. **It was run under DuckRed light, which is a *passing* palette for disabled-checked** (1.3020). The
   run therefore neither confirms nor refutes the deferred `SepiaLight` case at 1.0024. **The first
   person to look at SepiaLight should expect to see the defect**, and should not treat it as a
   regression introduced since.
2. **The disabled checkbox was distinguished by *hue*, not luminance.** That is exactly what the 1.2
   rule declines to measure, and the omission is deliberate: hue is what a greyscale display, a
   monochrome screenshot, and an achromatopsic user all lose. A control that separates only by hue
   reads as fine to most sighted reviewers and vanishes for the people the rule exists to protect.
   **Do not let "looks fine to me" on a screenshot override a measured 1.00.** The measurement is not a
   proxy for the look; it is the part of the look that the reviewer cannot see.

### The harness gap this closed

The gallery rendered three checkbox states and omitted **disabled-unchecked** — the exact state
`ce559b4` fixed. A disabled control is judged against the **enabled** one, not against the page, so a
single instance can never show whether the two differ: the defect was invisible in the harness before
the fix and would have stayed invisible after it. Both empty boxes are now adjacent and labelled.

Generalised: **a harness that shows one of a pair cannot verify a pair.** Same shape as the
distinguishability list itself — every entry that existed passed, and the missing one was missing.

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

### Two deviations from the spec above, and why

Both were taken deliberately. Neither is a shortcut — each *removes* work the spec's version creates.

**1. No Navigation Compose. Panes are derived from state.** A list-detail layout that also has a
`NavHost` stores `selectedNoteId` twice — once in a route argument, once in the ViewModel — and every
Expanded↔Compact resize then has to reconcile a back stack against pane state. Deriving the panes from
one piece of state deletes the reconciliation instead of implementing it, and back becomes a four-line
`BackHandler` that is enabled only on Compact, because that is the only mode where anything is stacked.
The type-safety the spec wants from routes is a `ShellFilter` sealed interface, checked by the compiler
rather than by a serialiser. Revisit if a phase introduces a genuinely new destination — settings,
Phase 12's lock screen — rather than a pane.

**2. No `WindowSizeClass`. `paneModeFor(Dp)` is a pure function.** `calculateWindowSizeClass` is
experimental, soft-deprecated in favour of the adaptive libraries, needs an `Activity` handle and
another dependency, and **cannot be tested off-device** — the only way to check its breakpoints is to
drag a window. A size class *is* a width threshold, so `paneModeFor` is the whole of it, and
`ShellStateTest.paneBreakpointsAreMaterialsOwn` asserts 599/600/839/840 on the JVM in milliseconds.
Material's own numbers, kept.

There is a third, smaller one: **Medium (600–839dp) shows list + editor with the sidebar still in the
drawer**, not three panes. Three panes at that width leave the editor too narrow to read a serif body
in, which is the one thing the editor exists to do.

### Sidebar rows: all seven filter, and two needed a query

Five are fields `Note` already carries and cost nothing (`All` = not archived, `Today` via the injected
`Clock`, `Locked`, `Archive`, and `Todo`). `Untagged` and `Trash` each needed one `NoteDao` query,
because `observeNotes` hides trashed rows by contract and knows nothing about tag membership.

**`Todo` goes through `MarkdownScanner.scanLine`, not a `- [ ]` regex.** The regex is shorter and is a
second implementation of one syntax — the defect Phase 9 documents for `[[WikiLink]]`, where
`ResolveWikiLinks` and the scanner disagree about fences. Reusing the scanner makes the filter
fence-correct for free (a task inside a code block yields `FENCE_BODY` and never reaches the block
prefix) and means the rule can only ever change in one place.

**`Locked` is empty until Phase 12** and that is the honest result, not a stub: the row filters
`isLocked` correctly and finds nothing, which is what "no locked notes" means today.
`DuckShellViewModelTest.lockedIsEmptyUntilPhase12ButFiltersCorrectly` proves it is a real filter by
seeding a locked note and watching the same row find it.

**No search field.** The spec's layout puts one in the note-list header. A field that accepts text and
does nothing is not a stub, it is a lie in the UI, and making it true is Phase 10's entire Build list —
debounce, operators, `snippet()` highlighting. Phase 5b deferred `DuckSearchField` on the same ground.
The slot is there and empty on purpose.

### What the token layer's foresight bought

The shell paints 21 (foreground, background) pairs and **added nothing to `ContrastTest.duckPairs`** —
every one was already floored. That is not luck: Phase 5 and 5b floored `onSurface`, `proseMuted`,
`chromeMuted` and `accent` against `surfaceElevated` specifically because "Phase 6 puts this in the
sidebar", and the shell only ever paints on `surface` or `surfaceElevated`.

Two pairs it *would* have added were designed out instead. The obvious active-row treatment is a
`tagSurface` fill, and enumerating that fill's pairs before writing it rejected it twice over:
`proseMuted` on `tagSurface` is **4.4751 in GraphiteLight** — under the 4.5 a 12sp timestamp owes as
normal text, with 4.5404 and 4.5148 in two more palettes — and `accent` on `tagSurface` is **2.7201 in
SepiaLight**, under the 3:1 a graphic owes, the same number `DuckTagChip` already records for its
leading icon. `onSurface` on `tagSurface` is fine everywhere; it is the quiet text and the bar that
fail, which is exactly what a "looks fine to me" review misses.

So the active row is an **`accent` bar plus a SemiBold weight** — two signals, never colour alone, both
sitting on surfaces already floored. `ContrastTest.tintedActiveRowsAreRecordedPendingAPaletteInput`
records all three columns across all eight palettes, so a future phase that wants a tinted row knows
which numbers it has to move first.

**Rows are `Modifier.selectable`, not `NavigationDrawerItem`,** for the same reason: M3 defaults
`selectedContainerColor` to `secondaryContainer`, which our projection maps to `tagSurface` — 1.04–1.16
on `surfaceElevated`, already recorded as a ghost tint. Overriding it in both states leaves the
component supplying only a touch target, a ripple and selected semantics, all of which `selectable`
gives directly with fewer defaults to fight. The one M3 container that does reach the screen is
`ModalDrawerSheet`'s, which defaults to `surfaceContainerLow` → our `surface`; `DuckShell` passes
`surfaceElevated` explicitly, or the sidebar would render on the page colour **only on phones**.

### Two Material alpha composites reach the screen, and both are exempt

The Phase 5 prerequisite forbids silence about a colour on screen that is in no palette. No component
here gains `enabled`, but two arrive anyway. Read from the `material3 1.4.0` bytecode — the version the
build resolves, confirmed with `dependencyInsight`, since the cache also holds a stale 1.3.0 AAR:

- **Drawer scrim.** `DrawerDefaults.getScrimColor` reads `ScrimTokens.ContainerColor` and applies
  `copy(alpha = 0.32f)`. **Exempt: occlusion is its function.** 1.4.3 governs text meant to be
  perceived; what must stay legible is the opaque sheet in front, whose pairs are all floored.
- **Ripple.** `RippleDefaults.RippleAlpha` is `RippleAlpha(0.16f, 0.10f, 0.08f, 0.10f)` —
  `material-ripple`'s field order is dragged, focused, hovered, pressed, so pressed is 0.10f — resolved
  against `LocalContentColor`. A pressed sidebar row is `onSurface` at 10% over `surfaceElevated`.
  **Exempt: transient feedback, not a state.** It says nothing the bar and the weight do not, and both
  of those survive the finger lifting.

Neither can live in `ContrastTest`: that suite compares two **opaque** colours, which is precisely the
property both lack. Recorded in `duckPairs`' KDoc and in `DuckShell`'s.

### State survives via `SavedStateHandle` only — no `rememberSaveable`

Every piece of shell state (filter, selection, expanded tag paths) lives in `SavedStateHandle`. This is
not belt-and-braces; it fixes a specific bug. On Compact the sidebar composes inside a
`ModalDrawerSheet` and on Expanded inside a `Row`, so **crossing 600dp changes the composition position
`rememberSaveable` keys on** — the saver finds no entry and the expanded set silently resets. A resize
would lose state that a rotation preserves, which is the confusing direction. A `SavedStateHandle` entry
has no composition key at all. `ShellStateRestorationTest` simulates process death by rebuilding the
ViewModel from a handle seeded with the first one's contents.

The editor takes a `String` and deliberately **not** a ViewModel: autosave, the note id and the save
path are Phase 8's Build list. The consequence is that edits die on rotation while the *selection*
survives, and its buffer is `remember`ed keyed on the text so selecting a different note replaces it —
an unkeyed `rememberTextFieldState` would ignore the new argument, and the failure would look like
"tapping a note does nothing".

### Two defects that 234 green unit tests did not catch

Both were found by installing the APK and looking at it, and both are recorded because the lesson is
the same one Phase 5 wrote down about the harness: **a check that cannot see the failure mode cannot
find it.**

1. **Crash on first launch: `Key "todo" was already used`.** The sidebar is one `LazyColumn` holding
   the fixed rows *and* the tag tree. The seed data contains a `#todo` note, so a tag row's `path` was
   `"todo"` — the same string as `ShellFilter.Todo.key`, and Compose throws on a duplicate key. Every
   unit test passed, because the collision lived in the composable's `items(key = …)` and not in the
   state. `ShellFilter.Tag` already namespaces with a `tag:` prefix; the sidebar simply was not using
   it. Fixed, and `ShellStateTest.noTagPathCanCollideWithAFixedRowKey` now asserts the property for
   adversarial paths rather than the one path that happened to be seeded.
2. **Content under the status bar, on wide screens only.** `Scaffold`'s `topBar` slot does *not* apply
   window insets to arbitrary content — only its own `TopAppBar` does — and Expanded/Medium lay panes
   out in a bare `Row` with no `Scaffold` and no `ModalDrawerSheet` to supply them. So the Compact
   sidebar button drew over the clock, and in Expanded the sidebar's first row did too. Compact was
   fixed with `statusBarsPadding()` on the button, Expanded and Medium with the same on their pane
   `Row`. **The Expanded case is only visible above 840dp**, which a phone-only check never reaches.

**Known ceiling, not fixed:** in Expanded and Medium the note list and sidebar can scroll their last
row under the gesture pill, because only the Compact path routes through a `Scaffold` that supplies
bottom insets. Cosmetic, and the fix is a `contentPadding` on both `LazyColumn`s that must not
double-count Compact's `Scaffold` padding — worth doing when Phase 8 adds the editor's bottom toolbar
and has to solve the same problem properly.

### The four gallery-only criteria, checked across palettes

These four cannot be asserted on the JVM and `MainActivity` has no theme switcher, so they were
checked on the gallery's Shell page. **DuckRed light alone is not a check** — Phase 5 CLOSED says so
about the disabled-checked case, and it applies here for the same reason: DuckRed light is the
palette *least* likely to expose a weak tint or a flat plane.

Checked in Compact (drawer open) and Expanded, across DuckRed light, Sepia light, Sepia dark and
Midnight dark:

1. **Active-row accent bar — passes everywhere.** Both the sidebar row and the note card show a bar
   plus a SemiBold label in every palette. **Sepia light is visibly the weakest** — a muted tan bar on
   cream — which is what `accent` sitting near its 3.0 floor looks like. The weight is doing real work
   there, which is the argument for two signals rather than one.
2. **Sidebar plane separation — passes on dark, effectively fails on Sepia light.** On Midnight dark
   the sidebar is obviously a different plane. On **SepiaLight the panes do not separate as planes at
   all**: the elevated fill contributes nothing and the 1px rule is what marks the boundary. Not a
   regression — `dividerAndElevationAreRecorded` already pins `surfaceElevated on surface` at **1.06
   in SepiaLight**, the lowest of eight, against 1.13–1.19 on the darks — but it does mean
   **`VerticalDivider` is load-bearing on the light palettes, not decorative.**

   The rule marks the boundary *precisely*, **not solely** — nav rows, cards and prose differ by
   content, and that second cue is what keeps `divider` exempt from a 1.4.11 floor. So "do not delete
   it" is a **Duck design rule, not a WCAG one**, and is labelled that way in `ContrastTest` beside
   the 1.2 tint floor: a rule that reads as conformance cannot be traded off, and a later phase may
   legitimately want to trade this one. If the pane rule ever needs more weight that is a **new slot,
   not a darker `divider`** — one hex would make every card separator heavy in all eight palettes.
3. **The `NavigationDrawerItem` ghost tint (build risk #2) — cannot occur.** The predicted failure was
   M3 defaulting `selectedContainerColor` to `secondaryContainer` → our `tagSurface`, at 1.04–1.16 on
   `surfaceElevated`. Rows are `Modifier.selectable`, so there is no `selectedContainerColor` to
   inherit. Confirmed by eye in the drawer in all four palettes: the selected row's background is
   **identical to its neighbours** — the bar and the weight are the entire difference. The risk was
   designed out rather than tuned down.
4. **Tag tree at three levels — passes.** `projects` › `duck` › `ui`/`sync` renders with correct depth
   indentation and chevrons, `personal` shows as the expandable-but-not-selectable intermediate, and
   both pinned tags appear in the pinned section with `personal/recipes` showing its full path.

Three harness defects were fixed to make this checkable at all, and each is the same lesson as Phase
5's missing checkbox state — **a harness that cannot show the state cannot verify it**:

- The fixture opened with **nothing selected**, so the active-row states the page exists to verify
  were invisible until someone thought to tap. It now opens with the first note selected.
- Gallery state was plain `remember`, so **every rotation reset it to DuckRed/light/Tokens** — and
  rotation is the only way to reach Expanded. Now `rememberSaveable`.
- The page chips **overflowed a portrait phone**, rendering "Shell" one letter per line.

### Verification

234 JVM unit tests, 21 instrumented on a Pixel 7 API 35 emulator, lint clean. Release APK dex-probed:
`DuckShellViewModel` / `DuckSidebar` / `DuckNoteCard` present, `ShellFixture` / `DuckGallery` /
`GalleryPage` absent. Checked on device in Compact (drawer, back, note selection) and Expanded (three
panes, active bar, editor rendering a real note body).

Both remaining `bear` strings in the tree were removed under the global contract — one was in
`SAMPLE_NOTE`, which **ships in the release APK**, and one in a `GoldenSpanTest` comment. The only
`bear` bytes left in release are `bearingAccuracy` from the `android.location` framework stubs.

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
  | 1 | one slot; **7 of 8 values are aliases** (`accentPress` ×4 dark, `chromeMuted` ×3 light). SepiaLight is the only palette needing a genuinely new value | 8 of 8 clear 1.2; direction right in 7 |
  | 2 | two slots; **all 16 values are aliases — zero new hexes**. `onAccent` = each palette's `surface`, except SepiaLight's `onSurface`; the fill as tier 1 with SepiaLight taking `divider` | 8 of 8 clear **and** recede; SepiaLight becomes the only theme with a dark tick |

  Verified: that tier-2 assignment satisfies distinguishability, tick-on-fill, tick-on-accent and
  direction in all eight palettes, with SepiaLight at 2.6743 / 9.6541 / 3.6099.

  **Tier 0 is the current decision, and the reason is no caller — not cost.** Nothing disables a checked
  box, and Phase 12 may render a locked note without checkboxes at all. Do not re-defer this on price:
  the slots are cheap and mostly aliasing, and a future reader talking themselves out of it because
  "sixteen values" sounds expensive would be deferring on a number that was never real.

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
2. **Add *every* enabled/disabled pair to `distinguishability`, in the same change.** This is the
   assertion that carries the real requirement, and the one that catches what the alpha check cannot:
   two colours that both pass against their background and are identical to each other. Note the failure
   direction — the 0.12 composites make the chip *too faint*, while the defects actually found in 5b made
   controls *not faint enough*. Assert the pair, do not reason about which risk applies.

   **This is an enumeration duty, the same one the floor table earned in 5b.** Enumerate the component's
   painted elements first, then add a pair for each, then build. For `DuckTagChip` that is four —
   label, leading icon, border and selected fill — not one representative pair. Move
   `theDistinguishabilityListIsNotSilentlyEmpty`'s pinned count in the same commit.

   The pin stops silent *deletion*. Nothing stops silent *non-addition*, which is how the
   disabled-unchecked checkbox reached `src/main`: every pair that existed passed, and the missing one
   was missing. A list of hand-picked entries is only as good as the last person's memory, so the duty
   is written here rather than left to it.

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

**Success criteria, as verified**

Recorded here rather than in a planning file. The list previously lived only in a session plan
outside the repo and was lost to an overwrite; a criterion that is not under version control is a
criterion that cannot be checked against later.

*Unit-testable (JVM)*

1. `TagScanner.rename` turns `#work` → `#job`, `#work/x` → `#job/x`, and leaves `#working` alone.
2. It preserves `#multi word tag#`'s closed form, and forces closed form when the new name has a space.
3. It does not touch `#work` inside a code span or a fenced block.
4. Two tags on one line both rewrite correctly (offset invalidation).
5. `isValidName` rejects `""`, `"123"`, `"Work"`, `"a/"`, `"a#b"`; accepts `"a"`, `"a/b"`, `"multi word"`.
6. `TagScanner.remove` strips the tag and leaves no double space or trailing space.
7. Prefix filter: filtering `projects` returns a note tagged only `projects/duck/ui`; `project` returns nothing.
8. Counts include descendants; an intermediate reports its subtree total; a note carrying both a parent and a child counts **once** under the parent.
9. Delete does not cascade — deleting `projects` leaves `projects/duck` intact and its notes tagged.
10. Rename onto an existing name merges; the target ends up holding both note sets.
11. Undo restores every body byte-for-byte and returns the tree to its pre-operation shape — for **both** rename and delete.
12. Pin and icon survive a rename; on a merge, the target's own values win.
13. Undo after renaming a pinned tag leaves no orphan — the `createdNames` sweep.
14. Undo after a merge keeps the merge target's own notes, because the sweep is conditional on being orphaned.
15. Deleting a pinned tag actually removes the row — `deleteOrphanTags` spares pinned rows, so this fails without the explicit `deleteByName`.
16. Delete restores the row *with its pin and icon* on undo.
17. `AddTagToNote` appends on its own line at EOF; an empty body gains no leading newline; trailing blank lines gain no ragged gap.
18. `AddTagToNote` is a no-op when the tag is already present, **exact-name** — adding `#projects` to a note carrying `#projects/duck` is *not* a no-op.
19. `AddTagToNote` **reports failure** when the body ends in an unclosed fence, rather than writing a tag `ParseTags` cannot see.
20. `ContrastTest` green with `duckPairs` at 23 and `distinguishability` at 3, both pinned counts moved.
21. Icon inheritance: a child with no `iconKey` draws its nearest ancestor's; its own key wins.

*Instrumented (device — SQLite semantics a fake cannot prove)*

22. Rename across **200 notes** in one transaction; all 200 bodies rewritten, one row `job`, none `work`.
23. A forced failure mid-rename leaves **0** notes changed and both tag rows as they were.
24. The same for delete: a forced failure mid-delete leaves **0** bodies changed and the row intact.
25. Rename covers a **trashed** note carrying the tag; so does delete.
26. `notesByTagPath` under 30 ms at 5000 notes — the criterion policing the deliberate trade that
    `substr(t.name, 1, n)` is a function on the column and **cannot use the `tags.name` index**.

*Visual-check-only*

27. Sidebar counts legible **and aligned** in all eight palettes.
28. The context menu separates from the sidebar behind it. Both are `surfaceElevated`, so only the
    1dp `chromeMuted` border and the shadow do this — **SepiaLight is the case**.
29. The disabled confirm button reads as disabled — **DuckRedDark is the case**, at 1.288, the
    thinnest row in `distinguishability`.
30. The delete dialog's destructive label reads as destructive — `error` on `surfaceElevated`, the
    phase's one new pair, 5.74 at worst.
31. The icon picker's selected cell is carried by its **border**; the fill is a hint at best.
    `tagSurface on surfaceElevated` measures **1.036–1.157**, pinned by
    `componentFillsOnElevatedContainersAreRecorded` and below the 1.2 this project calls visibly
    distinct. *Reworded after the sweep:* it previously read "border plus fill, not fill alone",
    which claimed two signals when the measurement already in the repo said one of them is
    invisible. On device in DuckRed light the fill is not perceptible at all and the accent border
    does the whole job. The eyes and the number agreed; only the criterion disagreed.
32. The chip row above the editor does not crowd the body at Compact width.

**Unspecified edge — renaming a pinned tag no note carries**

It is **deleted, not moved**, and nothing specified that.

`rename` rewrites bodies, and `carryCustomization` moves a pin onto *the row those bodies created*.
With no note carrying `#unused`, no `renamed` row is ever minted, so
`tags.getByName(newName) ?: return@forEach` carries nothing and `deleteByName` then drops the
original. Such a row can only exist in the first place because `deleteOrphanTags` deliberately spares
pinned ones.

Two candidate answers, neither chosen here:

- **Delete it.** Bodies are the source of truth, so a tag no body carries is not really a tag, and
  renaming a thing with no textual existence is ill-defined.
- **Mint the row so the pin survives.** The user pinned it deliberately, and a pin vanishing on a
  rename is surprising.

**Undo restores it either way** — the snapshot carries the old row with its pin.
`rename_ofAPinnedTagNoNoteCarriesDeletesIt` asserts what the code does, not what anyone specified.
An earlier version of that test asserted the opposite from imagination and failed on device.

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

*Split into **8a** (autosave + the saver drop — shipped, below), **8b** (format toolbar, undo/redo
buttons, task toggling, section folding) and **8c** (images, paste, focus mode, RTL). 4a/4b and 5a/5b
precedent. Undo/redo moved from 8a to 8b: `TextFieldState.undoState` already works and there is
nothing to build until a toolbar exposes it — what 8a owed it was the `clearHistory()` rule, below.*

---

## PHASE 8a — AUTOSAVE + SAVER DROP

**Goal:** The open note's text is durable, and the two writers cannot corrupt each other.

### The success bar was wrong, and is amended

*"Kill the app mid-typing — no data loss"* cannot be met by a 400ms debounce, and not for the reason
the phase note assumed. **400ms is an *idle* window, not a loss bound.** A debounce fires when typing
*stops*; a continuous typist never stops, so it never fires and **the unsaved window is unbounded** —
60 seconds of steady typing is 60 seconds at risk. `DuckShellAutosaveTest.
debounceAloneNeverFiresDuringContinuousTyping` drives a bare `debounce(400)` over five seconds of
keystrokes and asserts it saves **nothing**. That is evidence, not argument.

So autosave is **400ms idle OR 2s since the first unsaved keystroke**, and the bar now reads:

> **No data loss on any orderly departure** — home, recents, screen off, task switch, system reclaim;
> `ON_STOP` flushes. **≤ 2 s of typing** on an abnormal kill — crash, `am force-stop`, kernel OOM
> while foreground — where no `ON_STOP` fires. Nothing short of a Room write per keystroke recovers
> that residual, and it is stated rather than hidden.

### Three implementation decisions that were forced, not chosen

**1. `cappedDebounce` is hand-rolled, and `merge(debounce, sample)` was rejected twice over.**
`sample` schedules a *perpetual* ticker, so the scheduler never goes idle — under
`kotlinx-coroutines-test` that hangs `advanceUntilIdle()` forever, which is most of the shell's
existing suite. It was not a hypothesis: the whole suite hung until the operator was rewritten. And
`sample` ticks on a fixed grid from subscription, so the ceiling it appears to enforce silently
resets. The replacement parks on `receive()` between saves, so nothing is scheduled while idle.

**2. The autosave loop keys on `loadedId`, not `selectedId`.** They look equivalent and are not:
keyed on the selection, the loop starts the instant a row is tapped, observes the buffer still
holding the *previous* note (or nothing at all on a fresh ViewModel), and writes that. The empty body
becomes `lastWritten`, every later external change then reads as "buffer is dirty", and adoption
stops happening entirely. **Six tests failed on that one cascade**, including a selection restored
onto a hard-deleted note re-creating it from an empty buffer.

**3. `save()` takes no text — it reads the buffer under the lock.** The emission that triggers a save
is a signal, never a payload. `adopt` and `rewriteBuffer` mutate the buffer without anything calling
`Snapshot.sendApplyNotifications`, so `snapshotFlow` has not re-emitted and the value still in flight
is the text from *before* the rewrite. Trusting it reverted a rename on the very next debounce — the
buffer correct, Room wrong, three writes where two belonged.

### The two writers — adopt-when-clean was insufficient

Autosave writes the body; `TagRepositoryImpl`'s rename and delete rewrite the same bodies. Phase 7
assumed a single writer and said "revisit when autosave lands".

Without a fix: rename reads the note's *last saved* body, commits, and 400ms later autosave writes
the stale buffer — `saveNoteWithTags` re-derives tags and **`ensureTag` mints the row the transaction
just deleted.** Last-writer-wins, and the winner is the stale one, on exactly the note the user was
looking at.

**Adopt-when-clean does not close it.** It blocks the adoption but leaves `#work` in a dirty buffer,
and the next save resurrects the tag anyway — the identical failure, one debounce later. So the
buffer is **rewritten, never discarded**, through the same `TagScanner` the transaction used:

| intent | dirty-buffer action |
|---|---|
| `onRenameTag` | `TagScanner.rename` on the live buffer |
| `onDeleteTag` | `TagScanner.remove` on the live buffer |
| `onAddTagToNote` | **flush only.** The append rule lives inside `AddTagToNote`; re-applying it here would be a second implementation of tag syntax. It survives by a *chain* — flush leaves the buffer clean, adoption picks the appended body up — not a mechanism. Pinned, `ponytail:` at the call site |
| `onUndo` | **no flush**, then adopts unconditionally |

**`onUndo` is the one intent that must not flush**, and it did until review caught it. The other three
flush so the transaction reads current text; undo's contract is the opposite — *discard what happened
since the snapshot*. Flushing first wrote the user's typing to Room and then restored the snapshot
straight over it, destroying the typing **on disk** rather than only in the buffer, and restamping
`modifiedAt` on the way past for a write that lived two statements. The typing is still lost from the
buffer, because adoption is unconditional and that is undo working; what changed is that Room is never
polluted with a body the user asked to revert.

`lastWritten` gets the same transform, because Room's copy underwent exactly it: a clean buffer stays
clean, a dirty one stays dirty by the user's own edits.

**Optimistic concurrency was not available.** `modifiedAt` cannot be the version — Phase 7
deliberately does not restamp it on a rename, so it does not move. `remoteRev` is Phase 13's.

### `modifiedAt`, unchanged both ways

`SaveNote` restamps (an edit is an edit); `TagRepositoryImpl.writeBody` still does not (a rename is
not). Visible consequence: the open note jumps to the top of the list once, then cannot move again —
it is already newest. Opening a note restamps **nothing**, which needed the gate set *before* the
buffer is written or the load writes itself straight back.

### What the saver drop bought — and the guard that could not see it

`MarkdownEditorScreen` holds nothing saveable. The `Bundle` carries filter, note id and expanded tag
paths — three strings — and restore is "read the id, re-query Room". The 600dp resize that silently
dropped the buffer under `rememberSaveable` is **gone, not moved**: a ViewModel field has no
composition key. `rememberTextFieldState` survives only in `src/debug`, where the note is ~700 bytes
and the gallery is dex-probed absent from release.

**The first two guards for this were unsound, and it took an experiment to see it.** Both inspected
`SavedStateHandle` for an oversized value. **`rememberSaveable` never goes near `SavedStateHandle`** —
it writes to the composition's `SaveableStateRegistry`, which the *Activity* collects into
`onSaveInstanceState`. Both land in the same `Bundle` and contend for the same 1MB Binder buffer,
which is why the drop matters, but reading one says nothing about the other. Proven rather than
argued: the saver was temporarily reintroduced into `MarkdownEditorScreen` and **both guards stayed
green**. Neither composes anything, so neither could ever have observed a saver.

`EditorSaverSourceTest` replaces them — a source scan in `TokenSourceTest`'s idiom, banning both
`TextFieldState.Saver` and `rememberTextFieldState` (which *is* that saver, and reads as innocuous)
in `presentation/markdown` and `presentation/shell`. It was verified by the same experiment in
reverse: reintroduce the saver, and it goes red. Two properties it needs and the first draft lacked —
it strips comments before matching, or the KDoc explaining the removal is itself flagged as the
defect; and it proves the scan resolved, since a scan with a wrong path finds nothing and reports
green. **This is the third time in this project a green test turned out to be looking at nothing** —
after Phase 5's missing checkbox state and Phase 6's `[[WikiLink]]` corpus gap.

The preview now uses `remember { TextFieldState(...) }` rather than `rememberTextFieldState`, so
`src/main` has no saveable buffer at all — an invariant a scan can check, rather than an exception it
has to carve out.

### Painted pairs — none

The editor pane paints three, all pre-existing and already floored: `onSurface on surface`,
`accent on surface` (cursor — a fill, 3.0), `onSurface on selection`. No component gained `enabled`.
**`duckPairs` stays 23, `distinguishability` stays 3.** No save indicator: a new pair bought with a
feature nobody asked for.

### Verification

**328 JVM unit tests, 0 failures** (299 at `4a16ca7` + 29). **37 instrumented, 0 failures** on a
Pixel 7 API 35 emulator — 31 pre-existing plus 6 new, run through
`adb shell am instrument`, never `connectedAndroidTest`. Lint clean: 32 warnings, all dependency
staleness, 0 errors.

The six new instrumented tests are the ones a fake cannot speak to: process-death survival, `ON_STOP`
flushing with no debounce elapsed, the ceiling firing **within `MAX_WAIT_MS` of the first keystroke**
on a real clock, the `ensureTag` resurrection for both rename and delete, and a 10k-word note loading
with only an id in the handle. **The resurrection pair is the phase's central claim** — no fake has an
`ensureTag`, so nowhere else can observe a deleted `tags` row being minted again by a stale buffer.

**`FakeTagRepository` now rewrites bodies when passed a note repository.** 8a's subject is two writers
racing over one body, and with bodies frozen the shell correctly adopts Room's stale copy — the fake's
artefact, not a defect. Still not a reimplementation: the rewriting goes through `TagScanner`. What it
omits is the transaction, which is what a JVM test cannot speak to.

**The instrumented suite was reported blocked on "no device" when the emulator was running.** `adb` is
not on `PATH` on this machine, and a bare `adb devices` answers *"command not found"* — which reads
exactly like an empty device list if skimmed. **An absent binary and an absent emulator produce the
same conclusion.** Both absolute paths are now in `SETUP.md` beside the Gradle one, with the
`emulator -avd` "multiple emulators" error flagged as meaning *already running*, not *failed*.

**OPEN — "10k-word note scrolls at 60fps" is not evidenced and 8a does not claim it.** No JVM
benchmark line was added, because none is honest: `ScannerBenchmarkTest` is pure JVM over
`MarkdownTextCache`, and measuring the autosave observation needs `TextFieldState`, an Android-only
artifact. Timing a bare 60KB `toString()` would measure the JDK. `BasicTextField` does not virtualise
and layout is O(document), so at 10k words layout — not scanning, not autosave — is the likely
villain. Needs a device trace; 8b or Phase 16 owns it.

**Carried from Phase 6 — drop the editor's saver when autosave lands**

`MarkdownEditorScreen` holds its buffer in `rememberSaveable(text, saver = TextFieldState.Saver)`.
That is `rememberTextFieldState` expanded by hand, and it is right for Phase 6: plain `remember`
silently loses what was typed on a rotation, which is a regression Phase 4 did not have.

But the saver serialises the **whole note body** into the saved-instance `Bundle`, so every open note
round-trips a Binder transaction — a **1MB per-process buffer already shared with
`DuckShellViewModel`'s `SavedStateHandle`** — and `TransactionTooLargeException` lands well below the
nominal limit. This phase's own 10k-word note target is in range.

Once autosave (400ms debounce, above) puts the body in Room, the `Bundle` only needs the **note id**.
**Drop the saver then rather than inheriting it:** restore becomes "read the id, re-query Room", which
is also what makes *"kill the app mid-typing — no data loss"* true for real rather than true up to a
`Bundle` size.

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
