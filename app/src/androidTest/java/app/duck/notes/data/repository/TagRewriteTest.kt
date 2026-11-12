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
import org.junit.Assert.fail
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
            .addCallback(DuckDatabaseCallback())
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

    private suspend fun tagRefs() = db.tagDao().observeNoteRefs().first().map { it.name to it.noteId }.toSet()

    private suspend fun tag(name: String) =
        checkNotNull(db.tagDao().getByName(name)) { "no tag row named $name" }

    /**
     * Plant UNDO_IGNORE_PERSISTED_CONFLICT: replace notes.getById(id)?.body == body with true
     * in TagRepositoryImpl.restore. A conflict on the last affected note must restore nothing.
     */
    @Test
    fun restore_rejectsChangedAffectedBodyBeforeAnyRowOrTagMutation() = runBlocking<Unit> {
        seed("First #work")
        seed("Second #work")
        db.tagDao().upsert(tag("work").copy(isPinned = true, iconKey = "star"))
        val snapshot = repo.rename("work", "job")
        assertEquals(2, snapshot.bodies.size)
        val expected = snapshot.bodies.keys.associateWith { requireNotNull(bodyOf(it)) }
        val changedId = snapshot.bodies.keys.last()
        val save = app.duck.notes.domain.usecase.SaveNote(
            NoteRepositoryImpl(db.noteDao()), DeriveTitle(), ParseTags(), ResolveWikiLinks(),
            app.duck.notes.domain.Clock { 99L },
        )
        val later = expected.getValue(changedId) + "\nNew saved text #later"
        save(changedId, later)
        val notesBefore = db.noteDao().observeNotes().first().sortedBy { it.id }
        val tagsBefore = db.tagDao().observeAll().first().sortedBy { it.id }
        val refsBefore = db.tagDao().observeNoteRefs().first().toSet()
        assertTrue("positive changed-body control", bodyOf(changedId) != expected[changedId])
        try {
            repo.restore(snapshot, expected)
            fail("a changed affected body must reject Undo")
        } catch (failure: IllegalStateException) {
            assertEquals("Note changed after the tag operation", failure.message)
        }
        assertEquals("no earlier note may be partially restored", notesBefore, db.noteDao().observeNotes().first().sortedBy { it.id })
        assertEquals("no customization may be partially restored", tagsBefore, db.tagDao().observeAll().first().sortedBy { it.id })
        assertEquals(refsBefore, db.tagDao().observeNoteRefs().first().toSet())
        assertEquals(later, bodyOf(changedId))

        // Positive restore control: the identical transaction succeeds when all bodies match.
        save(changedId, expected.getValue(changedId))
        repo.restore(snapshot, expected)
        snapshot.bodies.forEach { (id, body) -> assertEquals(body, bodyOf(id)) }
        assertEquals(listOf("work"), tagNames())
        assertTrue(tag("work").isPinned)
        assertEquals("star", tag("work").iconKey)
        assertEquals(snapshot.bodies.keys, db.tagDao().observeNoteRefs().first().map { it.noteId }.toSet())
    }

    /** A removed affected note is also a conflict; Undo must never partly restore or recreate it. */
    @Test
    fun restore_rejectsMissingAffectedNoteWithoutRestoringOtherNotes() = runBlocking<Unit> {
        seed("First #work")
        seed("Second #work")
        val snapshot = repo.removeTag("work")
        assertEquals(2, snapshot.bodies.size)
        val expected = snapshot.bodies.keys.associateWith { requireNotNull(bodyOf(it)) }
        val deletedId = snapshot.bodies.keys.last()
        db.noteDao().delete(requireNotNull(db.noteDao().getById(deletedId)))
        val before = db.noteDao().observeNotes().first()
        try {
            repo.restore(snapshot, expected)
            fail("a missing affected note must reject Undo")
        } catch (failure: IllegalStateException) {
            assertEquals("Note changed after the tag operation", failure.message)
        }
        assertEquals(before, db.noteDao().observeNotes().first())
        assertNull(db.noteDao().getById(deletedId))
        assertTrue(tagNames().isEmpty())
    }

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

    /** Plant TAG_RENAME_DELETE_AFTER_REWRITE: move source deletion after carryCustomization. */
    @Test
    fun rename_descendingOverlapPreservesMembershipCustomizationAndUndo() = runBlocking {
        val parent = seed("#work note")
        val child = seed("#work/sub child")
        db.tagDao().upsert(tag("work").copy(isPinned = true, iconKey = "star"))
        db.tagDao().upsert(tag("work/sub").copy(iconKey = "folder"))

        val snapshot = repo.rename("work", "work/sub")

        assertEquals("#work/sub note", bodyOf(parent))
        assertEquals("#work/sub/sub child", bodyOf(child))
        assertEquals(listOf("work/sub", "work/sub/sub"), tagNames())
        assertEquals(setOf("work/sub" to parent, "work/sub/sub" to child), tagRefs())
        assertTrue(tag("work/sub").isPinned)
        assertEquals("star", tag("work/sub").iconKey)
        assertEquals("folder", tag("work/sub/sub").iconKey)
        assertEquals(listOf("work/sub/sub"), snapshot.createdNames)

        repo.restore(snapshot, mapOf(parent to "#work/sub note", child to "#work/sub/sub child"))

        assertEquals("#work note", bodyOf(parent))
        assertEquals("#work/sub child", bodyOf(child))
        assertEquals(listOf("work", "work/sub"), tagNames())
        assertEquals(setOf("work" to parent, "work/sub" to child), tagRefs())
        assertTrue(tag("work").isPinned)
        assertEquals("star", tag("work").iconKey)
        assertEquals("folder", tag("work/sub").iconKey)
    }

    /** Opposite-direction control for TAG_RENAME_DELETE_AFTER_REWRITE. */
    @Test
    fun rename_ascendingOverlapPreservesMembershipCustomizationAndUndo() = runBlocking {
        val parent = seed("#work/sub note")
        val child = seed("#work/sub/sub child")
        db.tagDao().upsert(tag("work/sub").copy(isPinned = true, iconKey = "star"))
        db.tagDao().upsert(tag("work/sub/sub").copy(iconKey = "folder"))

        val snapshot = repo.rename("work/sub", "work")

        assertEquals("#work note", bodyOf(parent))
        assertEquals("#work/sub child", bodyOf(child))
        assertEquals(listOf("work", "work/sub"), tagNames())
        assertEquals(setOf("work" to parent, "work/sub" to child), tagRefs())
        assertTrue(tag("work").isPinned)
        assertEquals("star", tag("work").iconKey)
        assertEquals("folder", tag("work/sub").iconKey)
        assertEquals(listOf("work"), snapshot.createdNames)

        repo.restore(snapshot, mapOf(parent to "#work note", child to "#work/sub child"))

        assertEquals("#work/sub note", bodyOf(parent))
        assertEquals("#work/sub/sub child", bodyOf(child))
        assertEquals(listOf("work/sub", "work/sub/sub"), tagNames())
        assertEquals(setOf("work/sub" to parent, "work/sub/sub" to child), tagRefs())
        assertTrue(tag("work/sub").isPinned)
        assertEquals("star", tag("work/sub").iconKey)
        assertEquals("folder", tag("work/sub/sub").iconKey)
    }

    /** Plant TAG_UNDO_KEEP_SOURCE_ORPHANS: sweep only snapshot.createdNames before metadata. */
    @Test
    fun rename_descendingPinnedOrphansKeepSeparateMetadataAndUndo() = runBlocking {
        val untouched = seed("#keep note")
        val parent = TagEntity(UUID.randomUUID().toString(), "work", iconKey = "star", isPinned = true)
        val child = TagEntity(UUID.randomUUID().toString(), "work/sub", iconKey = "folder", isPinned = true)
        db.tagDao().upsert(parent)
        db.tagDao().upsert(child)

        val snapshot = repo.rename("work", "work/sub")

        assertTrue(snapshot.bodies.isEmpty())
        assertEquals(listOf("work/sub/sub"), snapshot.createdNames)
        assertEquals(parent.copy(name = "work/sub"), tag("work/sub"))
        assertEquals(child.copy(name = "work/sub/sub"), tag("work/sub/sub"))
        assertEquals(listOf("keep", "work/sub", "work/sub/sub"), tagNames())
        assertEquals(setOf("keep" to untouched), tagRefs())

        repo.restore(snapshot, emptyMap())

        assertEquals(parent, tag("work"))
        assertEquals(child, tag("work/sub"))
        assertEquals(listOf("keep", "work", "work/sub"), tagNames())
        assertEquals("#keep note", bodyOf(untouched))
        assertEquals(setOf("keep" to untouched), tagRefs())
    }

    /** Opposite-direction control for TAG_UNDO_KEEP_SOURCE_ORPHANS. */
    @Test
    fun rename_ascendingPinnedOrphansKeepSeparateMetadataAndUndo() = runBlocking {
        val untouched = seed("#keep note")
        val parent = TagEntity(UUID.randomUUID().toString(), "work/sub", iconKey = "star", isPinned = true)
        val child = TagEntity(UUID.randomUUID().toString(), "work/sub/sub", iconKey = "folder", isPinned = true)
        db.tagDao().upsert(parent)
        db.tagDao().upsert(child)

        val snapshot = repo.rename("work/sub", "work")

        assertTrue(snapshot.bodies.isEmpty())
        assertEquals(listOf("work"), snapshot.createdNames)
        assertEquals(parent.copy(name = "work"), tag("work"))
        assertEquals(child.copy(name = "work/sub"), tag("work/sub"))
        assertEquals(listOf("keep", "work", "work/sub"), tagNames())
        assertEquals(setOf("keep" to untouched), tagRefs())

        repo.restore(snapshot, emptyMap())

        assertEquals(parent, tag("work/sub"))
        assertEquals(child, tag("work/sub/sub"))
        assertEquals(listOf("keep", "work/sub", "work/sub/sub"), tagNames())
        assertEquals("#keep note", bodyOf(untouched))
        assertEquals(setOf("keep" to untouched), tagRefs())
    }

    /** Plant TAG_UNDO_REUSE_OCCUPIED_ID: set restoredId to old.id unconditionally. */
    @Test
    fun rename_descendingMixedOverlapUndoDoesNotRelocateReferencedSourceId() = runBlocking {
        val child = seed("#work/sub child")
        db.tagDao().upsert(tag("work/sub").copy(iconKey = "folder"))
        db.tagDao().upsert(TagEntity(UUID.randomUUID().toString(), "work", iconKey = "star", isPinned = true))

        val snapshot = repo.rename("work", "work/sub")

        assertEquals("#work/sub/sub child", bodyOf(child))
        assertEquals(setOf("work/sub/sub" to child), tagRefs())
        assertTrue(tag("work/sub").isPinned)
        assertEquals("star", tag("work/sub").iconKey)
        assertEquals("folder", tag("work/sub/sub").iconKey)

        repo.restore(snapshot, mapOf(child to "#work/sub/sub child"))

        assertEquals("#work/sub child", bodyOf(child))
        assertEquals(listOf("work", "work/sub"), tagNames())
        assertEquals(setOf("work/sub" to child), tagRefs())
        assertTrue(tag("work").isPinned)
        assertEquals("star", tag("work").iconKey)
        assertEquals("folder", tag("work/sub").iconKey)
    }

    /** Opposite-direction control for TAG_UNDO_REUSE_OCCUPIED_ID. */
    @Test
    fun rename_ascendingMixedOverlapUndoDoesNotRelocateReferencedSourceId() = runBlocking {
        val parent = seed("#work/sub note")
        db.tagDao().upsert(tag("work/sub").copy(iconKey = "star"))
        db.tagDao().upsert(TagEntity(UUID.randomUUID().toString(), "work/sub/sub", iconKey = "folder", isPinned = true))

        val snapshot = repo.rename("work/sub", "work")

        assertEquals("#work note", bodyOf(parent))
        assertEquals(setOf("work" to parent), tagRefs())
        assertEquals("star", tag("work").iconKey)
        assertTrue(tag("work/sub").isPinned)
        assertEquals("folder", tag("work/sub").iconKey)

        repo.restore(snapshot, mapOf(parent to "#work note"))

        assertEquals("#work/sub note", bodyOf(parent))
        assertEquals(listOf("work/sub", "work/sub/sub"), tagNames())
        assertEquals(setOf("work/sub" to parent), tagRefs())
        assertEquals("star", tag("work/sub").iconKey)
        assertTrue(tag("work/sub/sub").isPinned)
        assertEquals("folder", tag("work/sub/sub").iconKey)
    }

    /** Plant TAG_UNDO_ALL_TARGETS_CREATED: replace the newNames absence filter with newNames. */
    @Test
    fun rename_existingPinnedOrphanTargetKeepsItsRowAndMetadataAfterUndo() = runBlocking {
        val source = seed("#from note")
        db.tagDao().upsert(tag("from").copy(iconKey = "star", isPinned = true))
        val target = TagEntity(UUID.randomUUID().toString(), "into", iconKey = "heart", isPinned = true)
        db.tagDao().upsert(target)

        val snapshot = repo.rename("from", "into")

        assertEquals("#into note", bodyOf(source))
        assertEquals(setOf("into" to source), tagRefs())
        assertEquals(target, tag("into"))
        assertTrue("an existing target was not created by rename", snapshot.createdNames.isEmpty())

        repo.restore(snapshot, mapOf(source to "#into note"))

        assertEquals("#from note", bodyOf(source))
        assertEquals(setOf("from" to source), tagRefs())
        assertEquals(listOf("from", "into"), tagNames())
        assertEquals(target, tag("into"))
        assertTrue(tag("from").isPinned)
        assertEquals("star", tag("from").iconKey)
    }

    /** Plant TAG_RENAME_IDENTICAL_DELETES: remove rename's from == to early return. */
    @Test
    fun rename_identicalPathLeavesBodiesRowsAndMembershipUntouched() = runBlocking {
        val note = seed("#work note")
        db.tagDao().upsert(tag("work").copy(iconKey = "star", isPinned = true))
        val beforeNote = db.noteDao().getById(note)
        val beforeTag = tag("work")

        val snapshot = repo.rename("work", "work")

        assertEquals(beforeNote, db.noteDao().getById(note))
        assertEquals(beforeTag, tag("work"))
        assertEquals(setOf("work" to note), tagRefs())
        assertTrue(snapshot.bodies.isEmpty())
        assertTrue(snapshot.tags.isEmpty())
        assertTrue(snapshot.createdNames.isEmpty())
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
     * Undoing a merge does not roll back the pin the target *inherited*. `carryCustomization`
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
     * Plant TAG_RENAME_NO_TRANSACTION: replace inTransaction's connection transaction with block().
     * The failing carry first proves that actual body and membership writes already occurred.
     */
    @Test
    fun rename_rollsBackOnFailureMidOperation() = runBlocking {
        val parent = seed("#projects note")
        val child = seed("#projects/duck other")
        db.tagDao().upsert(tag("projects").copy(isPinned = true, iconKey = "star"))
        val beforeNotes = listOf(db.noteDao().getById(parent), db.noteDao().getById(child))
        val beforeTags = db.tagDao().observeAll().first()
        val beforeRefs = tagRefs()
        var observedWrites = false
        val failingTags = object : TagDao by db.tagDao() {
            override suspend fun upsert(tag: TagEntity) {
                assertEquals("#work note", bodyOf(parent))
                assertEquals("#work/duck other", bodyOf(child))
                assertEquals(setOf(parent), db.noteDao().notesByTagNameOnce("work").map { it.id }.toSet())
                assertEquals(setOf(child), db.noteDao().notesByTagNameOnce("work/duck").map { it.id }.toSet())
                observedWrites = true
                error("forced failure after real rename writes")
            }
        }
        val failing = TagRepositoryImpl(
            db, failingTags, db.noteDao(), DeriveTitle(), ParseTags(), ResolveWikiLinks(),
        )

        val thrown = runCatching { failing.rename("projects", "work") }.exceptionOrNull()

        assertEquals("forced failure after real rename writes", thrown?.message)
        assertTrue("the failure must follow actual body and membership writes", observedWrites)
        assertEquals(beforeNotes, listOf(db.noteDao().getById(parent), db.noteDao().getById(child)))
        assertEquals(beforeTags, db.tagDao().observeAll().first())
        assertEquals(beforeRefs, tagRefs())
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
     * `carryCustomization` to graft the pin onto. It recreates the deleted source row under the new
     * name, keeping its ID and customization. Such a row can only exist because
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
     * Undo removes the orphan destination before restoring the saved source row and its ID.
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
 * Delete's [TagDao] fails on its final call, after actual body writes.
 *
 * Everything else delegates, so the rewrites ahead of it are genuine writes against genuine SQLite —
 * which is the whole point: the rollback has to undo work that actually happened.
 */
private class ThrowingTagDao(real: TagDao) : TagDao by real {
    override suspend fun deleteByName(name: String) = error("forced failure mid-operation")
}
