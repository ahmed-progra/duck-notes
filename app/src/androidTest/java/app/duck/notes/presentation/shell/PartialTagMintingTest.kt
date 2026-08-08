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
import app.duck.notes.data.repository.AttachmentRepositoryImpl
import app.duck.notes.data.repository.AttachmentStorageImpl
import app.duck.notes.data.repository.NoteRepositoryImpl
import app.duck.notes.data.repository.TagRepositoryImpl
import app.duck.notes.domain.Clock
import app.duck.notes.domain.usecase.AddTagToNote
import app.duck.notes.domain.usecase.AttachImage
import app.duck.notes.domain.usecase.DeleteAttachment
import app.duck.notes.domain.usecase.DeleteTag
import app.duck.notes.domain.usecase.DeriveTitle
import app.duck.notes.domain.usecase.ObserveAttachments
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typing `#work` one character at a time no longer mints `w`, `wo`, `wor` as real tags — on a real
 * database, with the real `ensureTag` and the real transaction.
 *
 * ## The mechanism this fix is aimed at, measured rather than assumed
 *
 * The report looked contradictory: the sidebar showed the partials, while Database Inspector showed a
 * clean four-row `tags` table with no orphans. Reading both sides at the same instant resolved it —
 * they were never in contradiction, they were the same system at different times. Before the fix:
 *
 * | after | `tags` table | sidebar |
 * |---|---|---|
 * | `#w` | `[w]` | `[w]` |
 * | `#wo` | `[wo]` | `[wo]` |
 * | `#wor` | `[wor]` | `[wor]` |
 * | `#work` | `[work]` | `[work]` |
 *
 * **The table held exactly one tag at every step and never accumulated.** `#w` is a *complete, valid
 * tag* to `TagScanner` — nothing about it is "in progress" from the parser's point of view — so every
 * autosave that landed mid-word minted a real row and a real `note_tags` ref. The next save called
 * `clearCrossRefs`, the previous partial lost its only reference, and `deleteOrphanTags` removed it.
 *
 * So: **nothing was reaping incorrectly and there were no orphans to reap.** `deleteOrphanTags` works
 * exactly as designed, and the sidebar reads the `tags` table
 * (`ObserveTagTree` → `TagRepository.observeAll` → `TagDao.observeAll`) and nothing else. What looked
 * like accumulation was the sidebar faithfully rendering a *sequence* of momentarily-correct states.
 *
 * That is why the fix is neither in the reaping nor in the sidebar nor in the scanner: what the parser
 * lacked was **the caret**. A span whose `end` is the caret may still grow by one more character. See
 * `ParseTags`.
 *
 * An earlier run of this test showed the sidebar one step behind the table. That is Room invalidation
 * propagation latency, not a defect: with a longer settle the two agree at every step. Recorded
 * because a one-step lag looks exactly like a stale-cache bug.
 *
 * **Phase 8a introduced the defect.** Before autosave the editor never wrote, so a partially typed tag
 * never reached `saveNoteWithTags` at all.
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
        val attachments = AttachmentRepositoryImpl(db.attachmentDao())
        val attachStorage = AttachmentStorageImpl(ApplicationProvider.getApplicationContext())
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
            attachImage = AttachImage(attachStorage, attachments, notes, saveNote, Clock { System.currentTimeMillis() }),
            deleteAttachment = DeleteAttachment(attachStorage, attachments, notes, saveNote),
            observeAttachments = ObserveAttachments(attachments),
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

            // Nothing is minted while the caret sits at the end of the tag — including `#work`
            // itself, which is indistinguishable from a prefix until the caret moves on.
            assertEquals("a partial tag reached the database at '$prefix'", emptyList<String>(), table)
            // ...and the sidebar shows the table, not something else. This is the half that made the
            // report look self-contradictory, so it stays asserted.
            assertEquals("sidebar disagrees with the tags table at '$prefix'", table, sidebar)

            // The body is durable the whole way. Suppression drops the tag, never the text.
            assertEquals("the body at '$prefix'", prefix, db.noteDao().getById("n1")?.body)
        }

        // One more character finishes the tag: the span no longer ends at the caret.
        withContext(Dispatchers.Main) {
            vm.buffer.setTextAndPlaceCursorAtEnd("#work ")
            Snapshot.sendApplyNotifications()
        }
        delay(DuckShellViewModel.IDLE_MS + 2_000)

        assertEquals("the finished tag must be minted", listOf("work"), tableRows())
        assertEquals(mapOf("work" to 1), refCounts())
        scope.cancel()
    }

    /**
     * The other release valve: leaving the note mints the tag even though the caret never moved.
     *
     * `flush` suppresses nothing, and the text has not changed since the suppressed write — so this
     * is the path that only reaches the database because the write carries an explicit debt.
     */
    @Test
    fun aTagLeftUnderTheCaretIsMintedWhenTheAppStops() = runBlocking {
        db.noteDao().saveNoteWithTags(
            NoteEntity(id = "n1", title = "n", body = "", createdAt = 0L, modifiedAt = 0L),
            emptyList(),
        )

        val vm = withContext(Dispatchers.Main) { shell() }
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { vm.state.collect {} }
        withContext(Dispatchers.Main) { vm.onSelectNote("n1") }
        delay(500)

        withContext(Dispatchers.Main) {
            vm.buffer.setTextAndPlaceCursorAtEnd("#work")
            Snapshot.sendApplyNotifications()
        }
        delay(DuckShellViewModel.IDLE_MS + 2_000)
        assertEquals("precondition: the autosave suppressed it", emptyList<String>(), tableRows())
        val beforeStop = db.noteDao().getById("n1")?.modifiedAt

        withContext(Dispatchers.Main) { vm.flush() }
        delay(1_000)

        assertEquals("ON_STOP must mint the tag", listOf("work"), tableRows())
        assertEquals(mapOf("work" to 1), refCounts())
        // ...and must not age the note: those keystrokes were stamped by the write that hid the tag.
        assertEquals("the tag completion restamped", beforeStop, db.noteDao().getById("n1")?.modifiedAt)
        scope.cancel()
    }
}
