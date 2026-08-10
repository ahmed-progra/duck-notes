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
- Format toolbar above keyboard, **nine buttons**: bold, italic, code, highlight, heading, list,
  quote, checkbox, link. `table`, `image` and `more` move to **8d**, where images and paste already
  live — a table needs a grid the editor cannot draw yet, and an image button with no picker behind it
  is a button that does nothing. `code` and `quote` were not on the original list and are here because
  the scanner already produces both and the toolbar cost is one entry each.
- Tasks: `- [ ]` / `- [x]`, tap toggles, thin progress line under note card in list.
- Section folding by heading. **Fold state is session-scoped, not persistent, and the original
  wording of this line ("Persist fold state per note") promised something `SavedStateHandle` does not
  deliver.** It survives rotation, resize and process death, because that is what the handle is for.
  It does **not** survive a cold launch, and only the open note's folds are kept — switching notes
  evicts the previous note's, which is what stops the saved-instance `Bundle` growing without bound.
  Durable folds would be a Room column, and that belongs to a phase rather than to this line.
- Images: pick, insert, Coil render inline, tap to zoom, resize, delete.
- Paste handling: URL → link, image → attachment, markdown → raw.
- Undo/redo stack. **Pulled out of the toolbar phase and left unscheduled**, correcting the note
  below. `TextFieldState.undoState` gives buttons for free, so bolting two `DuckIconButton`s onto the
  format bar looks like the whole job — and is not. Everything hard about undo in Duck is *outside*
  the editor: `adopt` and `rewriteBuffer` both call `clearHistory()` because a tag rename is not the
  user's edit to walk back through, `BodySnapshot` is a separate transient stack for exactly that
  reason, and 8a's own note calls a durable undo "an operation log or nothing". That is a design
  question, not a toolbar row.
- Focus mode: hide chrome, dim non-current paragraph.
- RTL support (Arabic). Bidi-safe markdown rendering.

**Success:** Kill the app mid-typing — no data loss. 10k-word note scrolls at 60fps.

*Split into **8a** (autosave + the saver drop), **8b** (motion), **8c** (format toolbar, task
toggling, section folding) and **8d** (images, paste, focus mode, RTL). 4a/4b and 5a/5b precedent.*

***This paragraph said something different until 8c, and the stale version is worth recording rather
than quietly replacing.*** *It assigned the toolbar to 8b and images to 8c, which stopped being true
the moment 8b shipped as motion — the renumbering was written down in the 8b section (below) and never
propagated here. So the two definitions of "8c" in this document disagreed for a whole phase, and the
first one a reader hits is the wrong one. Nothing broke, because nobody read it; that is the point.
**A phase split lives in exactly one place, and this is it.***

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

## PHASE 8a-fix — THE IN-PROGRESS TAG

8a made the editor durable and, in doing so, put partially typed text in front of the tag parser for
the first time. Typing `#work` one character at a time minted `w`, `wo` and `wor` as real `tags` rows.

### The mechanism, because it is not where anyone looked

The report read as self-contradictory: the sidebar showed the partials, Database Inspector showed a
clean table with no orphans. Both were true. **The table holds exactly one row at every step and never
accumulates** — each partial loses its only reference on the next save and `deleteOrphanTags` reaps it
in the same transaction. The sidebar was faithfully rendering a *sequence* of momentarily-correct
states.

So the fix is **not** in the reaping (correct), **not** in the sidebar (it reads `TagDao.observeAll`
and nothing else), and **not** in the scanner. `#w` is a *complete, valid tag* to `TagScanner`;
nothing about it is "in progress" from the parser's point of view. **What the parser lacked was the
caret.** A span whose `end` is exactly the caret may still grow by one more character.

**A settle timer was rejected on the evidence.** It separates mid-word from finished by *duration*,
and the defect was reported by typing one character at a time with long pauses — any window would
still mint the partial. The caret separates them categorically. Recorded so it is not re-proposed.

### The rule

1. **Autosave** derives tags with the span ending at the caret dropped (`ParseTags(body, caret)`).
2. **Flush** — `ON_STOP`, note switch, before any body-rewriting intent — drops nothing.
3. **A suppressed write owes a full one, and paying it is not an edit.** Completing `#work` changes no
   character, so the `lastWritten` gate would swallow the write that mints it; `oweFullWrite` lets it
   through, and that write passes `restamp = false`. One restamp per edit — the same rule Phase 7
   applies when a rename declines to restamp.

`TagScanner` is untouched. The suppression *filters spans it produced*; a second tag parser would be
the bug this avoids.

**Cost, stated:** a tag typed and left with the caret parked on it does not reach the sidebar until
the next text change or a flush. A bare caret move is not enough — `snapshotFlow { buffer.text }`
observes text only. The editor still paints it accent-red throughout.

### Two things this got wrong before it got them right

**`oweFullWrite` was reset in `adopt`, which killed the debt it existed to carry.** After the
suppressed write Room re-emits the note, `syncBuffer` takes the same-note-clean branch and adopts our
own text — clearing the flag before the flush could pay it, so the tag never appeared at all. Only a
*different* note retires the debt: `adopt(body, keepTagDebt = true)` on the same-note path.

**And it outlives `loadedId` if nothing resets it.** Reachable through `onFilter`, which clears the
selection without flushing — `onSelectNote` flushes first and pays the debt on the way out, which is
exactly why the leak is invisible from the obvious path. Same cascade shape as keying the autosave
loop on `selectedId`. `syncBuffer` now holds `writeLock`, so the flag, `lastWritten`, the buffer and
`loadedId` move as one.

### Verification

**Every new check was inverted and observed to fail** — the discipline `EditorSaverSourceTest` was
written to enforce after three guards shipped that could not fail:

| inversion | what failed |
|---|---|
| collector passes `suppressInProgressTag = false` | 6 tests, including the repro |
| `keepTagDebt = true` on the note-change path | `anOwedTagWriteDoesNotFollowTheUserToTheNextNote`, alone |
| `restamp = true` unconditionally | `mintingTheTagOnFlushDoesNotRestampModifiedAt`, alone |

`PartialTagMintingTest` was **inverted, not extended** — it pinned the defect, and a pinning test that
survives its own fix is measuring nothing. Its KDoc keeps the mechanism.

**348 JVM unit tests** (330 at `3127589` + 18), **39 instrumented** (38 + 1) on a Pixel 7 API 35
emulator, lint **0 errors**. The JVM baseline was re-measured by running: the first `testDebugUnitTest`
came back `UP-TO-DATE` and produced no number at all, which is its own trap.

**Known ceiling — the closed form still leaks its first word.** Typing `#multi word tag#`, at the
instant the body reads `#multi word` with the caret at 11, `scan` returns `TagSpan(0, 6, "multi")` —
the open form ends at the space, `6 != 11`, so `multi` is minted until the closing `#` lands.
(`#multi` with the caret at 6 *is* suppressed; the space opens the leak.) Closing it needs the scanner
to report an unterminated closed-form candidate — a change to a frozen module for the rarer form.
Pinned by `anUnterminatedMultiWordTagStillMintsItsFirstWord`.

---

## PHASE 8b — MOTION

**Renumbered from a proposed 5c.** A `5c` landing after `8a-fix` makes the log unreadable; the format
toolbar becomes **8c** and images/paste/focus/RTL become **8d**. No committed phase changes name.

Motion was not weak in Duck, it was **absent**: zero `androidx.compose.animation` imports in
`src/main`, and no phase in this document had ever specified it.

### Step 0 — the baseline, taken before adding per-frame work

"10k-word note scrolls at 60fps" has been open since 8a, and motion adds per-frame cost, so a number
had to exist first or a later regression would be unattributable. A 10,004-word note was seeded
through the real Room database and scrolled with twelve identical swipes, **against a 303-word control
on the same code path**:

| | 303 words | 10,004 words |
|---|---|---|
| janky | 3 (1.14%) | 22 (6.83%) |
| 50th / 90th / 95th / 99th | 17 / 17 / 17 / 18 ms | 17 / 19 / 22 / 32 ms |

**The median is identical at 33× the length**, so 17ms is the emulator's floor, not the document's
cost. The document appears only in the tail. **This is an attribution baseline and nothing more — the
60fps claim stays OPEN**, because a host GPU flooring every document at 17ms cannot answer it.

Three traps recorded rather than smoothed over: swiping the 4-item list rendered **0 frames** and
reported `0.00%` jank, which reads exactly like a perfect score; jank varies ~2 points run to run, so
a post-motion delta smaller than that means nothing; and the swipes were verified to *actually scroll*
by screenshot (2,178 words, 21.6% of the document, no selection highlight) because a `uiautomator`
dump returns the field's whole text and cannot show scroll position.

### The frequency test — four animate, four refuse

Animation frequency decides whether to animate at all, and most of what Duck does is high-frequency.

| animate | refuse |
|---|---|
| Compact pane change (spatial continuity) | press feedback — Material ripple already owns it |
| tag tree expand / collapse | `DropdownMenu` / `AlertDialog` — M3 animates them already |
| note list insert / reorder / remove | the editor, on any keystroke path |
| the two active bars, 120ms | **the chevron** — see below |

**The chevron was in the plan and was dropped on reading the code**, which carries: *"Two distinct
glyphs, not one rotated: the states have to differ in shape, not only in orientation."* Rotating it is
exactly what that decision forbids. Planned motion lost to a recorded decision.

The tree and the list **collapse into one mechanism**: expanding a tag *is* an item insertion, so
`Modifier.animateItem()` serves both. No `AnimatedVisibility`, no second mechanism to keep in step.

### Reduced motion — measured, and one claim was wrong

Measured on API 35, toggling *Accessibility → Remove animations*:

| state | `animator` | `transition` | `window` |
|---|---|---|---|
| fresh, untouched | **null** | 1.0 | 1.0 |
| Remove animations on | 0.0 | 0.0 | 0.0 |
| **battery saver on** | **1.0** | 1.0 | 1.0 |

**Battery saver does not disable animation.** The plan asserted it did; that was pre-Android-9 policy.
And the key is **absent** on a fresh device, so `Settings.Global.getFloat(cr, key)` throws there — the
defaulting overload is mandatory, and the original design would have shipped that crash.

**`ValueAnimator.areAnimatorsEnabled()` is OR'd in, and does not close the battery-saver gap.** Its
javadoc claims it does — *"or by Battery Savery mode being enabled"*, AOSP's own typo — and the
implementation path supports the idea: it reads `sDurationScale`, written only from
`WindowManagerGlobal` off `IWindowManager.getCurrentAnimatorScale()`, i.e. WMS's *effective* scale
rather than anything persisted. **But on API 35 it measurably does not fire**: `powerSaveMode=true`,
`areAnimatorsEnabled()` still `true`.

That measurement only counts because of its positive control. **The first probe returned `true`
unconditionally, even at `animator_duration_scale=0`** — `sDurationScale` is populated only in a
process that owns a window, and a bare instrumentation process never receives `onAnimatorScaleChanged`.
Adding a compose rule made it report `false` at scale 0 and still `true` under battery saver. Without
the control the run would have been read as a real result for the wrong reason.

So: **the call is kept** (one static read, correct wherever the platform does lower the scale) and the
`ACTION_POWER_SAVE_MODE_CHANGED` receiver written alongside it was **removed** — machinery for an event
measured not to affect us. **Duck's reduced motion covers the accessibility toggle; battery saver's
effect on Duck's own animations is a stated gap.**

**All three keys are read**, not just the animator scale. One more emulator would still leave eight API
levels unverified; treating any zero as reduce-motion makes the question moot at all of them. Accepted
cost: zeroing only `window_animation_scale` in Developer Options also stops in-app motion.

**Observed, not read once** — a `ContentObserver` inside a `DisposableEffect`, backed by
`mutableStateOf`, re-read after registration to close the register-gap, unregistered in `onDispose`.
Verified live on device: five toggles plus navigation, same PID, clean logcat.

### Contrast during a transition

**The active bars animate `scaleY` at full opacity, not alpha.** Fading would paint `accent` below the
3:1 a 1.4.11 graphic owes for the whole 120ms, on the most-tapped element in the app. Growing from the
centre keeps every painted frame at full `accent`, so **the ratio is invariant and no exemption is
needed** for it.

The exemption is still written down, in `ContrastTest`'s KDoc, because the pane change genuinely does
crossfade text: **`duckPairs` enumerates steady states a user can read, not in-flight frames.**
Anything reachable at rest stays enumerated and floored. An unstated exemption is indistinguishable
from an oversight.

### Guards, and the test that was measuring nothing

`TokenSourceTest` now bans `tween(`, `spring(`, `snap(`, `keyframes(`, `CubicBezierEasing(` and
Compose's stock easings outside `core/theme/`. **Constructors rather than millisecond literals** — a
duration can only reach an animation through one of them, so the rule is complete rather than
heuristic. The regex handles `tween<Float>(`, the form you reach for when inference fails. Verified by
planting a real offender in `DuckNoteCard` and watching `mainSourceGoesThroughTheTokenLayer` go red.

**`PaneTransitionTest` never reads `Settings`.** On a device with animator scale 0 every duration
collapses and an assertion about mid-transition state measures nothing — the same shape as the saver
guards and the `UP-TO-DATE` run. It provides `LocalDuckMotion` directly.

**And the first version of it was broken in exactly that way.** The provider was wrapped *around*
`DuckTheme`, which provides `LocalDuckMotion` itself — so the theme overwrote it and the reduced-motion
case ran at full motion. It surfaced as a failure only because the reduced case asserted the opposite
of the case above it. Moving the provider **inside** `DuckTheme` fixed it. Then inverted: forcing
`DuckMotion.none` into the spec turned the animation assertion red with its own message.

### Three specs, because three different jobs — and the platform default was better than the token

`animateItem`'s defaults in foundation 1.9.4 are **all springs**, `spring(stiffness = 400f)`, verified
in the bytecode rather than recalled. The first draft of this phase passed tweens for all three and
**silently replaced them** — on the one surface that is routinely interrupted. The note list reorders
on every save (`SaveNote` restamps, query is newest-first), so a row is often still moving when the
next reorder lands; a tween restarts from zero there and stutters, a spring carries its velocity.

So the three specs split by job, not by one shared value:

| spec | what | why |
|---|---|---|
| `mediumSpec()` 200ms `easeOut` | arriving | decelerating; the user is waiting on it |
| `placementSpec()` **spring** | moving | interrupted constantly; must not restart |
| `exitSpec()` 120ms `easeExit` | leaving | shorter, accelerating; clear dead state |

`easeExit` is a third curve, and the one place an accelerating shape is right: nobody waits on an exit,
and lingering keeps stale content over the state being read. It is **not** the `easeIn` deliberately
absent from entrances — same shape, opposite job. The pinned easing count moved 2 → 3 deliberately.

**The token layer names the platform's good default; it does not overwrite it.**

### Reduced motion is not the same as zero duration

Measured frame by frame, `AnimatedContent` with every duration at zero **still held both panes
composed for two frames** — `0ms L1/E0, 16ms L1/E1, 32ms L1/E1, 48ms L0/E1`. Invisible, since a
zero-length fade sits at alpha 0, but it composes and lays out both panes; on a 10k-word note that is
the editor's entire layout run beside the list's, for nothing.

The test in place at the time asserted at **64ms and passed**, which is how it was missed. Reduced
motion now **does not enter `AnimatedContent` at all** (`DuckMotion.animates`), and the assertion moved
to the first frame — `0ms L1/E0, 16ms L0/E1`, no overlap.

### Verification

**364 JVM** (348 at `e9cf2e6` + 16), **41 instrumented** (39 + 2), lint **0 errors**. No new gallery
page: `ShellPage` already exercises three of the four surfaces interactively, and a motion page would
have re-staged it.

**Every new guard was inverted — planted a violation, watched it go red, reverted.** Each produced
exactly one failure, and the named one:

| guard | planted | failure |
|---|---|---|
| whole-tree motion ban | a **new file** in `presentation/shell` holding `tween(300, easing = FastOutSlowInEasing)` | `mainSourceGoesThroughTheTokenLayer` — `[TempMotionOffender.kt: tween(, TempMotionOffender.kt: FastOutSlowInEasing]` |
| active-bar symmetry | `activeBarSpec` returning `fastSpec()` for both directions | `theActiveBarLeavesAndArrivesOnDifferentCurvesAtTheSameLength` |
| motion spec slot count | an eighth spec accessor on `DuckMotion` | `theMotionSpecSlotCountIsPinned` |

The first one is deliberately a **new file** rather than a line added to an existing one: that proves
the tree walk picks up sources it has never seen, which is the failure mode a scan actually has. The
in-tree `detectorFlagsPlantedOffenders` case only proves the regex fires on a string.

### The active bar's other half — Option B

The first draft specified the bar *arriving* and left the bar *departing* on whatever spec happened
to be there. Selection moves tens of times a day, so the unspecified half ran as often as the
specified one. `DuckMotion.activeBarSpec(selected)` now owns both: `easeOut` in, `easeExit` out, both
at `fast` so the crossover is one exchange rather than a stagger.

**The alternative is deferred, not rejected.** Two unrelated elements animating oppositely is not the
same as one indicator *translating* between rows, which is what the motion depicts. That needs a
single bar in the list's coordinate space driven by item offsets — an overlay above the `LazyColumn`,
`layoutInfo` reads per scroll frame, and answers for three states that have none today (selection
scrolled off-screen, selection changing mid-scroll, selected row removed by a filter), on both
surfaces, interacting with `animateItem`'s own placement clock. Estimated 150–250 lines across 3–4
files plus 4–6 instrumented tests, and it puts per-frame layout reads on the list already suspected
in the unresolved 60fps question. **Judged on a device first; a future phase candidate if the
crossover reads wrong.**

**Neither screenshots nor screen recording can judge a transition on this emulator** — `screencap`
latency exceeds a 2s animation even at 10× scale, and `screenrecord` produces a one-frame file. Every
mid-transition claim here comes from clock-stepped instrumented tests; the screenshot sweep is
evidence about *steady states* only, and cannot stand in for them.

**Large font — done, and it is the steady state the sweep is good for.** `font_scale 2.0`, the
accessibility maximum, on API 35. The worry was specific: the active bar is `fillMaxHeight` against a
row whose height is driven by text, so looking right at 100% is not evidence about 200%. Measured
from the framebuffer against `uiautomator` bounds, not judged by eye:

| surface | row / card height | accent bar | bar width |
|---|---|---|---|
| sidebar row | 126px (48dp) | `y=74..199` — **126px** | 8px (3dp) |
| note card | 415px (158dp) | `y=649..1063` — **415px** | 8px (3dp) |

Exact on both, including a card grown to 158dp by wrapped text. The note list itself holds: rows
grow, the snippet still clamps to two lines, dividers and ellipsis intact, nothing clipped; the
editor renders with the accent cursor and handle. Two things the numbers show that eyes would not:
the sidebar row does **not** grow at 200% — its label is 31.6dp inside a 48dp minimum, so the
`heightIn(min = ROW_HEIGHT)` floor is still what sets it — and note-card pitch goes 220px → 415px,
so item insert/remove moves nearly twice as far per row at this setting. Both fall out of `scaleY`
against a parent height, which is why nothing needed changing.

---

## PHASE 8b-AUDIT-FIXES — what a read-only audit of the suite found

An audit ran every check in the suite against one question: *could this ever fail?* 405 checks,
all green at the time (364 JVM + 41 instrumented). It found one shipped data-loss defect, three
guards that could not do what they were cited for, and a handful of smaller gaps. Recorded here
because the **defect class** is the reusable part, not the individual fixes.

### The class: a check that has a positive control and still proves the wrong thing

Beyond "vacuous" and "unanchored" there is a third, worse shape — **misdirected**: a real assertion
*with* a real control, where what a red would prove is not what the check is cited to guarantee. It
is more dangerous than having no control, because it buys trust it has not earned. This codebase has
now produced it four times: the original saver guard (inspected `SavedStateHandle`, not
`SaveableStateRegistry`), the `areAnimatorsEnabled()` battery-saver claim (doc said it covered it,
measurement said otherwise), and the two below. **When a check presents a control, ask what the
control's red state actually proves, and whether that is the same proposition the check is cited
for.** A gap there is the finding.

- `TagRewriteTest`'s pinned-orphan rename asserted `snapshot.tags.single().isPinned` under a KDoc
  claiming *"undo restores it pinned"*. That proves the snapshot **carries** the row, not that
  `restore` reinstates it — and it did not. Fixed; see below.
- `TagAgreementTest.theEditorPaintsTheWikiLinksResolveWikiLinksFinds` was cited as holding
  `ResolveWikiLinks` and `MarkdownScanner` to agreement, and passed only because no corpus sample
  put a `[[…]]` inside code. Now pinned explicitly; see Phase 9's known defect.

**Falsifiability was proven by planting, not by argument** — a temporary offending edit in the real
tracked tree, run against the real Gradle task, then reverted. A scratch copy cannot test that a
whole-tree scan resolves the actual source root, which is precisely what such a scan gets wrong.

### The inverse class: **misnamed** — proves the right thing under the wrong label

**Misdirected** proves the wrong thing. **Misnamed** proves the right thing and hides that it does:
the check guards something real, but its name and KDoc describe a different property, so its
coverage is invisible to anyone deciding whether it can go.

It is not a documentation nit, and it fails in the one direction the vacuous family cannot. A
vacuous check rots quietly and costs nothing when deleted. A misnamed one is **load-bearing while
reading as redundant** — a cleanup pass removes it as a duplicate of the test whose name it appears
to echo, takes real coverage with it, and **the deletion looks correct in review**. Nothing goes
red, because the property it was silently holding has no other guard. That makes it the only defect
class in this list that gets *worse* on its own, with no code change: every rename, every tidy-up,
every "these two look the same" is another chance to lose it.

**Fix it by KDoc, never by rename.** A rename breaks the link from anything already citing the old
name — this document, a commit message, another test's comment — and trades an invisible coupling
for a dangling one. State the second property on the test that carries it, name the finding it
stands in for, and point back from wherever that finding is discussed. **A coupling has to be
readable from both ends or it is only half recorded.**

Two are pinned in the tree, found by the post-8c audit and closed in `docs: misnamed guards`:

- `GoldenSpanTest.theBoxIsPaintedAsItsOwnRange` reads as being about the box's *position*. Its
  assertion is an **ordered list**, so it also pins the emission order that makes
  `MarkdownStylesTest`'s `task.merge(box)` the production expression rather than a guess.
- `ConcealInvariantTest.renderedStylesLandOnTheSameCharacters` promises characters in its KDoc and
  asserts **bounds**. The character property is real and lives two tests below in
  `rebasedStyleCoversTheSameTextAsBefore`.

### Fixed

| what | where |
|---|---|
| **Renaming a pinned tag no note carries destroyed it, and undo could not recover it** — `carryCustomization` dropped the pin, `deleteByName` then destroyed the row, and `restore`'s `getByName` was null so the recovery branch never ran. Rename now **moves** the row (`upsert` is keyed on `id`); `restore` re-inserts a customized row no body re-created. Fixes the delete path's undo too, which was broken by the same line. | `TagRepositoryImpl.carryCustomization` / `.restore` |
| `bothEasingsStartAtZeroAndEndAtOne` **could not fail** — a cubic Bezier has fixed endpoints, so it held for any control points. Replaced with anchored **and monotonic**, over all three easings by reflection. | `MotionTest` |
| `stripComments` used a bare `//.*`, so a banned call after a URL on the same line vanished before detection. Proven by plant: the suite stayed green with a real `rememberTextFieldState` in `MarkdownEditorScreen`. Now uses `TokenSourceTest`'s string-first alternation. | `EditorSaverSourceTest` |
| 8b's animation-spec control planted 4 of 11 banned alternates. | `TokenSourceTest` |
| Five tests asserted only inside a loop over a possibly-empty collection. | `ConcealInvariantTest` ×3, `MarkdownTextCacheTest`, `TagAgreementTest` |

### Two slot rules to read from the code, not from shorthand

Both were audit false positives caused by a one-line summary being stricter or vaguer than the code.
Stated here so the next audit does not re-raise them.

- **`chromeMuted` does paint text, and that is correct.** The rule is not "never text" — it is
  `DuckColors.kt`'s own phrasing: *"Borders, outlines, and genuinely **disabled** controls. Never
  text, at any size."* The disabled carve-out is deliberate: WCAG 1.4.3 exempts inactive components,
  and `ContrastTest.distinguishability` tracks the pair as "rename confirm label". `TagMenu`'s two
  `disabledContentColor` uses are the intended callers. **No code defect.**
- **`DuckColors.selection` is currently painted by nothing**, and that is safe. Nothing provides
  `LocalTextSelectionColors`, so `MaterialTheme` supplies its default —
  `TextSelectionColors(primary, primary.copy(alpha = 0.4f))`, read from the material3 1.4.0 bytecode
  the build actually resolves — and `primary` is `accentText`. The painted pair is therefore
  `onSurface` over `accentText` at 40% on `surface`, measured **5.39–7.82 across all eight
  palettes**, so it clears 4.5 everywhere and there is no accessibility failure. Two `duckPairs`
  entries consequently measure a slot nothing paints. **Keep the slot and keep the count at 16** —
  wire it to `LocalTextSelectionColors` only if custom selection styling is ever wanted, at which
  point those two floors start doing work.

### Two counts the audit itself got wrong

Same spirit as the slot rules above, and for a sharper reason: the audit that produced finding 18 —
*stale prose counts* — quoted two counts of its own, and both were wrong.

| the audit said | measured |
|---|---|
| `TagRewriteTest:36` cites "29 cases" while `TagScannerRewriteTest` holds 26 | **29 is correct.** The citation was right and the audit was not; no edit was needed. |
| `AutosaveDurabilityTest:52` says "21 tests", "34 after this pass" | **37.** Both figures stale — the file's and the audit's. |

**A finding that quotes a number is doing the thing it objects to.** Count before acting on a cited
count; `grep -c '@Test'` is two seconds and it saved an unnecessary edit to a line that was already
right. The general form: an audit is a source like any other, and its numbers rot at the same rate as
the prose it is criticising.

Calibration: of 18 findings, two overstated, both toward *more* wrong than reality — the safe
direction for an audit to err and the expensive one for anyone acting on it unchecked.

### Carried from Phase 7 — merge undo leaves the target pinned. Unscheduled.

`carryCustomization` writes `isPinned = current.isPinned || old.isPinned` onto the destination row on
a merge; `restore` only walks `snapshot.tags`, which holds the **source** rows. `BodySnapshot` has no
record of the target's prior state, so undoing a merge cannot put it back and the target stays pinned.

Closing it needs a **new snapshot field** — the destination rows as they were — not a change to
either loop. **Cosmetic, not data-losing:** an extra row in the pinned section the user can unpin.
Found while checking whether the pinned-orphan `@Upsert` could collide with the `name` UNIQUE index
(it cannot — both name-changing writes are guarded by a `getByName` that just returned null, proven by
defeating the guard and watching `UNIQUE constraint failed: tags.name` appear).

Pinned by `TagRewriteTest.restore_ofAMergeLeavesTheTargetPinned`, which **asserts the defect** and
goes red the day it is fixed. Not scheduled to a phase.

### Still open after this pass

`ResolveWikiLinks`' missing code masking (Phase 9, below — now pinned rather than unnoticed) ·
merge undo's inherited pin (above, pinned, unscheduled) ·
release builds unminified, `isMinifyEnabled = false`, deferred to Phase 16 · **"10k-word note scrolls
at 60fps" remains unevidenced.** 8b's jank numbers are an attribution baseline and nothing more; a
host GPU flooring every document at 17ms cannot answer the question, and no benchmark in the repo
claims to. It needs a real-hardware trace.

---

## PHASE 8c — FORMAT TOOLBAR, TASK TOGGLING, SECTION FOLDING

**Goal:** the editor gains write affordances. Until now the only ways to change a body were typing in
it and `TagRepositoryImpl` rewriting it from the sidebar.

Three commits: **8c-a** toolbar, **8c-b** task toggling, **8c-c** folding. b and c both touch conceal
and are independent of each other; c needs no hit-test, so if 8c-b's Compose spike fails, b defers and
a/c still ship.

### 8c-a — the format toolbar

**Nine buttons, no toggled state.** A button inserts syntax; it does not report whether the caret is
already bold. That keeps every button to the two states `DuckIconButton` already paints and already
has a `ContrastTest` distinguishability row for — an active state would owe a third colour and a new
row. Add it when someone asks, and enumerate its pair in the same change.

**The toolbar declares no syntax.** Every literal it writes comes from `MarkdownScanner.markerFor` /
`prefixFor`, and `MarkdownFormatTest.everyMarkerScansBackAsItsOwnKind` feeds each one back through the
real parser rather than comparing it to a table — the round trip `TagScanner.isValidName` uses, in the
other syntax. `MarkdownSyntaxSourceTest` then scans `presentation/` and `core/` for markdown literals
and for `Regex(`, so the rule is enforced rather than remembered.

That guard exempts by **length, not by file**: `SAMPLE_NOTE` is a 500-character preview fixture, and
nobody implements markdown in a 500-character literal. A per-file allow-list would have had to name
`FormatToolbar.kt` — the one file the guard exists to watch.

**"Already formatted?" is answered by scanning, never by `startsWith`.** `- [x] done` is a task line,
and a `startsWith("- [ ] ")` check says it is not and prepends a second marker. Scanning also makes
removal exact: `##   Heading` loses its whole marker rather than a hardcoded prefix length.

**A toolbar press is an ordinary edit.** It goes through `TextFieldState.edit`, the same function the
keyboard drives, so `snapshotFlow { buffer.text }` emits and the 400ms debounce writes it. **No new
write path, no `flush()`, no lock, and no intent on `DuckShellViewModel`.** Routing it through
`rewriteBuffer` is impossible rather than discouraged — that function is `private` to the ViewModel,
so the compiler holds the line and no test has to. `DuckShellAutosaveTest` asserts the half a compiler
cannot: that the edit reaches Room, in exactly one save.

**Fences: the buttons go `enabled = false`, and there is no message path in 8c by design.**
`AddTagToNote`'s `NotWritable` Snackbar exists because *that* failure is invisible — the menu item
appears to do nothing. A disabled button is visible before the tap, so a message would explain a state
already on screen. Applying "outside the fence" was rejected: it splits one action into several edits,
so one undo entry becomes several, and a `**` written into a code sample is literal text.

`MarkdownFormat.isWritable` has two overloads and the difference is load-bearing. The toolbar's
enabled state recomputes on every keystroke, so it takes the editor's **incremental** `ScanResult`
rather than rescanning, and walks `lineTokens` rather than `tokens` — the latter is a `get()` that
flattens the whole note on every access.

**Theme: zero new pairs. `duckPairs` stays 23, `distinguishability` stays 3.** The bar is
`surfaceElevated` on `surface`, its glyphs are `onSurface` (4.5, floored) and `chromeMuted` when
disabled (3.0, floored), and it reuses `DuckIconButton` rather than introducing a second button type —
which is why it inherits the `"icon button glyph"` row instead of owing one.

**Motion: zero new slots.** The bar rides on `imePadding()`, so the keyboard's own animation owns the
timing; a spec here would fight it.

### 8c-b — task toggling

**The blocker, and why the wording mattered.** Task markers concealed to nothing: `MarkdownScanner`
emits one marker range covering `- [ ] ` entire, `SpanPlanner` concealed it whole, and `- [ ] milk`
rendered as `milk`. `TaskTapSpikeTest` measured what that costs on device — a tap sweep across a task
line landed only in `{0} ∪ [6..]`, never in 1..5.

**State it as collapse, not unreachability.** Compose maps a rendered position back to the *start* of
a deleted range, so every tap over `- [ ] ` resolved to source 0. The six offsets were shared, not
destroyed — and that is precisely why **splitting the range was sufficient**. Had the information
really been gone, no carve-out would have helped and this would have needed a replacement-glyph
transformation with a length-changing offset map. Small change versus large one, decided by one word.

**The carve-out.** `SpanPlanner.carve` conceals `[markerStart, boxStart)` and leaves `[ ] ` in the
rendered text; the space after `]` is kept or every task reads `[ ]milk`. The box comes from
`Token.box`, carried out of `taskAt` rather than re-derived — `markerStart + 2` is right on `- [ ]`
and wrong on the legal `-   [ ]`, and wrong by *shifting the tap target onto a space* rather than by
failing. `Token.markers` deliberately stays the full six characters, because `MarkdownFormat.block()`
removes `markers.first()` when the toolbar's Checkbox toggles.

**`StyleKey` 18 → 20, and the count is deliberately unpinned.** `MarkdownStyles.spanStyle` is an
exhaustive `when`, so a key nobody painted does not compile — stronger than an assertion and it
cannot go stale. `duckPairs` needs its count pin because it is a hand-maintained list with no
compiler behind it; this is not that. `TASK_BOX_DONE` carries an explicit `TextDecoration.None`,
which is load-bearing: `SpanStyle` merge keeps the underlying value wherever the overriding one is
null, so omitting it inherits `TASK_DONE`'s `LineThrough` and paints a struck-through checkbox.

**Theme: zero new pairs. `duckPairs` stays 23, `distinguishability` stays 3.** The open box is
`onSurface` and the done box `accentText`, both already floored against `surface` at 4.5.

**The overlay, not an interceptor.** A transparent `Modifier.toggleable` per box, positioned from
`TextLayoutResult.getBoundingBox` on rendered offsets that `SpanPlanner.taskBoxes` supplies. Raw
pointer interception would have to hand-roll slop and timeout to avoid eating scrolls and would
expose **no accessibility node at all**. `Role.Checkbox` plus the task's own text as
`contentDescription` is what makes TalkBack say *"count them, checkbox, not checked"*.

**It must sit inside the scroll container.** `toggleable` cancels on drag and lets the drag reach a
scrollable *ancestor*; as a sibling there is none, so the note does not scroll and it reads as
"toggleable swallows drags". SETUP.md trap 7.

**Target size: 16.4 × 26.3dp, measured — which is under WCAG 2.5.8's 24 × 24.** The plan assumed it
would clear that and it does not. It stands on 2.5.8's **`inline` exception** (a target inside a
sentence, constrained by the line-height of non-target text), which genuinely covers a `[ ]` drawn as
characters in prose — but that exception is the only thing it passes on, and that is worth knowing.
Widening is not the fix: vertically it steals the neighbouring lines' taps, horizontally it swallows
taps meant to place the caret in the task's text. The toolbar's Checkbox button is the 48dp route.

**Three findings the plants produced, none of them the defect the plant was aimed at:**

- **P5b (overlapping conceal) went green.** `ConcealInvariantTest` swept carets `0..body.length`, but
  a block token spans its whole line, so every swept caret *reveals* it — `> - [ ] x` concealed one
  range at every position, and the ordering and overlap checks run under `zipWithNext`, which does
  nothing with one element. **Two block markers had never been checked against each other.** Fixed by
  adding `CARET_AWAY` to the four sweeps; P5b then went red on overlap alone.
- **P6 falsified only half of what it was supposed to.** `detectTapGestures(onPress)` toggles on down
  *and the note still scrolls* — the drag reaches the scroll container anyway. So the "a drag
  scrolls" half needed its own plant: hoisting the overlay out of the scroll container (trap 7's
  actual hazard), which gives `Scrolled(false)`. The drag test now asserts both outcomes as one pair,
  because JUnit stops at the first failure and a sequential version proved only whichever half ran
  first.
- **P7 went green because the test was self-referential.** It computed its tap as
  `overlay.right + 12.dp`, so widening the overlay moved the probe with it. The probe is now anchored
  to the text field, and the test asserts its own premise — that the probe lands outside the overlay
  — so P7 fails by reporting that the overlay is too wide.

**A Phase 8a defect found here and pinned, not fixed.** `DuckShellViewModel.adopt` calls
`setTextAndPlaceCursorAtEnd` unconditionally, including when the body is byte-identical to what the
buffer holds. `syncBuffer` reaches it on the "same note, buffer clean" branch — the state every
successful autosave leaves behind — so Room echoes the note back and **the caret jumps to the end of
the note.** Not specific to task toggling: the same sequence moves the caret after a toolbar press
and after ordinary typing. It surfaced here only because a checkbox tap is the first edit whose
contract forbids moving the caret. Pinned by
`DuckShellAutosaveTest.aSavedBufferIsReAdoptedAndTheCaretJumpsToTheEnd`, which asserts the **wrong**
behaviour so it goes red the day it is fixed. The fix is one condition in `adopt`, but that is 8a's
code and 8a's blast radius — **scheduled, not smuggled into this commit.**

### 8c-c — section folding

Section folding shipped with one pure range planner and one editor overlay. A heading stays visible;
folding removes the lines after it through the first following heading of equal or higher level. A
parent therefore owns nested headings, and folding a parent plus a child returns one outer deletion
instead of overlapping ranges. Markdown syntax inside that body is merged into the same conceal range
before `OutputTransformation` applies it, preserving the existing descending, non-overlapping offset
contract.

The fold key is `level + occurrence among equal title/level headings + title`. The ViewModel stores the
set under a SavedStateHandle key scoped by note id, so switching notes, rotation, resize, and process
restore cannot leak a fold to another note. Entering a folded body with the caret reveals that body for
editing without discarding the saved collapsed state. The gutter chevron is a semantic button inside
the scrolling editor, positioned from the same rendered offsets as the text.

Pure tests cover peer boundaries, nested sections, duplicate heading keys, caret reveal, and parent /
child overlap. The editor's deferred caret re-clamp defect from 8a remains pinned and unscheduled;
folding does not broaden that fix.

### 8c-c-fix — what shipped unverified, and what it cost

**8c-c was committed without a gate**: no plants, no test tally, no lint, no `assembleRelease`, no
`dex_probe`, no instrumented run. Its report claimed ADB and the emulator were not installed, which is
SETUP.md trap 1 verbatim — both are installed and off `PATH`. Run afterwards, every gate passed: 437
JVM, 0 lint errors, release dex clean, `OK (56 tests)`. **Nothing was red, and four real defects were
sitting under the green.** That is the argument for the gate, not against it.

- **The saved-instance `Bundle` grew without bound.** Nothing removed `shell.foldedSections.<noteId>`,
  so a key accumulated per note folded, for the life of the process, in the 1MB Binder buffer 8a's
  saver drop exists to protect — a straight regression of 8a's "filter, note id and expanded tag
  paths" invariant. Now swept on write, so at most one note's folds exist. Consolidating into one
  map-valued key was rejected: it relocates the growth instead of bounding it.
- **The invariant suite could not see a fold.** Every `SpanPlanner.plan` call in
  `ConcealInvariantTest` used the 3-argument overload, so `foldedRanges` was always empty — while the
  merge that reconciles folds with markers carried a comment about not wanting to "look like a
  malformed conceal plan to the invariant suite". The suite now sweeps folded and unfolded, with
  `theFoldedSweepActuallyFolds` as the control, because most corpus samples fold to nothing and a
  silent no-op reads exactly like coverage.
- **The merge coalesced *adjacent* ranges, not only overlapping ones.** Adjacent ranges are already
  safe to delete back-to-front, so this bought no correctness and changed `conceal`'s shape for every
  note in the app — in the direction that makes the invariant assertions *easier* to satisfy.
  Narrowed to true overlaps.
- **No instrumented coverage at all**, on the same overlay mechanism 8c-b needed four device tests and
  three plants to get right. `SectionFoldUiTest` adds tap, the trap-7 drag/scroll pair, the TalkBack
  node, and the rebased-position claim — **and the first run of it found the chevron was untappable.**

**The chevron could not be tapped at all, and that is what shipped.** `SectionFoldOverlay` positioned
itself at `bounds.left - 24.dp` inside the *text* Box. For a heading at column 0 — the normal case,
not an edge case — `bounds.left` is 0, so its parent-space bounds were `[-24dp, 0dp]`: entirely left
of its parent's origin. Compose hit-tests within ancestor layout bounds, so no touch could reach it
while its semantics node stayed perfect and TalkBack worked fine. Measured on device at
`-3.81dp..20.19dp` against an editor starting at `20.19dp`. Section folding was in the release APK
doing nothing, and every gate passed.

The fix is **a real gutter column, not another coordinate adjustment**: the editor is now a `Row` of a
fixed-width gutter Box and the text Box, so the chevron is a sibling in its own layout slot with
`IntOffset(0, bounds.top)` and no x arithmetic left to get wrong. Chosen over insetting the text field
because it leaves `TaskBoxOverlay`'s coordinate math untouched — the text Box is still exactly the
text field's coordinate space, which is what that overlay's rebased offsets assume.
`MarkdownScanner`/`SpanPlanner` were confirmed, not assumed, to have no dependency on layout
coordinates: they are pure character-offset code.

A **second** source-vs-rendered confusion fell out of the same test.
`MarkdownEditorScreen` gated each chevron on `section.headingStart < measured.layoutInput.text.length`
— a *source* offset against the *rendered* length — so once an earlier section folded and the rendered
text shortened, every later heading's chevron silently vanished. `SectionFoldOverlay` already guards
its own rebased offset, so the outer check was both wrong and redundant.

**Two numbers to read from `MarkdownEditorScreen.FOLD_GUTTER` rather than from here:** the gutter is
**48dp**, Android's documented minimum touch target, and the reading column therefore starts **48dp**
from the pane edge, where it started 20dp before the gutter existed. 48 and not 24 because
*reachability* and *target size* are different properties — `theChevronStaysInsideTheGutter` proves
the first, and Compose's `click()` lands on exact bounds, so a 24dp target passes every check in this
repo while remaining an unreliable thumb target. Unlike the task checkbox (16.4 × 26.3dp, standing on
WCAG 2.5.8's `inline` exception because it is drawn as characters inside a sentence), a control in its
own gutter has no such excuse.

Plus: the `ModifierParameter` lint warning 8c-c introduced, its three new classes added to
`dex_probe.py`'s `MUST_BE_PRESENT`, a test for the `renderedEnd <= renderedStart` guard, and a
correction to `SectionFolding`'s KDoc, which claimed fold keys "survive edits elsewhere in the note"
without noting that retyping a heading drops its fold.

**Phase 8c is closed.**

---

## PHASE 8d-a — RTL

**Goal:** make the editor correct under a right-to-left layout direction. This is the first slice of
**8d** (images, paste, focus mode, RTL); it ships first because 8c-c's **fixed-left gutter created the
dependency** — a `Row` with a leading gutter is only "leading" if the framework mirrors it, and 8c-c
asserted that mirroring rather than measuring it. Images, paste and focus mode remain for the rest of 8d.

**Two RTL defects, both in shipped code, both measured on device — not guessed.**

**1. `TaskBoxOverlay` mirrored an already-resolved coordinate.** Its `left` comes from
`getBoundingBox(...).left`, an absolute visual coordinate that already accounts for bidi.
`Modifier.offset` *also* mirrors x under RTL, so it mirrored a value that was already correct. →
`absoluteOffset`.

**2. The overlay's base placement was direction-dependent, and `absoluteOffset` alone did not fix it.**
`Box`'s default `TopStart` resolves to the **right** edge under RTL, so the overlay was based at the
right edge and then offset rightward again. → `contentAlignment = AbsoluteAlignment.TopLeft`. The
measured progression for the RTL task box is the whole story: `673.9dp` (original, off the device) →
`−306.7dp` (with `absoluteOffset` alone) → `347.05..363.43dp` inside a field of `20.19..363.43` (both
fixes). It now sits at the line's end, which under RTL is the right edge.

**The fold gutter itself needed no change — and that claim is now evidence, not assertion.** `Row`
mirrors its children by `LocalLayoutDirection`: LTR chevron `0..48` before the field `48..391`; RTL
chevron `363..411` after the field `20..363`. 8c-c's "the gutter will mirror" is measured at last.

**The guard was wrong first, in the instructive direction.** The original assertion asked "is the box
in the trailing half under RTL?" and **passed — because `682 > 191`**, i.e. it passed *because* the box
had flown off the right edge. A control proving something other than what it was cited for: MISDIRECTED
by this document's own vocabulary, written one session after the chevron-containment fix taught the same
lesson. It now asserts **containment**, not half-of-screen. `RtlLayoutTest` is 2 instrumented tests; the
full instrumented suite is **`OK (64 tests)`**, up from 62.

**Two process failures worth naming, both now written into SETUP.md.**

- **A plant produced a false green (→ trap 10).** Plant A restored a pre-fix defect but also changed an
  import, so it did not compile; the install was **not gated on the build**, so the runner ran the
  *previous* APK and reported `OK (2 tests)` against bytes the plant never reached. `Select-Object -First
  N` on a live gradle pipeline had also corrupted the exit code. Every install now gates on
  `$LASTEXITCODE`, captured before any filter. This is the silent, green-reporting form of the global
  contract's "a plant is code, and can be wrong in the direction that fakes a red".
- **An editing shortcut corrupted a source file (→ trap 4).** `Get-Content -Raw` + `Set-Content -Encoding
  utf8` on `MarkdownEditorScreen.kt` read BOM-less UTF-8 as cp1252 and re-encoded the mojibake — and it
  would have compiled fine. Repaired at byte level (`git checkout` of a tracked file is banned): BOM
  removed, 19 em-dashes restored, and the 2 irrecoverable `U+FFFD` characters (`∪` on line 70, `×` in the
  `16.4 × 26.3dp` note) retyped by hand and afterwards verified byte-for-byte against `180d7e5`
  (`×` = `U+00D7`, `c3 97`).

**Phase 8d-a is closed.**

---

## PHASE 8d-b — IMAGES (ATTACHMENT STRIP)

**Goal:** attach images to a note. **A strip of thumbnails under the editor, not bitmaps spliced into
the rendered markdown.** The body carries a plain `![alt](attachment:<id>)` reference; the strip is the
*view* of the files those references point at. Tap the leading **+** tile to pick from the system photo
picker, tap a thumbnail to zoom, long-press to delete.

**Dependency order built: DAO → repository/storage ports → use cases → Coil → strip → shell wiring.**
Room `attachments` table and `Attachment` model already existed (empty since a prior phase), so **no
migration** — the DB stayed at version 1.

**Three deviations, all recorded here rather than left to be discovered.**

**1. Strip, not inline rendering.** Inline would mean an `InlineTextContent` keyed off a
`TokenKind.IMAGE` span, sized from the decoded bitmap, re-measured inside the same
`MarkdownOutputTransformation` the caret, task overlays and fold overlays already contend over — the
one surface whose 10k-word cost is still unevidenced (8a). The strip keeps images entirely out of that
path. The reference in the body is the durable/export half; the strip is the rendered half. Same
split-the-concern move as the tag chip row in Phase 7.

**2. The placeholder is a bordered `surfaceElevated` tile, not a muted glyph.** The obvious choice —
a placeholder icon in `proseMuted` (the token whose KDoc names "image placeholders") — is *heavier*
than the 64dp thumbnail it stands in for, and `chromeMuted`, the lighter grey, is barred from carrying
anything readable at all. So the empty tile owes neither and uses neither: a `surfaceElevated` fill
under a `divider` hairline, which is what shows while Coil decodes or if the file is gone. `duckPairs`
stays 23, distinguishability stays 3 — **no new colour pairing**, which is the whole reason for the
choice. Flagged because it contradicts a literal reading of `proseMuted`'s own doc.

**3. Coil pinned to 3.2.0, not the current 3.5.0.** Coil 3.3.0+ set `CheckAarMetadata` to require
`compileSdk 36`; Duck is on 35 this phase, and the build fails at `CheckAarMetadataWorkAction` with
*"requires … compile against version 36 or later"*. 3.2.0 predates that bump and its `AsyncImage(File)`
API is identical. `coil-compose` only — no `coil-network`, since local files need no fetcher. Recorded
in `libs.versions.toml` beside the pin.

**Atomicity: best-effort, decided on purpose.** `AttachImage` does store-file → insert-row →
append-reference, **not** one transaction. The order makes the only reachable mid-kill partial state
benign: a stored file with no row (a few KB, the same category as the accepted hard-delete orphan), or
a row with no body reference (usable in the strip, merely absent from exported markdown). Neither
corrupts a note. A crash-safe version is one Room transaction over the row and the note-body write —
deferred to Phase 14's orphan sweep, which already owns hard-delete leftovers. `DeleteAttachment`
removes all three (file, row, reference); a missing row is a no-op, matching `DeleteNote`'s idempotence.

**The picker grant, and why the copy exists.** `PickVisualMedia` returns a **revocable, process-scoped**
`content://` read grant. `AttachmentStorageImpl` copies the bytes into `filesDir/attachments/<id>` and
records that path, so the attachment outlives the grant. The copy runs synchronously moments after the
pick, well inside the grant's lifetime.

**RTL: the strip mirrors for free, and that is now evidence.** A `LazyRow` lays items start-to-end per
`LocalLayoutDirection`, so the **+** tile (first item) leads — left in LTR, right in RTL — with no
production code doing anything. `RtlLayoutTest.theAttachmentStripMirrorsWithLayoutDirection` measures
it: LTR add-tile `right ≤` first-thumb `left`; RTL add-tile `left ≥` first-thumb `right`. Its plant
(`reverseLayout = true`) fails **both** halves with real geometry (LTR add at `80..144` vs thumb
`8..72`; RTL add `267..331` vs thumb `339..403`), which is what distinguishes a true red from a stale
one.

**Six plants, each run red then reverted.**

1. `MarkdownSyntaxSourceTest` — `SYNTAX += "!["`. Plant: a `"![]("` literal in `AttachmentStrip`
   (swapped for the **+** glyph) → `noSyntaxLiteralAppearsAboveTheScanner` red. The one home for `![`
   is `MarkdownScanner.imageRef`, round-tripped by `MarkdownFormatTest`.
2. `AttachImageTest` — strip-vs-body agreement. Plant: `AttachImage` returns `note.body` instead of
   `appendImage(...)` → both agreement assertions red (the id is in the row but not the body).
3. `DeleteAttachmentTest` — no orphan. Plant: `DeleteAttachment` skips `storage.delete(row.uri)` →
   the file-orphan assertion red.
4. `AttachmentDurabilityTest` (instrumented) — the real copy. Plant: `AttachmentStorageImpl` returns
   `sourceUri.path` instead of the copy's path → *"stored under filesDir/attachments"* red. **Honest
   scope:** it proves copy-independence (delete the source, the copy survives byte-for-byte), **not**
   `content://` grant revocation — no instrumented test can drive the real picker grant, and calling
   the file-delete "grant revocation" would be a *misdirected* control. Stated in the test's KDoc.
5. `dex_probe.py` — `MUST_BE_PRESENT` gains the seven new classes. See its own subsection below; the
   plant taught something.
6. `RtlLayoutTest` strip mirror — described above.

### The dex-probe plant taught two things, both process failures the fifth-plant lesson predicts

**A class-only rename did not turn `MUST_BE_PRESENT` red.** Renaming `AttachmentRepositoryImpl` →
`AttachmentRepoImpl` (class + DI binding) left the probe reporting it **present, count 6** — a false
green. The class rename *did* take (`AttachmentRepoImpl` appeared 14× in the dex), but all 6 hits of
the old name were the **source-file name `AttachmentRepositoryImpl.kt`** embedded in the dex debug info
(`SourceFile` / Kotlin `SMAP`). `blob.count("AttachmentRepositoryImpl")` is a substring match, and the
filename contains the string. **So `MUST_BE_PRESENT` really tests "a class of this name OR a `.kt` file
of this name compiled into the dex."** For every entry whose class name equals its filename — which is
all of them — a positive control passes as long as the *file* is in the build. The valid plant had to
rename the **file** too; then the string vanished and the probe went `MISSING … 0`, exit 1. This is not
a defect introduced this phase — it is a property the probe always had — but no prior plant had renamed
a class without also deleting its file, so it had never surfaced.

**`org.gradle.caching=true` can serve a stale dex to the probe.** During the same investigation, a
plain `assembleRelease` — and even `clean assembleRelease` — returned a byte-identical dex from the
build cache in 6 s. The probe read whatever dex the cache handed back, so it can certify bytes your
edit never reached — the exact shape of trap 10, one layer down. Both findings are written into
SETUP.md as trap 11, **which is where the current flag lives — read it there rather than from here.**
This paragraph said `--no-build-cache` (or `--rerun-tasks`), and 9c proved those are not equivalent:
`--no-build-cache` does not disable Gradle's up-to-date checks, so the task can be skipped outright.
`--rerun-tasks` is the one that forces the work.

### Verification

- **JVM unit:** `452 tests, 0 failures` across 41 classes (was ~440). New: `AttachImageTest`,
  `DeleteAttachmentTest`, `MarkdownFormatTest` image round-trip, `FakeAttachmentRepository`,
  `FakeAttachmentStorage`.
- **Instrumented:** full suite `OK (75 tests)` (was 64). New: `AttachmentDaoTest` (ordering, scoping,
  FK cascade + its positive control), `AttachmentStripUiTest` (add-tile click, thumbnail long-press,
  both-shown control), `AttachmentDurabilityTest`, `RtlLayoutTest` strip mirror.
- **Lint:** 0 errors; the one new-code warning (`UseKtx` on `Uri.parse`) fixed via `String.toUri`.
  Remaining 40 are pre-existing project-wide (`GradleDependency`, `NewerVersionAvailable`, …).
- **Release:** `assembleRelease --no-build-cache` green; `dex_probe.py` **PASS** — 15 positive
  controls present, 4 debug-only classes absent, no bear references outside `bearingAccuracy`.

**Phase 8d-b is closed.**

---

## PHASE 8d-c — PASTE

**Goal:** paste a clipboard image into the editor and attach it exactly as the picker does — the image
to the strip, its `![](attachment:<id>)` reference into the body, written by the **same** `AttachImage`
path, so there is no second implementation of image syntax. **Deferred, stated up front:** HTML paste
and link-over-selection paste — both out of scope this phase.

**One modifier, one listener, no new attach path.** `MarkdownEditorScreen`'s `BasicTextField` gains
`Modifier.contentReceiver(imagePasteReceiver(...))`. The receiver pulls image items out of a paste (or
drag-and-drop, or keyboard rich content), resolves each `content://` MIME through the `ContentResolver`
— exactly as the picker reads it — and routes them to `onAttachImages`, the same use case the **+** tile
calls. Text pastes fall through untouched.

**The clobber race, fixed by sequencing (the 1a decision).** Paste can deliver more than one image at
once. Each `AttachImage` reads the body, appends its reference and saves; two concurrent attaches both
read the pre-append body and the second clobbers the first's reference. `onAttachImages` flushes once
and loops **sequentially**, so each append sees the previous one's save. The picker's `onAttachImage` is
the singleton case of this — one attach path, not two.

**API verified against the artifact, not from memory — and it forced the test design.**
`TransferableContent`'s only constructor is **private** (checked against Foundation 1.9.4's bytecode,
the version BOM 2025.10.01 resolves), so a test cannot synthesise one to drive
`imagePasteReceiver.onReceive`. The classifying decision was therefore split into `shouldAttach` /
`isAttachableImage`, which take an ordinary `ClipData.Item` / `String` a test *can* build. The listener's
remaining body — the `hasMediaType` gate and `consume` mechanics — is three framework lines with no
branch of its own, covered by a one-time manual on-device paste rather than an automated test faking an
unconstructable type.

**Two plants, each run red then reverted.**

1. `ImagePasteTest` (JVM) + `ImagePasteReceiverTest` (instrumented) — the MIME rule. Plant: invert
   `isAttachableImage` (`!startsWith("image/")`) → the JVM test goes 4/4 red and the instrumented
   `shouldAttach` test 2/4 red, both on the one predicate.
2. `dex_probe.py` — `MUST_BE_PRESENT += "ImagePaste"`. Plant: rename the `ImagePaste.kt` **file** (not
   just the class — trap-11's lesson from 8d-b) → the probe goes `MISSING … 0`, exit 1.

### The required manual verification came back negative — and that is the phase's real finding

**Performed by hand, negative, and the cause is upstream of Duck.** On the Pixel_7 / Android 15 (API 35)
emulator a human copied a real image via Chrome's "Copy image" — a genuine live-grant source — then
long-press → Paste into a note body. Nothing attached: no strip thumbnail, no `![](attachment:<id>)`,
no error.

**The receiver fired; the clip was empty.** Temporary logging in `imagePasteReceiver.onReceive` (added,
read, reverted) showed it fires on **every** paste and is handed an empty clip —
`mimes=[text/plain] items=1 item0uri=null item0text="" item0html=false`: no image, no URI, no content
of any kind. So `hasMediaType(MediaType.Image)` is correctly `false`. **Confirmed independent of Duck:**
Android's own Gboard clipboard history, after the same "Copy image", also shows no image, with Duck not
involved at all. Chrome is not placing the image on this emulator's system clipboard for any consumer.

**What is verified, and what is deferred.** Everything Duck's code controls is proven: the receiver
fires; `hasMediaType` classifies correctly; and `shouldAttach` plus the downstream attach path (`store`
→ row → `appendImage`) is the same `onAttachImages` / `AttachImage` path 8d-b's working picker
exercises, plus the JVM and instrumented suites. The single link not exercisable here — a real image
clip actually reaching the receiver — is blocked by the emulator's clipboard, not by Duck. A true
end-to-end image-paste confirmation is **deferred to a physical device**.

**Two latent silent-failure sinks, found during the investigation, left for a hardening pass.**
`AttachmentStorageImpl.store` wraps its copy in `runCatching { … }.getOrNull()` and `AttachImage`
returns `null` on a failed copy — so a *real* image that failed to read would attach nothing with no
user feedback. Not the cause here (the clip is empty; nothing reaches them) and out of scope for 8d-c,
but recorded: the upgrade path is surfacing a copy failure to the user, the shape `onAddTagToNote`'s
`NotWritable` snackbar already uses.

### Verification

- **JVM unit:** `456 tests, 0 failures` (was 452). New: `ImagePasteTest` — the `image/` MIME boundary,
  incl. `imagexml` is **not** an image.
- **Instrumented:** full suite `OK (79 tests)` (was 75). New: `ImagePasteReceiverTest` — drives the real
  `shouldAttach` against real `ClipData.Item`s (image → uri+mime; non-image, plain-text, and untyped →
  null).
- **Lint:** 0 errors; 40 pre-existing warnings, none on the paste files.
- **Release:** `assembleRelease --rerun-tasks --no-build-cache` green; `dex_probe.py` **PASS** — 16
  positive controls present (now incl. `ImagePaste`), 4 debug-only absent, no bear references outside
  `bearingAccuracy`.

**Phase 8d-c is closed** for everything the code controls; the one link that could not be exercised on
the emulator — a real image clip reaching the receiver — is deferred to a physical device, per the note
above. **Focus mode is the remaining piece of 8d.**

---

## PHASE 9 — LINKING + INFO PANEL

**Goal:** Personal wiki without a graph database.

**Build**
- `[[Note title]]` autocomplete popup while typing.
- Resolve on save → `note_links` rows. Unresolved link = create-on-tap.
- Info panel (bottom sheet / side pane): word count, char count, created, modified, table of contents from headings, backlinks list, unlinked mentions (title appears as plain text elsewhere).
- ToC tap scrolls to heading.

**Known defect carried from Phase 4 — fix before writing `note_links` rows**

`[[WikiLink]]` has **three** implementations that disagree, which is the exact failure `TagScanner`
was extracted to prevent — in the other syntax.

- `ResolveWikiLinks.kt:22` is a bare `Regex("\\[\\[([^\\[\\]]+)]]")` over the whole body. **No code
  masking, no fence tracking.** It resolves `` `[[X]]` `` and a fenced `[[X]]` as real links.
- `MarkdownScanner.wikiLinkAt` correctly refuses both: a fenced line yields only a `FENCE_BODY` token
  and never reaches inline scanning, and `scanInline` skips offsets masked by
  `TagScanner.codeSpanMask`.
- **`DeriveTitle.kt:44` holds a byte-identical third copy** of the regex, found by the post-8b audit.
  It was missed because the defect was written up as "two implementations" and the third does not
  resolve links — it strips them for a title, and mis-strips a wiki link inside code the same way.
  **Three call sites to retire, not two.**

So a link written inside a code sample will be painted as plain text but still written to
`note_links`, producing a backlink the user cannot see or click.

**No longer failing by omission — it is pinned.** `MarkdownCorpus` now carries `wikiLinkInFence` and
`wikiLinkInCodeSpan`, which made `TagAgreementTest.theEditorPaintsTheWikiLinksResolveWikiLinksFinds`
go red exactly as predicted. Rather than leave it red, the divergence is asserted explicitly by
`TagAgreementTest.aWikiLinkInsideCodeIsResolvedButNotPainted`, with the two samples excluded from the
agreement test via `WIKI_LINK_CODE_MASKING_DEFECT` — one list, two consumers, so the exclusion and
the pin cannot drift.

**The pin asserts the defect, so it goes red when you fix it.** That is the signal, not a regression:
delete the pin and `WIKI_LINK_CODE_MASKING_DEFECT`, and the agreement test regains full corpus
coverage with no other edit. Do not loosen the assertion to make it pass.

**Fix:** extract a `WikiLinkScanner` beside `TagScanner` in `domain/markdown/`, owning the bracket
rule and reusing `codeSpanMask` plus the fence carry. `ResolveWikiLinks`, `MarkdownScanner` and
`DeriveTitle` all become thin views over it, exactly as `ParseTags` is over `TagScanner`. One
implementation, four views (counting the CommonMark side below). Do this **before** `note_links` rows
are written, or the bad rows outlive the fix.

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

## DEFERRED HARDENING

A running backlog of latent defects found in passing and deliberately left, so they have a home to be
scheduled from rather than being re-discovered each phase. **Not owned by any one phase** — Phase 16
(or an earlier hardening pass) schedules these; the point of listing them here is that "mentioned in a
phase's notes" is where a defect goes to be forgotten.

- **`AttachmentStorageImpl.store` swallows its copy failure.** It wraps the byte copy in
  `runCatching { … }.getOrNull()`, so a `content://` source that cannot be read returns `null` with no
  signal. *Fix:* surface the failure to the caller and show it, in the `NotWritable`-snackbar shape
  `onAddTagToNote` already uses. (Found 8d-c; mentioned there, and again in 8d-d, before landing here.)
- **`AttachImage` returns `null` on a failed copy with no user feedback.** A real image that failed to
  read attaches nothing and says nothing. *Fix:* same — propagate the failure and raise the same
  snackbar rather than returning `null` silently. Pairs with the sink above; one snackbar covers both.
### Closed

| what | where |
|---|---|
| **`NoteDao.saveNoteWithTags` wrote `note_links` rows as well as tags under a name saying only tags** — the *misnamed* class in production code, and the entry here that degraded on its own with no code change: a future reader takes the link write as misplaced, deletes it, and the deletion looks correct in review. Renamed to `saveNoteWithTagsAndLinks`, 39 occurrences across 15 files, compiler-enforced, no behaviour change. Found 9b, folded into **9c** as the next phase to touch the file — which is the whole point of the entry having existed. | `NoteDao.saveNoteWithTagsAndLinks` |

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
