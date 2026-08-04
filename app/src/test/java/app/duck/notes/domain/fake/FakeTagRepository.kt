package app.duck.notes.domain.fake

import app.duck.notes.domain.markdown.TagScanner
import app.duck.notes.domain.model.BodySnapshot
import app.duck.notes.domain.model.Tag
import app.duck.notes.domain.model.TagRef
import app.duck.notes.domain.repository.TagRepository
import app.duck.notes.domain.usecase.ParseTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [TagRepository].
 *
 * [observeAll], [observeNoteRefs], [setPinned] and [setIcon] behave for real, because the use cases
 * over them (`ObserveTagTree`, `ObserveTagCounts`, `PinTag`, `SetTagIcon`) are pure derivations and
 * a fake can model them exactly.
 *
 * [rename], [removeTag] and [restore] **record their calls and, by default, do nothing else**. What
 * makes those correct is a Room transaction rewriting N bodies atomically, and a fake that pretended
 * to do that would be asserting against a reimplementation rather than against the thing that ships.
 * `RenameTagTest` and `DeleteTagTest` use these to pin what the use case decides — validation,
 * lowercasing, the no-op cases — and `DuckDatabaseTest` covers the rest on a real database.
 *
 * ## Pass [notes] and the three of them rewrite bodies for real
 *
 * Phase 8a needs that, because its subject is the *interaction* between two writers: an editor buffer
 * holding unsaved text while a rename rewrites the same note underneath it. With bodies frozen, Room
 * disagrees with the buffer and the shell correctly adopts Room's stale copy — the fake's own
 * artefact, not a defect.
 *
 * This is still not a reimplementation. The rewriting goes through [TagScanner], the same single
 * implementation `TagRepositoryImpl` calls.
 *
 * ## What it omits is the transaction **and the whole tag-row half**
 *
 * This used to say only "the transaction", which undersold it. [rewriteAll] returns
 * `BodySnapshot(previous, emptyList())` and [restore] never reads `snapshot.tags` or
 * `snapshot.createdNames`, so **every JVM undo test here exercises a body-only restore.** Three real
 * behaviours are therefore invisible to this class:
 *
 * - a restored tag row keeping its **pin and icon**, which `NoteDao.ensureTag` cannot recover because
 *   it mints a fresh UUID with no customization;
 * - the **orphan sweep** over `createdNames`, without which an undone rename leaves a pinned, empty
 *   `job` in the sidebar;
 * - and `ensureTag` **resurrecting** a row the transaction just deleted.
 *
 * `TagRewriteTest` and `AutosaveDurabilityTest` cover all three on device, against real SQLite, and
 * that is where they belong: modelling tag rows here would be the reimplementation this KDoc's own
 * argument forbids, and it would be asserted against rather than the thing that ships.
 */
class FakeTagRepository(
    initial: List<Tag> = emptyList(),
    refs: List<TagRef> = emptyList(),
    private val notes: FakeNoteRepository? = null,
) : TagRepository {

    private val tags = MutableStateFlow(initial)
    private val noteRefs = MutableStateFlow(refs)

    /** Every (from, to) pair [rename] was called with, in order. Empty means it was never reached. */
    val renames = mutableListOf<Pair<String, String>>()

    val deletes = mutableListOf<String>()

    val restores = mutableListOf<BodySnapshot>()

    private val parseTags = ParseTags()

    fun current() = tags.value

    override fun observeAll(): Flow<List<Tag>> = tags

    override fun observeNoteRefs(): Flow<List<TagRef>> = noteRefs

    override suspend fun setPinned(tagId: String, pinned: Boolean) {
        tags.value = tags.value.map { if (it.id == tagId) it.copy(isPinned = pinned) else it }
    }

    override suspend fun setIcon(tagId: String, iconKey: String?) {
        tags.value = tags.value.map { if (it.id == tagId) it.copy(iconKey = iconKey) else it }
    }

    override suspend fun rename(from: String, to: String): BodySnapshot {
        renames += from to to
        return rewriteAll { TagScanner.rename(it, from, to) }
    }

    override suspend fun removeTag(name: String): BodySnapshot {
        deletes += name
        return rewriteAll { TagScanner.remove(it, name) }
    }

    /**
     * Runs immediately after [restore] has written the bodies back, before its caller continues.
     *
     * Exists for exactly one test. `DuckShellViewModel.onUndo` restores and then adopts, and the
     * window between those two steps is where a pending debounced save can clobber the restored body.
     * Under `UnconfinedTestDispatcher` that window cannot be reached from outside — the whole undo
     * coroutine runs to completion before a virtual-time advance releases the pending save, so a test
     * that merely leaves a save pending passes whether or not the fix is present, which is worse than
     * no test. This hook lets one land *inside* the window.
     */
    var afterRestore: suspend () -> Unit = {}

    override suspend fun restore(snapshot: BodySnapshot) {
        restores += snapshot
        notes?.let { repo ->
            for ((id, body) in snapshot.bodies) {
                repo.current(id)?.let { repo.save(it.copy(body = body), parseTags(body)) }
            }
        }
        afterRestore()
    }

    /** Mirrors `TagRepositoryImpl.rewriteAll`: skip unchanged bodies, return the previous ones. */
    private suspend fun rewriteAll(transform: (String) -> String): BodySnapshot {
        val repo = notes ?: return BodySnapshot(emptyMap(), emptyList())
        val previous = mutableMapOf<String, String>()
        for (note in repo.all()) {
            val next = transform(note.body)
            if (next == note.body) continue
            previous[note.id] = note.body
            repo.save(note.copy(body = next), parseTags(next))
        }
        return BodySnapshot(previous, emptyList())
    }
}
