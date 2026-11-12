package app.duck.notes.di

import android.content.Context
import androidx.room.Room
import app.duck.notes.data.local.DUCK_MIGRATIONS
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.DuckDatabaseCallback
import app.duck.notes.data.local.dao.AttachmentDao
import app.duck.notes.data.local.dao.NoteDao
import app.duck.notes.data.local.dao.NoteLinkDao
import app.duck.notes.data.local.dao.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import javax.inject.Singleton

/** Wires the Room database and its DAOs into the Hilt graph. Welcome notes use the ordinary save path after initialization. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DuckDatabase =
        Room.databaseBuilder(context, DuckDatabase::class.java, DuckDatabase.NAME)
            // Bundled SQLite — the system one may lack the FTS5 module `notes_fts` needs.
            // Installing a driver also means DuckDatabaseCallback must override the
            // SQLiteConnection callbacks; the SupportSQLiteDatabase ones are never invoked.
            .setDriver(BundledSQLiteDriver())
            .addCallback(DuckDatabaseCallback())
            .addMigrations(*DUCK_MIGRATIONS)
            .build()

    @Provides
    fun provideNoteDao(db: DuckDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideTagDao(db: DuckDatabase): TagDao = db.tagDao()

    @Provides
    fun provideNoteLinkDao(db: DuckDatabase): NoteLinkDao = db.noteLinkDao()

    @Provides
    fun provideAttachmentDao(db: DuckDatabase): AttachmentDao = db.attachmentDao()
}
