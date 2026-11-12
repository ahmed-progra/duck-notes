package app.duck.notes.data.local

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.di.DatabaseModule
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.local.entity.AttachmentEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineMigrationTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private fun isolated() = IsolatedAppDataContext(base, File(base.cacheDir, "migration-${UUID.randomUUID()}").apply { mkdirs() })
    private fun remove(context: IsolatedAppDataContext) {
        check(context.directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
        context.directory.deleteRecursively()
    }
    private fun v1(context: Context) {
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("app.duck.notes.data.local.DuckDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        BundledSQLiteDriver().open(context.getDatabasePath("duck.db").path).use { connection ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val name = entity.getString("tableName")
                connection.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", name))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) connection.execSQL(indices.getJSONObject(j).getString("createSql").replace('$' + "{TABLE_NAME}", name))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) connection.execSQL(setup.getString(i))
            FtsSchema.create(connection)
            connection.prepare("INSERT INTO notes(id,title,body,created_at,modified_at,is_pinned,is_archived,is_trashed,is_locked,is_encrypted,trashed_at,sync_state,remote_rev) VALUES(?,?,?,123,456,1,1,0,0,0,NULL,'PENDING',7)").use { statement ->
                for ((id, title, body) in listOf(Triple("source", "Journal", BODY), Triple("target", "Pond", "Pond"))) {
                    statement.bindText(1,id); statement.bindText(2,title); statement.bindText(3,body); statement.step(); statement.reset()
                }
            }
            connection.execSQL("INSERT INTO tags(id,name,is_pinned,icon_key,sort_order) VALUES('tag','work',1,'tag',0)")
            connection.execSQL("INSERT INTO note_tags(note_id,tag_id) VALUES('source','tag')")
            connection.execSQL("INSERT INTO attachments(id,note_id,uri,mime,width,height,size,created_at) VALUES('image','source','/old/files/attachments/00000000-0000-0000-0000-000000000001','image/png',2,2,99,321)")
            connection.execSQL("PRAGMA user_version=1")
        }
    }
    private fun snapshot(context: Context): Map<String,List<List<String>>> = BundledSQLiteDriver().open(context.getDatabasePath("duck.db").path).use { connection ->
        mapOf("notes" to listOf("rowid","id","title","body","created_at","modified_at","is_pinned","is_archived","is_trashed","is_locked","is_encrypted","trashed_at","sync_state","remote_rev"), "tags" to listOf("id","name","parent_id","is_pinned","icon_key","sort_order"), "note_tags" to listOf("note_id","tag_id"), "attachments" to listOf("id","note_id","uri","mime","width","height","size","created_at")).mapValues { (table,columns) ->
            connection.prepare("SELECT ${columns.joinToString { "quote($it)" }} FROM $table ORDER BY ${columns.joinToString()}").use { statement ->
                buildList { while(statement.step()) add(columns.indices.map { statement.getText(it) }) }
            }
        }
    }
    /** Plant MIGRATION_DROP_BODY: alter a note body inside MIGRATION_1_2. */
    @Test fun productionMigrationPreservesExactRowsAndRebuildsUnresolvedReferences() = runBlocking {
        val context = isolated()
        try {
            v1(context)
            val before = snapshot(context)
            var database = DatabaseModule.provideDatabase(context)
            try {
                assertEquals(BODY, database.noteDao().getById("source")?.body)
                assertEquals(listOf("source"), database.noteLinkDao().observeBacklinks("target").first().map { it.sourceId })
                database.noteDao().saveNoteWithTagsAndLinks(NoteEntity(id="later",title="Later",body="Later",createdAt=1,modifiedAt=2),emptyList())
                assertEquals(listOf("source"), database.noteLinkDao().observeBacklinks("later").first().map { it.sourceId })
                database.noteDao().delete(requireNotNull(database.noteDao().getById("later")))
            } finally { database.close() }
            assertEquals(before, snapshot(context))
            database = DatabaseModule.provideDatabase(context)
            try { assertEquals(BODY, database.noteDao().getById("source")?.body) }
            finally { database.close() }
        } finally { remove(context) }
    }
    /** Plant DOWNGRADE_DESTRUCTIVE: re-enable fallbackToDestructiveMigrationOnDowngrade in DatabaseModule. */
    @Test fun productionUnsupportedDowngradeFailsAndPreservesDatabaseRows() = runBlocking {
        val context = isolated()
        try {
            v1(context)
            val database = DatabaseModule.provideDatabase(context)
            database.noteDao().getById("source")
            database.close()
            BundledSQLiteDriver().open(context.getDatabasePath("duck.db").path).use { it.execSQL("PRAGMA user_version=3") }
            val before = snapshot(context)
            val unsupported = DatabaseModule.provideDatabase(context)
            try {
                try { unsupported.noteDao().getById("source"); fail("unsupported downgrade erased or opened the database") }
                catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("migration", ignoreCase=true)) }
            } finally { unsupported.close() }
            assertTrue(context.getDatabasePath("duck.db").isFile)
            assertEquals(before, snapshot(context))
            BundledSQLiteDriver().open(context.getDatabasePath("duck.db").path).use { c -> c.prepare("PRAGMA user_version").use { check(it.step()); assertEquals(3L,it.getLong(0)) } }
        } finally { remove(context) }
    }
    /** Plant ATTACHMENT_TXN_REMOVED: remove @Transaction from saveNoteWithAttachment. */
    @Test fun failedAttachmentInsertRollsBackBodyTagsAndReferences() = runBlocking {
        val context = isolated()
        val database = DatabaseModule.provideDatabase(context)
        try {
            val original=NoteEntity(id="source",title="original",body="original #work [[Pond]]",createdAt=1,modifiedAt=2)
            database.noteDao().saveNoteWithTagsAndLinks(original,listOf("work"),listOf("Pond"))
            val attachment=AttachmentEntity(id="collision",noteId="source",uri="attachments/00000000-0000-0000-0000-000000000001",mime="image/png",createdAt=1)
            database.attachmentDao().insert(attachment)
            try {
                database.noteDao().saveNoteWithAttachment(original.copy(body="would lose original",title="changed"),listOf("changed"),listOf("Later"),attachment)
                fail("duplicate attachment insert must fail")
            } catch (_: android.database.sqlite.SQLiteException) { }
            catch (_: androidx.sqlite.SQLiteException) { }
            assertEquals(original.body,database.noteDao().getById("source")?.body)
            assertEquals(listOf("work"),database.tagDao().observeAll().first().map { it.name })
            assertEquals(attachment,database.attachmentDao().getById("collision"))
            database.noteDao().saveNoteWithTagsAndLinks(NoteEntity(id="pond",title="Pond",body="Pond",createdAt=1,modifiedAt=2),emptyList())
            assertEquals(listOf("source"),database.noteLinkDao().observeBacklinks("pond").first().map { it.sourceId })
        } finally { database.close();remove(context) }
    }
    private companion object { const val BODY = "Journal\r\n[[**Pond**]]\n[[Later]]\r#work" }
}
