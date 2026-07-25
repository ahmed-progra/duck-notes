package app.duck.notes.domain.repository

import app.duck.notes.domain.model.Tag
import kotlinx.coroutines.flow.Flow

/**
 * Tag reads plus the user-authored bits. Tags themselves are created and reaped by the note save
 * path — there is no `create`/`delete` here on purpose.
 */
interface TagRepository {

    fun observeAll(): Flow<List<Tag>>

    suspend fun setPinned(tagId: String, pinned: Boolean)
}
