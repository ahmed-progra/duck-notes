package app.duck.notes.domain.model

/**
 * Enough to put N note bodies back exactly as they were — the undo for rename and for delete.
 *
 * Both operations produce one and `TagRepository.restore` consumes it, because they are the same
 * operation in opposite directions: *write these bodies back*. Undo therefore shares their code path
 * and cannot drift from what it inverts.
 *
 * **Restoring bodies restores tag membership for free**, since `note_tags` is re-derived by
 * `ParseTags` on every save. That is what makes the snapshot an honest inverse even of a *merge*,
 * which no inverse rename could undo: writing the old bodies back re-splits the two tags and leaves
 * the merged-into tag holding precisely its original notes.
 *
 * Two things it does not get for free, which is what the other two fields are for.
 *
 * @param bodies note id to body as it was. Held in memory for the life of the Snackbar only —
 *   **not durable across process death**, and N full bodies wide. A durable undo is an operation
 *   log, which is Phase 8's undo/redo stack or nothing.
 * @param tags the rows as they were, so pin and icon survive. `NoteDao.ensureTag` mints a fresh
 *   UUID with no customization, so it has to be written back on top of whatever row now holds the
 *   name.
 * @param createdNames names the operation brought into existence — rename's targets; empty for
 *   delete, which creates nothing. Rename carries the old row's pin onto the new name, and that is
 *   exactly what makes the new row **survive `deleteOrphanTags`** — so undo has to sweep it, or it
 *   leaves a pinned, empty `job` in the sidebar after an undo that claimed to restore everything.
 *   The sweep is conditional on the row being orphaned, which is what keeps a merge target that had
 *   notes of its own alive.
 */
data class BodySnapshot(
    val bodies: Map<String, String>,
    val tags: List<Tag>,
    val createdNames: List<String> = emptyList(),
) {
    /** How many notes the operation rewrote — the number the Snackbar and the delete dialog quote. */
    val noteCount: Int get() = bodies.size
}
