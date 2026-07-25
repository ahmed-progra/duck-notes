package app.duck.notes.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration registry. The DB ships at version 1, so [MIGRATION_1_2] never fires yet — it exists
 * to fix the pattern in place for the first real schema bump. When that happens, raise the
 * `@Database` version and write the `ALTER`/`CREATE` steps here. FTS objects are not touched by
 * migrations; [DuckDatabaseCallback] rebuilds them via `IF NOT EXISTS`.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Stub: no schema change between v1 and v2 yet.
    }
}

/** All migrations, in order, for `RoomDatabase.Builder.addMigrations`. */
val DUCK_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
