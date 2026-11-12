package app.duck.notes.startup

import app.duck.notes.domain.Clock
import app.duck.notes.domain.usecase.SaveNote
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ordinary notes, saved targets first and hub last. No DAO or initialization callback writes. */
@Singleton
class SeedWelcomeNotes @Inject constructor(
    private val state: WelcomeSeedState,
    private val saveNote: SaveNote,
    private val clock: Clock,
) {
    private val lock = Mutex()

    suspend operator fun invoke(databaseExistedAtStartup: Boolean) = lock.withLock {
        if (!state.claim(databaseExistedAtStartup)) return@withLock
        val firstTimestamp = clock.nowMillis()
        // Creation stays targets first; modified_at DESC displays hub, then the three guides.
        val timestampOffsets = listOf(2L, 1L, 0L, 3L)
        WelcomeNotes.bodies.forEachIndexed { index, body ->
            saveNote(null, body, timestampMillis = firstTimestamp + timestampOffsets[index])
        }
    }
}
