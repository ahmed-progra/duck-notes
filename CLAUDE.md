DUCK NOTES — Claude Code Build Prompt

Android clone of Bear (iOS markdown notes). Rebranded: Duck. Paste one phase per session. Do not skip phases.

0. GLOBAL CONTRACT

Applies to every phase. Never override.

Identity

App name: Duck Notes. Package: app.duck.notes.
Mascot: duck. Empty states, splash, app icon, onboarding.
No bear references anywhere. Not in code, strings, or assets.

Brand tokens

accent #D7494C (215,73,76 · HSL 359,64%,56%)
accentPress #B93C3F
surface #FFFFFF
surfaceDark #1C1C1E
text #2C2C2E
textMuted #8E8E93
divider #E5E5EA

Accent used for: tag chips, links, active sidebar row, cursor, selection, note-list active bar, checkbox fill.

Stack — fixed

Layer Tech
Language Kotlin
UI Jetpack Compose (Material 3)
Arch MVVM + Clean Architecture
DB Room
Markdown CommonMark (commonmark-java)
Search SQLite FTS5
Images Coil
DI Hilt
Network Ktor client
Sync Supabase
Prefs DataStore
Crypto Android Keystore

Working rules

State assumptions before coding. Ask if unclear.
Minimum code. No speculative features. No abstraction without a second caller.
Surgical edits. Match existing style. Never refactor unrelated code.
Define verifiable success criteria before writing code.
Offline-first always. Network is optional, never required.
Every phase ends green: compiles, tests pass, app runs.

Chain-of-thought protocol per phase

STEP 1 THINK — restate goal, list unknowns, state assumptions
STEP 2 PLAN — files to create/modify, data flow, edge cases
STEP 3 CONFIRM — show plan, wait for approval if ambiguous
STEP 4 BUILD — implement smallest correct version
STEP 5 VERIFY — run build + tests, check success criteria
STEP 6 REPORT — what changed, what is still open

## Guards

**A guard must not source its threshold, its subject, or its exemption from anything other than
the thing under test.**

- **Pin the literal.** A test that reads `MAX_WAIT_MS` to build its own bound moves with the
  constant and can never catch a change to it. Assert the number.
- **Drive the real thing.** A test that rebuilds its subject locally — its own `AnimatedContent`,
  its own `debounce`, its own canonicalizer — proves the library works, not that Duck calls it.
  Make the production symbol `internal` and call it.
- **Anchor the exemption to the match, never to its neighbourhood.** An exemption scoped wider than
  the thing it exempts lets an adjacent legitimate value vouch for an illegal one.
- **Every branch that can suppress a finding owes its own positive control**, not just the branch
  that produces one. Controls for presence prove the scan runs; they say nothing about whether the
  filter downstream is eating real hits.
- Every guard owes a named plant: the edit to `src/main` that turns it red. Run it, watch it fail,
  revert. A guard with no nameable plant is decoration — say so in its KDoc rather than leaving it
  to read as cover.
- **A plant is code, and can be wrong in the direction that fakes a red.** Two of this pass's plants
  were defective; both failed to *compile*, which is the safe direction. A plant that compiles, runs
  and exercises the wrong path produces a red you would believe — strictly worse than no plant,
  because it certifies a guard nobody has actually tested. Mitigation, and it is cheap: **prefer a
  minimal edit to an existing expression over new code.** Invert a condition, change a constant,
  swap one spec. A plant that adds a function or an import can fail on its own terms.

Written down after five checks shipped as five dressings of one defect. `MotionTest` read
`MAX_DURATION_MS` off the class it policed; the `MAX_WAIT_MS` tests read the constant;
`PaneTransitionTest` built its own `AnimatedContent`; `debounceAloneNeverFires` built its own
debounce.

**The fifth is the strongest, and it is the only one nobody reasoned out.** The other four were
found by reading the tests. `dex_probe.py`'s no-bear check was found by planting `"bear notes"` in
`src/main`, building a real release APK, and watching the probe pass it. Four cases someone argued
from a screen read as theory; the one that survived contact with a real dex string table is the
evidence.

The mechanism generalises well past dex. The framework exemption asked *"does `bearing` appear
anywhere in this 52-byte window?"* rather than *"is this hit itself `bearing`?"* — the exemption was
sourced from the window instead of from the match, so a legitimate neighbour vouched for an illegal
string. A dex string table is **sorted**, which made that adjacency systematic rather than
accidental: any string beginning `bear` sorts immediately next to the framework's own
`bearingAccuracy`, so the filter **could never report a bear-prefixed string at all** — the exact
shape a rebrand leaks. The probe had positive controls for presence and none for the exemption
path, so nothing was watching the branch that did the suppressing.

## Git

- Commit only when I say "commit" or at end of an approved phase.
- Never commit mid-phase. Never commit broken code.
- One commit per phase. No auto-push.
- Message format: `phase <n>: <name>` (e.g. `phase 2: room + fts5`)
- Before committing: `git status`, show me the file list, wait for approval.
- After a `/compact`, a bare "continue"/"continou" answers only "keep working" — never a yes/no
  question (e.g. a pending commit question) asked immediately before the compaction. If such a
  question is still open when a session compacts, re-ask it explicitly in your first message back
  rather than treating a generic continue as its answer.
- Never run: push, reset --hard, rebase, checkout of tracked files, clean -fd.
- Never touch .gitignore without asking.

## Kotlin style

- Always use modern Kotlin (1.9+).
- Use type inference when obvious.
- Prefer `val` over `var`.
- Use data classes for state holders.
- Avoid `!!`. Use safe calls or `?:`.
- Use inline functions for trivial lambdas.
- Use `run`/`with`/`apply`/`also` instead of temp variables.
- Use concise property access: `.property` instead of `get()` / `set()`.
- Avoid Java interop when clean Kotlin exists.
