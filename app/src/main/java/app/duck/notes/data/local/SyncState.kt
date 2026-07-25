package app.duck.notes.data.local

/** Where a note stands relative to the remote (Phase 13). Local-only until then. */
enum class SyncState {
    /** No pending local change; matches the server. */
    SYNCED,

    /** Local write queued in the outbox, not yet pushed. */
    PENDING,

    /** Diverged from remote; a conflicted copy must be resolved. */
    CONFLICT,
}
