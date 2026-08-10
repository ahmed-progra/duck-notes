package app.duck.notes.data.repository

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.DuckDatabaseCallback
import app.duck.notes.data.local.dao.TagDao
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.local.entity.TagEntity
import app.duck.notes.domain.usecase.DeriveTitle
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The body-rewriting half of [TagRepositoryImpl], against a real SQLite.
 *
 * The JVM tests cover the use cases over a fake repository, which is where the *decisions* live.
 * What a fake cannot check is the part that only exists because there is a database: whether the
 * cascade is a real prefix match, whether the writes participate in a transaction, and whether the
 * DAO's stated scope is what the SQL actually does. Those are the four claims below.
 *
 * `TagScanner` correctness is not retested here — [app.duck.notes.domain.markdown.TagScannerRewriteTest]
 * owns it over 29 cases, and repeating any of them on an emulator buys nothing.
 */
@RunWith(AndroidJUnit4::class)
class TagRewriteTest {

    private lateinit var db: DuckDatabase
    private lateinit var repo: TagRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DuckDatabase::class.java)
            // Must match production: system SQLite may lack FTS5, which `notes_fts` requires.
            .setDriver(BundledSQLiteDriver())
            .addCallback(DuckDatabaseCallback(seedSampleNotes = false))
            .build()
        repo = TagRepositoryImpl(db, db.tagDao(), db.noteDao(), DeriveTitle(), ParseTags(), ResolveWikiLinks())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(body: String, trashed: Boolean = false): String {
        val id = UUID.randomUUID().toString()
        db.noteDao().saveNoteWithTagsAndLinks(
            NoteEntity(
                id = id,
                title = DeriveTitle()(body),
                body = body,
                createdAt = 0L,
                modifiedAt = 0L,
                isTrashed = trashed,
            ),
            ParseTags()(body),
        )
        return id
    }

    private suspend fun bodyOf(id: String) = db.noteDao().getById(id)?.body

    private suspend fun tagNames() = db.tagDao().observeAll().first().map { it.name }.sorted()

    private suspend fun tag(name: String) =
        checkNotNull(db.tagDao().getByName(name)) { "no tag row named $name" }

    /**
     * Rename cascades down the path, and **only** down the path.
     *
     * `projects2` is the case a naive `LIKE 'projects%'` gets wrong: it shares the prefix but is not
     * a descendant, and the `|| '/'` in `notesByTagPathOnce` is the only thing separating them.
     */
    @Test
    fun rename_cascadesToDescendantsButNotToPrefixNeighbours() = runBlocking {
        val parent = seed("#projects note")
        val child = seed("#projects/duck note")
        val neighbour = seed("#projects2 note")

        repo.rename("projects", "work")

        assertEquals("#work note", bodyOf(parent))
        assertEquals("#work/duck note", bodyOf(child))
        assertEquals("#projects2 note", bodyOf(neighbour))
        assertEquals(listOf("projects2", "work", "work/duck"), tagNames())
    }

    /** Delete is the exact name only — the descendant a user never named survives. */
    @Test
    fun removeTag_sparesDescendants() = runBlocking {
        val parent = seed("#projects note")
        val child = seed("#projects/duck note")

        repo.removeTag("projects")

        assertEquals("note", bodyOf(parent))
        assertEquals("#projects/duck note", bodyOf(child))
        assertEquals(listOf("projects/duck"), tagNames())
    }

    /**
     * Trashed notes are rewritten too.
     *
     * `notesByTagPathOnce` deliberately omits an `is_trashed` filter: a note restored from trash
     * three days after a rename would otherwise come back carrying a tag that no longer exists,
     * which is a corruption the user has no way to see coming. The DAO says so in a comment; this
     * is the assertion behind it.
     */
    @Test
    fun rename_includesTrashedNotes() = runBlocking {
        val live = seed("#projects note")
        val trashed = seed("#projects trashed", trashed = true)

        repo.rename("projects", "work")

        assertEquals("#work note", bodyOf(live))
        assertEquals("#work trashed", bodyOf(trashed))
    }

    /**
     * Customization survives a rename, and the target's own values win a merge.
     *
     * Bodies carry membership; they do not carry a pin or an icon. `carryCustomization` is the only
     * thing that moves those, and a merge is where its rule is actually load-bearing.
     */
    @Test
    fun rename_carriesPinAndIcon_butTargetWins() = runBlocking {
        seed("#from note")
        seed("#into note")
        val tags = db.tagDao()
        tags.upsert(tag("from").copy(isPinned = true, iconKey = "star"))
        tags.upsert(tag("into").copy(iconKey = "heart"))

        repo.rename("from", "into")

        val merged = tag("into")
        assertTrue("pin carried into an unset field", merged.isPinned)
        assertEquals("target's own icon is not overwritten", "heart", merged.iconKey)
        assertNull(tags.getByName("from"))
    }

    /**
     * Undo restores bodies, membership and customization together.
     *
     * Membership comes back for free because `saveNoteWithTagsAndLinks` re-derives `note_tags` from the
     * text — the property that makes undo honest after a *merge*, where no inverse rename could
     * separate the two tags' notes again. That is the case seeded here.
     */
    @Test
    fun restore_undoesAMerge() = runBlocking {
        val from = seed("#from note")
        val into = seed("#into other")
        db.tagDao().upsert(tag("from").copy(isPinned = true))

        val snapshot = repo.rename("from", "into")
        repo.restore(snapshot)

        assertEquals("#from note", bodyOf(from))
        assertEquals("#into other", bodyOf(into))
        assertEquals(listOf("from", "into"), tagNames())
        assertTrue("pin restored", tag("from").isPinned)
    }

    /**
     * **The other half of a merge undo, pinned rather than hidden. This test asserts a DEFECT.**
     *
     * Undoing a merge does not roll back the pin the target *inherited*. `carryCustomization:220`
     * writes `isPinned = current.isPinned || old.isPinned` onto the destination row, and `restore`
     * only walks `snapshot.tags` — which holds the **source** rows. `BodySnapshot` carries no record
     * of the target's prior state, so there is nothing to put back and the target stays pinned.
     *
     * Closing it needs a new snapshot field (the destination rows as they were before the merge), not
     * a change to either loop, which is why it is recorded rather than fixed here.
     *
     * **Cosmetic, not data-losing** — an extra row in the pinned section that the user can unpin. That
     * is the whole reason it is pinned instead of scheduled; it is a carried Phase 7 defect, not one
     * this pass introduced. Measured before it was written down: a temporary assertion for the
     * *correct* behaviour failed on device.
     *
     * **It goes red when the defect is fixed, and that is when to delete it.** Same shape as
     * `TagAgreementTest.aWikiLinkInsideCodeIsResolvedButNotPainted`. Do not "fix" a red here by
     * loosening the assertion.
     */
    @Test
    fun restore_ofAMergeLeavesTheTargetPinned() = runBlocking {
        seed("#from note")
        seed("#into other")
        db.tagDao().upsert(tag("from").copy(isPinned = true))
        assertTrue("precondition: the target is not pinned to begin with", !tag("into").isPinned)

        repo.restore(repo.rename("from", "into"))

        assertTrue("the source's own pin does come back", tag("from").isPinned)
        assertTrue(
            "the target kept the pin it inherited — if this is now false the defect is FIXED, so " +
                "delete this test",
            tag("into").isPinned,
        )
    }

    /**
     * A failure **inside** the operation, after N bodies have already been written.
     *
     * `deleteByName` runs last in both `rename` and `removeTag` — after `rewriteAll` has saved every
     * affected note — so a DAO that throws there lands the failure exactly where it matters: past
     * the point of no return if the writes were not transactional. Delegation rather than a seam in
     * production code, which `TagDao` being an `interface` makes free.
     *
     * **Checking the exception message is not ceremony.** The first run of this suite passed this
     * test for the wrong reason: `inTransaction` was `withTransaction`, which throws immediately on
     * a driver-backed database, so the operation died before writing anything and "nothing changed"
     * was trivially true. A rollback test that cannot tell *which* exception it caught is a test
     * that passes when the feature is entirely broken — so the message below is checked too.
     */
    @Test
    fun rename_rollsBackOnFailureMidOperation() = runBlocking {
        val parent = seed("#projects note")
        val child = seed("#projects/duck other")
        val failing = TagRepositoryImpl(
            db, ThrowingTagDao(db.tagDao()), db.noteDao(), DeriveTitle(), ParseTags(), ResolveWikiLinks(),
        )

        val thrown = runCatching { failing.rename("projects", "work") }.exceptionOrNull()

        assertEquals(
            "must fail on the forced error, not on a broken transaction API",
            "forced failure mid-operation",
            thrown?.message,
        )
        assertEquals("#projects note", bodyOf(parent))
        assertEquals("#projects/duck other", bodyOf(child))
        assertEquals(listOf("projects", "projects/duck"), tagNames())
    }

    /** Delete's half of the same claim — it destroys text, so rollback matters more here. */
    @Test
    fun delete_rollsBackOnFailureMidOperation() = runBlocking {
        val id = seed("#projects note")
        val failing = TagRepositoryImpl(
            db, ThrowingTagDao(db.tagDao()), db.noteDao(), DeriveTitle(), ParseTags(), ResolveWikiLinks(),
        )

        val thrown = runCatching { failing.removeTag("projects") }.exceptionOrNull()

        assertEquals(
            "must fail on the forced error, not on a broken transaction API",
            "forced failure mid-operation",
            thrown?.message,
        )
        assertEquals("#projects note", bodyOf(id))
        assertEquals(listOf("projects"), tagNames())
    }

    /** The phase's headline: `#work` → `#job` across 200 notes, one transaction, one surviving row. */
    @Test
    fun rename_across200NotesInOneTransaction() = runBlocking {
        val ids = (1..200).map { seed("note $it #work") }

        repo.rename("work", "job")

        assertEquals(200, ids.count { bodyOf(it)?.endsWith("#job") == true })
        assertEquals(listOf("job"), tagNames())
    }

    /**
     * Delete covers trashed notes too.
     *
     * Re-derived rather than inherited from rename: a tag the user *explicitly deleted* reappearing
     * months later when a note is restored is worse than a rename undoing itself, because there is
     * no second spelling to explain it.
     */
    @Test
    fun removeTag_includesTrashedNotes() = runBlocking {
        val live = seed("#work note")
        val trashed = seed("#work trashed", trashed = true)

        repo.removeTag("work")

        assertEquals("note", bodyOf(live))
        assertEquals("trashed", bodyOf(trashed))
        assertTrue("row gone", tagNames().isEmpty())
    }

    /**
     * Renaming a **pinned tag no note carries** moves the row rather than destroying it.
     *
     * This test previously asserted the opposite, under a KDoc claiming the loss was *"Recoverable
     * rather than lost… so undo restores it pinned."* Both halves were wrong: the row was destroyed,
     * and `restore` could not bring it back. The claim survived because the only assertion behind it
     * — `snapshot.tags.single().isPinned` — proves the snapshot *carries* the row, which is a
     * different statement from undo reinstating it. See
     * [rename_ofAPinnedTagNoNoteCarriesIsUndoable] for the assertion that now makes it.
     *
     * Bodies are still the source of truth; this is the one case where they cannot be. With no note
     * carrying `#unused`, `saveNoteWithTagsAndLinks` never mints a `renamed` row, so there is nothing for
     * `carryCustomization` to graft the pin onto. It now moves the old row instead — same id, new
     * name — which `deleteByName` then no longer matches. Such a row can only exist because
     * `deleteOrphanTags` spares pinned ones, and that exemption is what makes moving it the correct
     * answer rather than a special case: the row was deliberately kept alive without notes, so a
     * rename should carry it, not collect it.
     */
    @Test
    fun rename_ofAPinnedTagNoNoteCarriesMovesIt() = runBlocking {
        val id = seed("#projects note")
        db.tagDao().upsert(TagEntity(id = UUID.randomUUID().toString(), name = "unused", isPinned = true))

        val snapshot = repo.rename("unused", "renamed")

        assertEquals("#projects note", bodyOf(id))
        assertTrue("no bodies rewritten", snapshot.bodies.isEmpty())
        val moved = db.tagDao().getByName("renamed")
        assertNotNull("the row must move rather than be deleted", moved)
        assertTrue("...and keep its pin", moved?.isPinned == true)
        assertNull("nothing is left under the old name", db.tagDao().getByName("unused"))
        assertTrue("and the snapshot still carries the old row for undo", snapshot.tags.single().isPinned)
    }

    /**
     * **Undo actually puts the pinned row back** — the claim the old KDoc made and never asserted.
     *
     * The row goes back by the same mechanism it moved: `restore` finds nothing named `unused`, so
     * it upserts the snapshot's row whole, and since `upsert` is keyed on `id` that relocates the
     * moved row rather than adding a second one. The `createdNames` sweep that follows then finds no
     * `renamed` and is correctly a no-op — which is why the tags loop has to run before it.
     */
    @Test
    fun rename_ofAPinnedTagNoNoteCarriesIsUndoable() = runBlocking {
        seed("#projects note")
        db.tagDao().upsert(TagEntity(id = UUID.randomUUID().toString(), name = "unused", isPinned = true))

        val snapshot = repo.rename("unused", "renamed")
        repo.restore(snapshot)

        val back = db.tagDao().getByName("unused")
        assertNotNull("undo must put the pinned row back", back)
        assertTrue("...and put it back still pinned", back?.isPinned == true)
        assertNull("and must not leave the renamed row behind", db.tagDao().getByName("renamed"))
    }

    /**
     * The delete path's half of the same claim, which nothing covered.
     *
     * Deleting a pinned tag no note carries is correct — the user asked for it. Undoing it goes
     * through the same `restore` branch as rename's, and was broken in the same way.
     */
    @Test
    fun removeTag_ofAPinnedTagNoNoteCarriesIsUndoable() = runBlocking {
        seed("#projects note")
        db.tagDao().upsert(TagEntity(id = UUID.randomUUID().toString(), name = "unused", isPinned = true))

        val snapshot = repo.removeTag("unused")
        assertNull("precondition: the delete removed it", db.tagDao().getByName("unused"))

        repo.restore(snapshot)

        val back = db.tagDao().getByName("unused")
        assertNotNull("undo must put the pinned row back", back)
        assertTrue("...and put it back still pinned", back?.isPinned == true)
    }

    /**
     * Phase 7's collision merge crossed with the row-moving `@Upsert` above — **the two must not meet.**
     *
     * `tags.name` carries a UNIQUE index, and `@Upsert` resolves on the `id` primary key, so an upsert
     * that changed a row's name into one another row already held would raise
     * `SQLiteConstraintException` rather than merging. The move added for the pinned-orphan case
     * writes a *new* name, which is exactly the write that could hit it.
     *
     * It cannot, and the reason is worth stating because it is not obvious from the two sites being
     * far apart: the move is the `?:` branch of `getByName(newName)`, so it runs only when that name
     * was just observed free, and it writes precisely that name — same string, same collation (the
     * index is plain, not `NOCASE`), same `immediateTransaction`, no window between the read and the
     * write. A collision routes to the merge instead, which touches the target's own id and leaves its
     * name alone.
     *
     * Asserted rather than reasoned, on both directions, because a constraint violation here would be
     * a crash on a user's undo. The id assertions are what distinguish *merged* from *moved* — without
     * them a test that simply did not throw would pass whichever branch ran.
     */
    @Test
    fun rename_ofAPinnedOrphanOntoAnExistingTagMergesRatherThanColliding() = runBlocking {
        val note = seed("#into other")
        val target = tag("into").id
        val orphan = UUID.randomUUID().toString()
        db.tagDao().upsert(TagEntity(id = orphan, name = "unused", isPinned = true))

        val snapshot = repo.rename("unused", "into")

        val merged = tag("into")
        assertEquals("the target row survives — a merge, not a relocation", target, merged.id)
        assertTrue("and inherits the orphan's pin", merged.isPinned)
        assertNull("the orphan's own row is gone", db.tagDao().getByName("unused"))
        assertEquals("#into other", bodyOf(note))

        repo.restore(snapshot)

        val back = db.tagDao().getByName("unused")
        assertNotNull("undo puts the orphan back", back)
        assertEquals("...as the same row, not a duplicate", orphan, back?.id)
        assertEquals("and the target, which still has a note, survives the sweep", target, tag("into").id)
    }
}

/**
 * A real [TagDao] that fails on the one call both write paths make **last**.
 *
 * Everything else delegates, so the rewrites ahead of it are genuine writes against genuine SQLite —
 * which is the whole point: the rollback has to undo work that actually happened.
 */
private class ThrowingTagDao(real: TagDao) : TagDao by real {
    override suspend fun deleteByName(name: String) = error("forced failure mid-operation")
}
