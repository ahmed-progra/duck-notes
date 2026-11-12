package app.duck.notes.data.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.dao.NoteDao
import app.duck.notes.data.local.dao.TagDao
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.mapper.toDomain
import app.duck.notes.data.mapper.toEntity
import app.duck.notes.domain.markdown.TagScanner
import app.duck.notes.domain.model.BodySnapshot
import app.duck.notes.domain.model.Tag
import app.duck.notes.domain.model.TagRef
import app.duck.notes.domain.repository.TagRepository
import app.duck.notes.domain.usecase.DeriveTitle
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

/**
 * Tag reads, plus the two operations that rewrite note bodies.
 *
 * ## Why the body rewriting lives here and not in a use case
 *
 * The transaction boundary has to be in `data/` — `domain/` cannot import `androidx.room`, and
 * `DomainPurityTest` enforces exactly that. So the loop that reads N notes, rewrites them and writes
 * them back sits inside [inTransaction] here, and the use cases above it are thin delegates in the
 * shape `PinTag` already established.
 *
 * It injects [DeriveTitle] and [ParseTags], which are domain use cases. That direction is correct —
 * data depends on domain, never the reverse — and it is the point: re-deriving the title and the tag
 * list with the same code an ordinary save uses is what stops a renamed note from acquiring a
 * different title rule than a typed one.
 *
 * ## Why the rewriting is [TagScanner] and not a regex
 *
 * A regex over `#work` resolves a URL fragment, an escaped `\#work`, an ATX heading marker, an
 * inline code span and a fenced block as tags — five ways to corrupt a body, and five disagreements
 * with what the editor paints and what `ParseTags` stores. This is the `[[WikiLink]]` defect Phase 9
 * documents, in the other syntax, and it is avoided by there being nothing here that knows what a
 * tag looks like.
 */
class TagRepositoryImpl @Inject constructor(
    private val db: DuckDatabase,
    private val tags: TagDao,
    private val notes: NoteDao,
    private val deriveTitle: DeriveTitle,
    private val parseTags: ParseTags,
    private val resolveWikiLinks: ResolveWikiLinks,
) : TagRepository {

    override fun observeAll(): Flow<List<Tag>> =
        tags.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeNoteRefs(): Flow<List<TagRef>> =
        tags.observeNoteRefs().map { rows -> rows.map { it.toDomain() } }

    /** No-op for an unknown id rather than creating a tag — tags only come from note bodies. */
    override suspend fun setPinned(tagId: String, pinned: Boolean) {
        val tag = tags.getById(tagId) ?: return
        tags.upsert(tag.copy(isPinned = pinned))
    }

    override suspend fun setIcon(tagId: String, iconKey: String?) {
        val tag = tags.getById(tagId) ?: return
        tags.upsert(tag.copy(iconKey = iconKey))
    }

    /**
     * One transaction, all-or-nothing. A rename that renamed 140 of 200 notes would leave two tags
     * where the user asked for one, and no way to tell which notes are in which half.
     *
     * Vacate source rows before rewriting so an overlapping destination cannot be deleted or moved.
     */
    override suspend fun rename(from: String, to: String): BodySnapshot = inTransaction {
        if (from == to) return@inTransaction BodySnapshot(emptyMap(), emptyList())
        val affected = notes.notesByTagPathOnce(from)
        val oldRows = tags.rowsUnderPath(from).map { it.toDomain() }
        val newNames = oldRows.map { to + it.name.removePrefix(from) }
        val createdNames = newNames.filter { tags.getByName(it) == null }

        oldRows.forEach { tags.deleteByName(it.name) }
        val bodies = rewriteAll(affected) { TagScanner.rename(it, from, to) }
        carryCustomization(oldRows, newNames)

        BodySnapshot(bodies = bodies, tags = oldRows, createdNames = createdNames)
    }

    /**
     * One transaction, all-or-nothing, and **the exact name only** — descendants survive.
     *
     * Rename must cascade because a child's name contains its parent's; nothing forces this to, and
     * `projects/duck` stays a perfectly valid name after `projects` is gone. Cascading here would
     * strip `#projects/duck/ui` from notes the user never named.
     */
    override suspend fun removeTag(name: String): BodySnapshot = inTransaction {
        val affected = notes.notesByTagNameOnce(name)
        val row = tags.getByName(name)

        val bodies = rewriteAll(affected) { TagScanner.remove(it, name) }
        tags.deleteByName(name)

        BodySnapshot(bodies = bodies, tags = listOfNotNull(row?.toDomain()))
    }

    /**
     * The inverse of both, in one transaction — *write these bodies back*.
     *
     * Restoring bodies restores tag **membership** for free, because `saveNoteWithTagsAndLinks` re-derives
     * `note_tags` from the text. It is what makes undo honest even after a merge, which no inverse
     * rename could achieve. Orphan aliases are removed before restoring customization, so renamed
     * source IDs cannot move a restored name out from under another source row.
     * The editor supplies expected post-operation bodies; conflicts reject the whole transaction
     * before any restore writes, so later saved text cannot be overwritten by an old snapshot.
     */
    override suspend fun restore(snapshot: BodySnapshot, expectedBodies: Map<String, String>?) = inTransaction {
        if (expectedBodies != null) {
            require(expectedBodies.keys == snapshot.bodies.keys)
            // Check the entire set before restoring even its first body or tag customization.
            for ((id, body) in expectedBodies) {
                check(notes.getById(id)?.body == body) { "Note changed after the tag operation" }
            }
        }
        for ((id, body) in snapshot.bodies) {
            val note = notes.getById(id) ?: continue
            writeBody(note, body)
        }
        (snapshot.createdNames + snapshot.tags.map { it.name }).distinct().forEach {
            tags.deleteIfOrphaned(it)
        }
        snapshot.tags.forEach { old ->
            val current = tags.getByName(old.name)
            when {
                current != null -> tags.upsert(current.copy(iconKey = old.iconKey, isPinned = old.isPinned))
                old.isPinned || old.iconKey != null -> {
                    // Overlap can leave this ID referenced under another restored name.
                    val restoredId = if (tags.getById(old.id) == null) old.id else UUID.randomUUID().toString()
                    tags.upsert(old.toEntity().copy(id = restoredId))
                }
                // A plain row with no body to re-create it is not resurrected: `setPinned`'s rule is
                // that tags only come from note bodies, and the next save reaps it anyway.
                else -> Unit
            }
        }
    }

    // --- shared machinery ----------------------------------------------------

    /**
     * One write transaction — **not** `room-ktx`'s `withTransaction`, which cannot work here.
     *
     * `withTransaction` reaches for the `SupportSQLiteOpenHelper`, and `DatabaseModule` installs a
     * `BundledSQLiteDriver` (the system SQLite may lack the FTS5 module `notes_fts` needs), which
     * replaces that path entirely. Calling it throws
     * *"Cannot return a SupportSQLiteOpenHelper since no SupportSQLiteOpenHelper.Factory was
     * configured"* at runtime — so rename, delete and undo all failed on a real device while every
     * JVM test stayed green, because those run against a fake repository.
     *
     * `useWriterConnection` + `immediateTransaction` is the driver-era equivalent. `immediate`
     * rather than `deferred` because every caller writes: it takes the write lock up front instead
     * of upgrading mid-transaction and risking `SQLITE_BUSY`. Nested DAO calls reuse the held
     * connection — Room tracks it in the coroutine context, which is what makes
     * `saveNoteWithTagsAndLinks`'s own `@Transaction` compose rather than deadlock.
     *
     * `DuckDatabaseTest` records the same trap for `insertAll`; this is that note's other half.
     */
    private suspend fun <R> inTransaction(block: suspend () -> R): R =
        db.useWriterConnection { it.immediateTransaction { block() } }

    /**
     * Applies [transform] to each note's body and writes the changed ones back, returning the bodies
     * **as they were** for the snapshot.
     *
     * Notes whose body the transform leaves untouched are skipped entirely rather than re-saved: a
     * no-op save would still restamp `modifiedAt` and reorder the note list for nothing.
     */
    private suspend inline fun rewriteAll(
        affected: List<NoteEntity>,
        transform: (String) -> String,
    ): Map<String, String> {
        val previous = mutableMapOf<String, String>()
        for (note in affected) {
            val next = transform(note.body)
            if (next == note.body) continue
            previous[note.id] = note.body
            writeBody(note, next)
        }
        return previous
    }

    /**
     * One note, through the same single write path an ordinary save uses.
     *
     * `modifiedAt` is **not** restamped. A rename is not an edit the user made to that note's
     * content, and restamping 200 notes would reshuffle the entire note list — losing the user's
     * sense of what they were last working on, in return for nothing.
     */
    private suspend fun writeBody(note: NoteEntity, body: String) {
        // Links re-derived from the rewritten body for the same reason the title and tags are: this
        // is the single write path, and `saveNoteWithTagsAndLinks` clears a note's link rows before
        // re-inserting. Omitting them here would make a tag rename silently delete every
        // `[[WikiLink]]` row of every note it touched — a rename in one syntax destroying data in
        // another. A rename never edits bracket syntax, so the re-derived list is the same list.
        notes.saveNoteWithTagsAndLinks(
            note.copy(title = deriveTitle(body), body = body),
            parseTags(body),
            resolveWikiLinks(body),
        )
    }

    /**
     * Copies pin and icon from each old row onto the row now holding its new name.
     *
     * **The target's own values win.** On a merge the destination tag already existed and may
     * already be pinned or have an icon the user chose for it; carrying only into fields it left
     * unset is the least surprising rule, and the only one that does not silently overwrite a
     * deliberate choice with an incidental one.
     */
    private suspend fun carryCustomization(oldRows: List<Tag>, newNames: List<String>) {
        oldRows.zip(newNames).forEach { (old, newName) ->
            if (!old.isPinned && old.iconKey == null) return@forEach
            val current = tags.getByName(newName)
                // No body minted this destination; the source ID was vacated before rewriting.
                ?: return@forEach tags.upsert(old.toEntity().copy(name = newName))
            tags.upsert(
                current.copy(
                    isPinned = current.isPinned || old.isPinned,
                    iconKey = current.iconKey ?: old.iconKey,
                ),
            )
        }
    }
}
