package app.duck.notes.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The FTS5 layer Room can't express itself (it only supports `@Fts3/@Fts4`).
 *
 * `notes_fts` is an external-content table mirroring `notes` via [content_rowid]; the triggers
 * keep it in sync. Everything is `IF NOT EXISTS` so [create] is safe to run from `onCreate`,
 * `onOpen`, and `onDestructiveMigration`. The DELETE/UPDATE triggers use FTS5's special
 * `('delete', ...)` command form required for external-content tables.
 */
object FtsSchema {

    private const val CREATE_TABLE =
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(
            title, body,
            content='notes', content_rowid='rowid',
            tokenize='unicode61 remove_diacritics 2'
        )
        """

    private const val TRIGGER_INSERT =
        """
        CREATE TRIGGER IF NOT EXISTS notes_fts_ai AFTER INSERT ON notes BEGIN
            INSERT INTO notes_fts(rowid, title, body) VALUES (new.rowid, new.title, new.body);
        END
        """

    private const val TRIGGER_DELETE =
        """
        CREATE TRIGGER IF NOT EXISTS notes_fts_ad AFTER DELETE ON notes BEGIN
            INSERT INTO notes_fts(notes_fts, rowid, title, body)
            VALUES ('delete', old.rowid, old.title, old.body);
        END
        """

    private const val TRIGGER_UPDATE =
        """
        CREATE TRIGGER IF NOT EXISTS notes_fts_au AFTER UPDATE ON notes BEGIN
            INSERT INTO notes_fts(notes_fts, rowid, title, body)
            VALUES ('delete', old.rowid, old.title, old.body);
            INSERT INTO notes_fts(rowid, title, body) VALUES (new.rowid, new.title, new.body);
        END
        """

    /** Create the FTS table and its sync triggers if they don't already exist. */
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_TABLE.trimIndent())
        db.execSQL(TRIGGER_INSERT.trimIndent())
        db.execSQL(TRIGGER_DELETE.trimIndent())
        db.execSQL(TRIGGER_UPDATE.trimIndent())
    }

    /** Re-index every surviving `notes` row into a freshly (re)created FTS table. */
    fun rebuild(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO notes_fts(notes_fts) VALUES ('rebuild')")
    }
}
