package app.duck.notes.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.duck.notes.domain.markdown.WikiLinkScanner
import app.duck.notes.domain.usecase.DeriveTitle

/**
 * Rebuild derived references from unchanged bodies, recovering unresolved links absent from v1.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE note_links")
        connection.execSQL(
            "CREATE TABLE note_links (source_id TEXT NOT NULL, link_text TEXT NOT NULL, " +
                "fallback_title TEXT NOT NULL, PRIMARY KEY(source_id, link_text), " +
                "FOREIGN KEY(source_id) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        connection.execSQL(
            "CREATE INDEX index_notes_title_is_trashed_modified_at_id " +
                "ON notes(title, is_trashed, modified_at, id)",
        )
        val deriveTitle = DeriveTitle()
        connection.prepare("SELECT id, body FROM notes").use { notes ->
            connection.prepare(
                "INSERT INTO note_links(source_id, link_text, fallback_title) VALUES (?, ?, ?)",
            ).use { insert ->
                while (notes.step()) {
                    val id = notes.getText(0)
                    for (title in WikiLinkScanner.titlesIn(notes.getText(1))) {
                        insert.bindText(1, id)
                        insert.bindText(2, title)
                        insert.bindText(3, deriveTitle(title))
                        insert.step()
                        insert.reset()
                    }
                }
            }
        }
    }
}

/** All migrations, in order, for `RoomDatabase.Builder.addMigrations`. */
val DUCK_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
