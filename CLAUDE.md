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

## Git

- Commit only when I say "commit" or at end of an approved phase.
- Never commit mid-phase. Never commit broken code.
- One commit per phase. No auto-push.
- Message format: `phase <n>: <name>` (e.g. `phase 2: room + fts5`)
- Before committing: `git status`, show me the file list, wait for approval.
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
