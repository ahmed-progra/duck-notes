package app.duck.notes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
 * Attachment/NoteLink tables exist for schema completeness; their DAOs arrive in Phases 8/9.
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

    companion object {
        const val NAME = "duck.db"
    }
}
