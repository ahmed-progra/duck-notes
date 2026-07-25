package app.duck.notes.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection

/**
 * Owns the parts of the schema Room can't declare: the FTS5 table/triggers ([FtsSchema]) and,
 * in debug builds, a few sample notes.
 *
 * These are the **[SQLiteConnection] overloads**, not the `SupportSQLiteDatabase` ones — with a
 * `SQLiteDriver` installed (see `DatabaseModule`), Room never invokes the Support-based callbacks,
 * so using those signatures would silently leave the database without `notes_fts`.
 *
 * The FTS layer is (re)created on every [onCreate], [onOpen], and [onDestructiveMigration] so it
 * survives fresh installs, re-opens, and fallback rebuilds alike. Seeding goes through prepared
 * statements on the raw connection — never a DAO, which would deadlock since the [RoomDatabase]
 * isn't finished constructing inside a callback. Seeds run after the triggers exist, so the
 * AFTER-INSERT trigger fills `notes_fts` for us.
 */
class DuckDatabaseCallback(
    private val seedSampleNotes: Boolean,
) : RoomDatabase.Callback() {

    override fun onCreate(connection: SQLiteConnection) {
        FtsSchema.create(connection)
        if (seedSampleNotes) seed(connection)
    }

    override fun onOpen(connection: SQLiteConnection) {
        FtsSchema.create(connection)
    }

    override fun onDestructiveMigration(connection: SQLiteConnection) {
        FtsSchema.create(connection)
        FtsSchema.rebuild(connection)
    }

    private fun seed(connection: SQLiteConnection) {
        val now = System.currentTimeMillis()
        insertNote(connection, SEED_NOTE_WELCOME, "Welcome to Duck", "# Welcome to Duck\n\nTap + to start. #welcome", now)
        insertNote(connection, SEED_NOTE_MARKDOWN, "Markdown basics", "**Bold**, _italic_, `code`. #duck", now - 1)
        insertNote(connection, SEED_NOTE_TODO, "Todo", "- [ ] Try full-text search #todo", now - 2)

        insertTag(connection, SEED_TAG_WELCOME, "welcome")
        insertTag(connection, SEED_TAG_DUCK, "duck")
        insertTag(connection, SEED_TAG_TODO, "todo")

        linkTag(connection, SEED_NOTE_WELCOME, SEED_TAG_WELCOME)
        linkTag(connection, SEED_NOTE_MARKDOWN, SEED_TAG_DUCK)
        linkTag(connection, SEED_NOTE_TODO, SEED_TAG_TODO)
    }

    // Bind indices below are 1-based, matching the `?` placeholders left to right.
    private fun insertNote(connection: SQLiteConnection, id: String, title: String, body: String, ts: Long) {
        connection.prepare(
            """
            INSERT INTO notes
                (id, title, body, created_at, modified_at,
                 is_pinned, is_archived, is_trashed, is_locked, is_encrypted,
                 trashed_at, sync_state, remote_rev)
            VALUES (?, ?, ?, ?, ?, 0, 0, 0, 0, 0, NULL, 'SYNCED', NULL)
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, id)
            statement.bindText(2, title)
            statement.bindText(3, body)
            statement.bindLong(4, ts)
            statement.bindLong(5, ts)
            statement.step()
        }
    }

    private fun insertTag(connection: SQLiteConnection, id: String, name: String) {
        connection.prepare(
            "INSERT INTO tags (id, name, parent_id, icon_key, is_pinned, sort_order) " +
                "VALUES (?, ?, NULL, NULL, 0, 0)",
        ).use { statement ->
            statement.bindText(1, id)
            statement.bindText(2, name)
            statement.step()
        }
    }

    private fun linkTag(connection: SQLiteConnection, noteId: String, tagId: String) {
        connection.prepare("INSERT INTO note_tags (note_id, tag_id) VALUES (?, ?)").use { statement ->
            statement.bindText(1, noteId)
            statement.bindText(2, tagId)
            statement.step()
        }
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
