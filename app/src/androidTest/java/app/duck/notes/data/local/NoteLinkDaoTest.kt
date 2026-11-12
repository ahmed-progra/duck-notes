package app.duck.notes.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.local.dao.NoteDao
import app.duck.notes.data.local.dao.NoteLinkDao
import app.duck.notes.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * `note_links` against a real database — Phase 9b.
 *
 * The JVM suite pins what `SaveNote` *hands* the write path; everything below is what only real rows
 * can answer: title resolution, unresolved references, live target changes, and both trash filters.
 *
 * Room is built exactly as `DuckDatabaseTest` builds it, `BundledSQLiteDriver` included: the system
 * SQLite may lack FTS5, which `notes_fts` requires, and this database declares it.
 */
@RunWith(AndroidJUnit4::class)
class NoteLinkDaoTest {

    private lateinit var db: DuckDatabase
    private lateinit var notes: NoteDao
    private lateinit var links: NoteLinkDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DuckDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .addCallback(DuckDatabaseCallback())
            .build()
        notes = db.noteDao()
        links = db.noteLinkDao()
    }

    @After
    fun tearDown() = db.close()

    private fun note(
        id: String,
        title: String,
        modifiedAt: Long = 0L,
        trashed: Boolean = false,
    ) = NoteEntity(
        id = id,
        title = title,
        body = "body of $title",
        createdAt = 0L,
        modifiedAt = modifiedAt,
        isTrashed = trashed,
    )

    private suspend fun save(note: NoteEntity, linkTitles: List<String> = emptyList()) =
        notes.saveNoteWithTagsAndLinks(note, emptyList(), linkTitles)

    private suspend fun backlinkSourceIds(targetId: String) =
        links.observeBacklinks(targetId).first().map { it.sourceId }

    @Test
    fun aResolvedTitleBecomesARowTheTargetCanSee() = runBlocking {
        save(note("target", "Pond"))
        save(note("source", "Journal"), listOf("Pond"))

        assertEquals(listOf("source"), backlinkSourceIds("target"))
    }

    /**
     * The panel renders a title, so the query has to join one — and it must be the source's *current*
     * title, not the text inside the brackets.
     */
    @Test
    fun theRowCarriesTheSourcesTitleAndTheTypedText() = runBlocking {
        save(note("target", "Pond"))
        save(note("source", "Journal"), listOf("Pond"))

        val row = links.observeBacklinks("target").first().single()
        assertEquals("Journal", row.sourceTitle)
        assertEquals("Pond", row.linkText)
    }

    /** Plant LINK_DROP_UNRESOLVED: skip inserting a reference whose title has no target yet. */
    @Test
    fun anUnresolvedReferenceResolvesWhenItsTargetArrives() = runBlocking {
        save(note("source", "Journal"), listOf("Nothing Called This"))
        assertEquals(emptyList<String>(), backlinkSourceIds("target"))
        save(note("target", "Nothing Called This"))
        assertEquals(listOf("source"), backlinkSourceIds("target"))
    }

    /**
     * **Titles are not unique, so `LIMIT 1` needs its `ORDER BY` to be a decision.**
     *
     * Two notes can both derive the title `Pond`. Without the sort SQLite returns whichever row it
     * reaches first — stable today, and free to change after a `VACUUM` or an index addition, which
     * would silently re-point an existing link at a different note. Most recently modified is the
     * deliberate answer. Dropping `ORDER BY modified_at DESC` from `noteIdByTitle` is the plant.
     */
    @Test
    fun aDuplicateTitleResolvesToTheMostRecentlyModifiedNote() = runBlocking {
        save(note("older", "Pond", modifiedAt = 1_000L))
        save(note("newer", "Pond", modifiedAt = 9_000L))
        save(note("source", "Journal"), listOf("Pond"))

        assertEquals(emptyList<String>(), backlinkSourceIds("older"))
        assertEquals(listOf("source"), backlinkSourceIds("newer"))
    }

    /** A repeated title is one row, not a constraint failure — the composite PK plus IGNORE. */
    @Test
    fun theSameTitleTwiceInOneBodyIsOneRow() = runBlocking {
        save(note("target", "Pond"))
        save(note("source", "Journal"), listOf("Pond", "Pond"))

        assertEquals(listOf("source"), backlinkSourceIds("target"))
    }

    @Test
    fun rewritingABodyWithoutTheLinkDropsTheRow() = runBlocking {
        save(note("target", "Pond"))
        save(note("source", "Journal"), listOf("Pond"))
        assertEquals(listOf("source"), backlinkSourceIds("target"))

        save(note("source", "Journal"), emptyList())

        assertEquals(emptyList<String>(), backlinkSourceIds("target"))
    }

    /**
     * A save clears **this** note's links and no one else's.
     *
     * `clearLinksFrom` is scoped by `source_id`; an unscoped `DELETE FROM note_links` would pass every
     * single-source test above and destroy the whole table on the second note's save. That is the
     * plant, and it is the reason this test seeds two independent sources.
     */
    @Test
    fun aSaveRewritesOnlyItsOwnRows() = runBlocking {
        save(note("target", "Pond"))
        save(note("first", "First"), listOf("Pond"))
        save(note("second", "Second"), listOf("Pond"))

        // Re-saving `second` must leave `first`'s row alone.
        save(note("second", "Second"), listOf("Pond"))

        assertEquals(listOf("first", "second"), backlinkSourceIds("target").sorted())
    }

    // --- trash ---------------------------------------------------------------

    /**
     * The filter is on the **source**: a note in the bin does not appear in anyone's backlinks.
     *
     * Dropping `AND n.is_trashed = 0` from `observeBacklinks` is the plant.
     */
    @Test
    fun backlinksExcludeTrashedSources() = runBlocking {
        save(note("target", "Pond"))
        save(note("live", "Live"), listOf("Pond"))
        save(note("binned", "Binned"), listOf("Pond"))
        assertEquals(listOf("binned", "live"), backlinkSourceIds("target").sorted())

        // Trash `binned` the way `DeleteNote` does — same body, so the same links are rewritten.
        save(note("binned", "Binned", trashed = true), listOf("Pond"))

        assertEquals(listOf("live"), backlinkSourceIds("target"))
    }

    /**
     * **Trash hides rows; it does not delete them.** The proof is that untrashing restores the
     * backlink without anything having re-resolved a title.
     *
     * This is the behaviour `NoteDao.notesByTagPathOnce` already argued for tags — *"The filter must
     * hide trashed notes; the rewrite must not"* — and the reason `NoteRepository.save` refuses to
     * default `linkTitles`: a `DeleteNote` that passed nothing would delete these rows instead, and
     * every assertion above would still pass.
     */
    @Test
    fun trashingAndRestoringLeavesTheRowsIntact() = runBlocking {
        save(note("target", "Pond"))
        save(note("binned", "Binned"), listOf("Pond"))

        save(note("binned", "Binned", trashed = true), listOf("Pond"))
        assertEquals(emptyList<String>(), backlinkSourceIds("target"))

        save(note("binned", "Binned", trashed = false), listOf("Pond"))

        assertEquals(
            "the row survived the trash; restoring only stopped hiding it",
            listOf("binned"),
            backlinkSourceIds("target"),
        )
    }

    /** Plant LINK_TARGET_TRASH_VISIBLE: remove t.is_trashed = 0 from the live backlink query. */
    @Test
    fun targetTrashAndRestoreChangeResolutionWithoutSavingTheSource() = runBlocking {
        save(note("target", "Pond", trashed = false))
        save(note("source", "Journal"), listOf("Pond"))
        assertEquals(listOf("source"), backlinkSourceIds("target"))
        save(note("target", "Pond", trashed = true))
        assertEquals(emptyList<String>(), backlinkSourceIds("target"))
        assertEquals(null, notes.noteIdByTitle("Pond"))
        save(note("target", "Pond", trashed = false))
        assertEquals(listOf("source"), backlinkSourceIds("target"))
    }

    @Test fun targetRenameAndReplacementNeverRewriteTheSource() = runBlocking {
        save(note("target", "Pond"))
        save(note("source", "Journal"), listOf("Pond"))
        val source = notes.getById("source")
        assertEquals(listOf("source"), backlinkSourceIds("target"))
        save(note("target", "Lake"))
        assertEquals(emptyList<String>(), backlinkSourceIds("target"))
        save(note("replacement", "Pond"))
        assertEquals(listOf("source"), backlinkSourceIds("replacement"))
        assertEquals(source, notes.getById("source"))
    }

    /** Plant LINK_FALLBACK_FIRST: reverse COALESCE operands in observeBacklinks. */
    @Test fun literalPrecedesFallbackAndBothAgreeWithTapResolution() = runBlocking {
        save(note("derived", "Pond", modifiedAt = 9_000L))
        save(note("source", "Journal"), listOf("**Pond**"))
        assertEquals("derived", notes.noteIdByTitle(" **Pond** "))
        assertEquals(listOf("source"), backlinkSourceIds("derived"))
        save(note("literal", "**Pond**", modifiedAt = 1L).copy(isArchived = true))
        assertEquals("literal", notes.noteIdByTitle(" **Pond** "))
        assertEquals(emptyList<String>(), backlinkSourceIds("derived"))
        assertEquals(listOf("source"), backlinkSourceIds("literal"))
        save(note("literal", "**Pond**", trashed = true))
        assertEquals("derived", notes.noteIdByTitle("**Pond**"))
        assertEquals(listOf("source"), backlinkSourceIds("derived"))
    }

    /** Plant LINK_TIE_BREAK_REVERSED: change id ASC to id DESC in the resolution queries. */
    @Test fun duplicateWinnerTracksModificationAndStableIdWithoutSourceSave() = runBlocking {
        save(note("z", "Pond", modifiedAt = 1L))
        save(note("a", "Pond", modifiedAt = 1L))
        save(note("source", "Journal"), listOf("Pond"))
        assertEquals("a", notes.noteIdByTitle("Pond"))
        assertEquals(listOf("source"), backlinkSourceIds("a"))
        assertEquals(emptyList<String>(), backlinkSourceIds("z"))
        save(note("z", "Pond", modifiedAt = 2L))
        assertEquals("z", notes.noteIdByTitle("Pond"))
        assertEquals(listOf("source"), backlinkSourceIds("z"))
        assertEquals(emptyList<String>(), backlinkSourceIds("a"))
        assertEquals(3, notes.count())
    }

    /** Nothing links here — the empty state the panel renders, not an error. */
    @Test
    fun aNoteNothingLinksToHasNoBacklinks() = runBlocking {
        save(note(UUID.randomUUID().toString(), "Lonely"))

        assertEquals(emptyList<String>(), backlinkSourceIds("lonely"))
    }

    // --- autocomplete candidates (9c) ----------------------------------------
    //
    // `observeTitles` lives on NoteDao rather than NoteLinkDao, but it is the `[[title]]` picker's
    // query and its answers have to agree with `noteIdByTitle`'s — trashed excluded, most recently
    // modified wins — so it is tested beside the resolution it feeds rather than among the note
    // queries it merely resembles.

    private suspend fun titles(excludeId: String? = null) = notes.observeTitles(excludeId).first()

    /**
     * **The order is a decision, not an accident.** `notes.title` carries no index, so without the
     * `ORDER BY` SQLite returns rows however it reaches them — stable today and free to change after
     * a `VACUUM`, which would silently reshuffle the picker. Most recently modified first is
     * `noteIdByTitle`'s own rule, so the title the picker puts at the top is the note an exact
     * resolution would pick. Dropping `ORDER BY modified_at DESC` is the plant.
     */
    @Test
    fun titlesAreOfferedMostRecentlyModifiedFirst() = runBlocking {
        save(note("old", "Oldest", modifiedAt = 1_000L))
        save(note("mid", "Middle", modifiedAt = 5_000L))
        save(note("new", "Newest", modifiedAt = 9_000L))

        assertEquals(listOf("Newest", "Middle", "Oldest"), titles())
    }

    /**
     * The note being edited does not offer itself.
     *
     * A self-link would put the note into its own backlinks panel — the one 9b built — which is noise
     * rather than information. Dropping the `:excludeId` predicate is the plant.
     */
    @Test
    fun theEditedNoteDoesNotOfferItself() = runBlocking {
        save(note("self", "Journal"))
        save(note("other", "Pond"))

        assertEquals(listOf("Pond"), titles(excludeId = "self"))
        assertEquals(
            "a null exclusion offers everything",
            listOf("Journal", "Pond").sorted(),
            titles().sorted(),
        )
    }

    /**
     * Trashed titles are not offered, matching `noteIdByTitle`.
     *
     * Offering one would complete to a title that resolves to nothing, so the link would be painted
     * and would never become a `note_links` row — a completion that silently does not work.
     */
    @Test
    fun trashedTitlesAreNotOffered() = runBlocking {
        save(note("live", "Pond"))
        save(note("binned", "Binned", trashed = true))

        assertEquals(listOf("Pond"), titles())
    }

    /** Two notes may share a title; the query returns both and `suggest` dedupes for display. */
    @Test
    fun aDuplicateTitleIsReturnedOncePerNote() = runBlocking {
        save(note("a", "Pond", modifiedAt = 1_000L))
        save(note("b", "Pond", modifiedAt = 2_000L))

        assertEquals(listOf("Pond", "Pond"), titles())
    }
}
