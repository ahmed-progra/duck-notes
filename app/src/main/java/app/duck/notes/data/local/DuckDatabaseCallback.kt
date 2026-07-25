package app.duck.notes.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Owns the parts of the schema Room can't declare: the FTS5 table/triggers ([FtsSchema]) and,
 * in debug builds, a few sample notes.
 *
 * The FTS layer is (re)created on every [onCreate], [onOpen], and [onDestructiveMigration] so it
 * survives fresh installs, re-opens, and fallback rebuilds alike. Seeding uses raw `execSQL`
 * only — the [RoomDatabase] instance isn't finished constructing inside a callback, so touching a
 * DAO here would deadlock. Seeds run after the triggers exist, so the AFTER-INSERT trigger fills
 * `notes_fts` for us.
 */
class DuckDatabaseCallback(
    private val seedSampleNotes: Boolean,
) : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        FtsSchema.create(db)
        if (seedSampleNotes) seed(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        FtsSchema.create(db)
    }

    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
        FtsSchema.create(db)
        FtsSchema.rebuild(db)
    }

    private fun seed(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        insertNote(db, SEED_NOTE_WELCOME, "Welcome to Duck", "# Welcome to Duck\n\nTap + to start. #welcome", now)
        insertNote(db, SEED_NOTE_MARKDOWN, "Markdown basics", "**Bold**, _italic_, `code`. #duck", now - 1)
        insertNote(db, SEED_NOTE_TODO, "Todo", "- [ ] Try full-text search #todo", now - 2)

        insertTag(db, SEED_TAG_WELCOME, "welcome")
        insertTag(db, SEED_TAG_DUCK, "duck")
        insertTag(db, SEED_TAG_TODO, "todo")

        linkTag(db, SEED_NOTE_WELCOME, SEED_TAG_WELCOME)
        linkTag(db, SEED_NOTE_MARKDOWN, SEED_TAG_DUCK)
        linkTag(db, SEED_NOTE_TODO, SEED_TAG_TODO)
    }

    private fun insertNote(db: SupportSQLiteDatabase, id: String, title: String, body: String, ts: Long) {
        db.execSQL(
            """
            INSERT INTO notes
                (id, title, body, created_at, modified_at,
                 is_pinned, is_archived, is_trashed, is_locked, is_encrypted,
                 trashed_at, sync_state, remote_rev)
            VALUES (?, ?, ?, ?, ?, 0, 0, 0, 0, 0, NULL, 'SYNCED', NULL)
            """.trimIndent(),
            arrayOf(id, title, body, ts, ts),
        )
    }

    private fun insertTag(db: SupportSQLiteDatabase, id: String, name: String) {
        db.execSQL(
            "INSERT INTO tags (id, name, parent_id, icon_key, is_pinned, sort_order) " +
                "VALUES (?, ?, NULL, NULL, 0, 0)",
            arrayOf(id, name),
        )
    }

    private fun linkTag(db: SupportSQLiteDatabase, noteId: String, tagId: String) {
        db.execSQL("INSERT INTO note_tags (note_id, tag_id) VALUES (?, ?)", arrayOf(noteId, tagId))
    }

    private companion object {
        const val SEED_NOTE_WELCOME = "11111111-1111-1111-1111-111111111111"
        const val SEED_NOTE_MARKDOWN = "22222222-2222-2222-2222-222222222222"
        const val SEED_NOTE_TODO = "33333333-3333-3333-3333-333333333333"
        const val SEED_TAG_WELCOME = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val SEED_TAG_DUCK = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val SEED_TAG_TODO = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    }
}
