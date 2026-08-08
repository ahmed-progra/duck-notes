package app.duck.notes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.duck.notes.data.local.dao.AttachmentDao
import app.duck.notes.data.local.dao.NoteDao
import app.duck.notes.data.local.dao.NoteLinkDao
import app.duck.notes.data.local.dao.TagDao
import app.duck.notes.data.local.entity.AttachmentEntity
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.local.entity.NoteLinkEntity
import app.duck.notes.data.local.entity.NoteTagCrossRef
import app.duck.notes.data.local.entity.TagEntity

/**
 * Room database. The `notes_fts` virtual table and its triggers are NOT declared here — Room
 * can't model FTS5 — they live in [FtsSchema], installed by [DuckDatabaseCallback].
 * The NoteLink table exists for schema completeness; its DAO arrives in Phase 9. The attachment DAO
 * landed in 8d-b — the table was already declared here at version 1, so no migration was needed.
 */
@Database(
    entities = [
        NoteEntity::class,
        TagEntity::class,
        NoteTagCrossRef::class,
        NoteLinkEntity::class,
        AttachmentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class DuckDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun tagDao(): TagDao
    abstract fun noteLinkDao(): NoteLinkDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        const val NAME = "duck.db"
    }
}
