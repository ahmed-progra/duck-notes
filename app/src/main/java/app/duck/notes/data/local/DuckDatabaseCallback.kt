package app.duck.notes.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection

/** Schema-only callbacks. Note creation runs through SaveNote after Room initialization. */
class DuckDatabaseCallback : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        FtsSchema.create(connection)
    }

    override fun onOpen(connection: SQLiteConnection) {
        FtsSchema.create(connection)
    }

    override fun onDestructiveMigration(connection: SQLiteConnection) {
        FtsSchema.create(connection)
        FtsSchema.rebuild(connection)
    }
}
