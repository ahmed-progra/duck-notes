package app.duck.notes.presentation.shell

import android.content.Context
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.DuckDatabaseCallback
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.repository.NoteRepositoryImpl
import app.duck.notes.data.repository.TagRepositoryImpl
import app.duck.notes.domain.Clock
import app.duck.notes.domain.usecase.AddTagToNote
import app.duck.notes.domain.usecase.DeleteTag
import app.duck.notes.domain.usecase.DeriveTitle
import app.duck.notes.domain.usecase.ObserveNote
import app.duck.notes.domain.usecase.ObserveNotes
import app.duck.notes.domain.usecase.ObserveTagCounts
import app.duck.notes.domain.usecase.ObserveTagTree
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.PinTag
import app.duck.notes.domain.usecase.RenameTag
import app.duck.notes.domain.usecase.SaveNote
import app.duck.notes.domain.usecase.SetTagIcon
import app.duck.notes.domain.usecase.UndoTagChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typing `#work` one character at a time mints `w`, `wo`, `wor` as real tags. **Pinned as current
 * behaviour, pending a decision** — this asserts the mechanism, not a fix.
 *
 * ## The mechanism, measured rather than assumed
 *
 * The report looked contradictory: the sidebar shows the partials, while Database Inspector shows a
 * clean four-row `tags` table with no orphans. Reading both sides at the same instant resolves it —
 * they were never in contradiction, they are the same system at different times.
 *
 * | after | `tags` table | sidebar |
 * |---|---|---|
 * | `#w` | `[w]` | `[w]` |
 * | `#wo` | `[wo]` | `[wo]` |
 * | `#wor` | `[wor]` | `[wor]` |
 * | `#work` | `[work]` | `[work]` |
 *
 * **The table holds exactly one tag at every step and never accumulates.** `#w` is a *complete, valid
 * tag* to `TagScanner` — nothing about it is "in progress" from the parser's point of view — so every
 * autosave that lands mid-word mints a real row and a real `note_tags` ref. The next save calls
 * `clearCrossRefs`, the previous partial loses its only reference, and `deleteOrphanTags` removes it.
 *
 * So: **nothing is reaping incorrectly and there are no orphans to reap.** `deleteOrphanTags` is
 * working exactly as designed, and the sidebar is reading the `tags` table
 * (`ObserveTagTree` → `TagRepository.observeAll` → `TagDao.observeAll`) and nothing else. What looks
 * like accumulation is the sidebar faithfully rendering a *sequence* of momentarily-correct states.
 *
 * An earlier run of this test showed the sidebar one step behind the table. That is Room invalidation
 * propagation latency, not a defect: with a longer settle the two agree at every step. Recorded
 * because a one-step lag looks exactly like a stale-cache bug.
 *
 * **Phase 8a introduced this.** Before autosave the editor never wrote, so a partially typed tag never
 * reached `saveNoteWithTags` at all. Any save landing mid-word necessarily parses the prefix.
 */
@RunWith(AndroidJUnit4::class)
class PartialTagMintingTest {

    private lateinit var db: DuckDatabase
    private val parseTags = ParseTags()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DuckDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .addCallback(DuckDatabaseCallback(seedSampleNotes = false))
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun shell(): DuckShellViewModel {
        val notes = NoteRepositoryImpl(db.noteDao())
        val tags = TagRepositoryImpl(db, db.tagDao(), db.noteDao(), DeriveTitle(), parseTags)
        val saveNote = SaveNote(notes, DeriveTitle(), parseTags, Clock { System.currentTimeMillis() })
        return DuckShellViewModel(
            handle = SavedStateHandle(),
            observeNotes = ObserveNotes(notes),
            observeNote = ObserveNote(notes),
            saveNote = saveNote,
            observeTagTree = ObserveTagTree(tags),
            observeTagCounts = ObserveTagCounts(tags),
            parseTags = parseTags,
            pinTag = PinTag(tags),
            renameTag = RenameTag(tags),
            deleteTag = DeleteTag(tags),
            setTagIcon = SetTagIcon(tags),
            addTagToNote = AddTagToNote(notes, parseTags, saveNote),
            undoTagChange = UndoTagChange(tags),
            clock = Clock { System.currentTimeMillis() },
        )
    }

    private suspend fun tableRows() = db.tagDao().observeAll().first().map { it.name }.sorted()

    private suspend fun refCounts(): Map<String, Int> =
        db.tagDao().observeNoteRefs().first().groupingBy { it.name }.eachCount()

    @Test
    fun typingATagOneCharacterAtATimeWithAPauseBetweenEach() = runBlocking {
        db.noteDao().saveNoteWithTags(
            NoteEntity(id = "n1", title = "n", body = "", createdAt = 0L, modifiedAt = 0L),
            emptyList(),
        )

        val vm = withContext(Dispatchers.Main) { shell() }
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { vm.state.collect {} }
        withContext(Dispatchers.Main) { vm.onSelectNote("n1") }
        delay(500)

        // One character at a time, each followed by a pause well past the 400ms idle window, so
        // EVERY prefix gets its own save. This is the shape no existing test has.
        "#work".forEachIndexed { i, _ ->
            val prefix = "#work".substring(0, i + 1)
            withContext(Dispatchers.Main) {
                vm.buffer.setTextAndPlaceCursorAtEnd(prefix)
                Snapshot.sendApplyNotifications()
            }
            // Past the idle window AND past Room's invalidation propagation, so table and sidebar
            // are being compared at a settled instant rather than mid-flight.
            delay(DuckShellViewModel.IDLE_MS + 2_000)

            val table = tableRows()
            val sidebar = vm.state.value.tagRows.map { it.path }.sorted()
            println("PARTIAL after '$prefix'  table=$table  refs=${refCounts()}  sidebar=$sidebar")

            // The partial IS minted — that is the defect, pinned.
            val expected = if (prefix == "#") emptyList() else listOf(prefix.removePrefix("#"))
            assertEquals("the tags table at '$prefix'", expected, table)
            // ...and it never accumulates: exactly one row, so nothing is failing to be reaped.
            assertTrue("the table accumulated partials: $table", table.size <= 1)
            // ...and the sidebar shows the table, not something else.
            assertEquals("sidebar disagrees with the tags table at '$prefix'", table, sidebar)
        }

        delay(1_000)
        assertEquals("the resting state must be clean", listOf("work"), tableRows())
        assertEquals(mapOf("work" to 1), refCounts())
        scope.cancel()
    }
}
