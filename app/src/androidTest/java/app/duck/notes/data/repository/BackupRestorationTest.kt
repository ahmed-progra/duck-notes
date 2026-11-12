package app.duck.notes.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.files.AttachmentFiles
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.IsolatedAppDataContext
import app.duck.notes.data.local.dao.BacklinkRow
import app.duck.notes.data.local.dao.TagNoteRefRow
import app.duck.notes.data.local.entity.AttachmentEntity
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.local.entity.TagEntity
import app.duck.notes.data.settings.SettingsRepository
import app.duck.notes.di.DatabaseModule
import app.duck.notes.di.SettingsModule
import app.duck.notes.di.WelcomeModule
import app.duck.notes.domain.Clock
import app.duck.notes.domain.markdown.MarkdownFormat
import app.duck.notes.domain.model.AppSettings
import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.domain.model.ThemePreference
import app.duck.notes.domain.usecase.DeriveTitle
import app.duck.notes.domain.usecase.ArchiveNote
import app.duck.notes.domain.usecase.DeleteNote
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import app.duck.notes.domain.usecase.SaveNote
import app.duck.notes.startup.WelcomeSeedState
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * Closed-file restore qualification using the installed target APK's manifest and backup XML.
 *
 * The copier interprets the declared include/exclude paths; it does not claim to be Android's
 * transport. These tests prove that the packaged selection retains a usable Room database,
 * portable/legacy images and real DataStore values in a different app-data directory.
 * OS scheduling, transport behavior, live-database backup and cloud quota require separate bmgr
 * runs. No application data outside each uniquely named cache fixture is changed.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestorationTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()

    /** Plant BACKUP_LEGACY_DROP_DATABASE: change the duck.db include path to missing.db. */
    @Test
    fun legacyPackagedRulesRestoreClosedDatabaseImagesAndPreferences() = runBlocking<Unit> {
        restoreRoundTrip(RuleSet.Legacy)
    }

    /** Plant BACKUP_CLOUD_DROP_SETTINGS: change the cloud settings.preferences_pb include path. */
    @Test
    fun cloudPackagedRulesRestoreClosedDatabaseImagesAndPreferences() = runBlocking<Unit> {
        restoreRoundTrip(RuleSet.Cloud)
    }

    /** Plant BACKUP_TRANSFER_DROP_IMAGES: change the device-transfer attachments include path. */
    @Test
    fun deviceTransferPackagedRulesRestoreClosedDatabaseImagesAndPreferences() = runBlocking<Unit> {
        restoreRoundTrip(RuleSet.Transfer)
    }

    /**
     * Each round trip also catches BACKUP_INCLUDE_PENDING: remove that mode's .pending exclusion.
     * A real pending file and two real included images are the exclusion/presence controls.
     */
    private suspend fun restoreRoundTrip(mode: RuleSet) {
        val source = isolated("source")
        val restored = isolated("restored")
        try {
            val expected = seed(source)
            val pending = File(source.filesDir, "attachments/.pending/interrupted.part").apply {
                parentFile?.mkdirs()
                writeText("incomplete import must never restore")
            }
            val unrelated = File(source.filesDir, "not-in-backup.txt").apply { writeText("unselected") }
            File(source.noBackupFilesDir, "local-only.txt").writeText("never backed up")
            assertTrue("positive exclusion control exists", pending.isFile)
            assertTrue("positive unrelated-file control exists", unrelated.isFile)
            assertTrue("database is physically present after close", source.getDatabasePath("duck.db").isFile)
            assertEquals(2, File(source.filesDir, "attachments").listFiles().orEmpty().count { it.isFile })
            val cleanupKey = "attachments/00000000-0000-0000-0000-000000000099"
            requireNotNull(AttachmentFiles(source.filesDir).resolve(cleanupKey)).writeText("synthetic pending deletion")
            AttachmentStorageImpl(source).apply {
                markForDeletion(cleanupKey)
                markForDeletion("attachments/$PORTABLE_IMAGE")
            }

            val rules = packagedRules(mode)
            val copied = copySelectedFiles(source, restored, rules)
            assertTrue("database include selected a real file", "databases/duck.db" in copied)
            assertTrue("settings include selected a real file", "files/datastore/settings.preferences_pb" in copied)
            assertTrue("welcome include selected a real file", "files/datastore/welcome.preferences_pb" in copied)
            assertTrue("portable image was selected", "files/attachments/$PORTABLE_IMAGE" in copied)
            assertTrue("legacy image bytes were selected", "files/attachments/$LEGACY_IMAGE" in copied)
            assertTrue("cleanup recovery intent was selected", "files/attachments/.deletions/00000000-0000-0000-0000-000000000099" in copied)
            assertFalse("pending import leaked into selected files", copied.any { it.startsWith("files/attachments/.pending/") })
            assertFalse("unselected file leaked into backup", "files/not-in-backup.txt" in copied)
            assertFalse("no-backup data leaked into backup", copied.any { it.startsWith("no_backup/") })
            assertFalse(File(restored.filesDir, "attachments/.pending/interrupted.part").exists())

            // Neither a legacy absolute path nor a live source database can rescue the restore.
            remove(source)
            assertFalse("old app-data directory is gone", source.directory.exists())
            verify(restored, expected)
            val reopened = DatabaseModule.provideDatabase(restored)
            try {
                val storage = AttachmentStorageImpl(restored)
                app.duck.notes.domain.usecase.RecoverAttachmentDeletions(
                    AttachmentRepositoryImpl(reopened.attachmentDao()), storage)()
                assertFalse(storage.exists(cleanupKey))
                assertTrue(storage.exists("attachments/$PORTABLE_IMAGE"))
                assertEquals(listOf("attachments/$PORTABLE_IMAGE"), storage.pendingDeletions())
            } finally { reopened.close() }
        } finally {
            remove(source)
            remove(restored)
        }
    }

    private suspend fun seed(context: IsolatedAppDataContext): Expected {
        val payloads = mapOf(PORTABLE_IMAGE to png(0xffbf244a.toInt()), LEGACY_IMAGE to png(0xff267acc.toInt()))
        val database = DatabaseModule.provideDatabase(context)
        val snapshot: Rows
        try {
            val notes = NoteRepositoryImpl(database.noteDao())
            val save = SaveNote(notes, DeriveTitle(), ParseTags(), ResolveWikiLinks(), Clock { 123_456L })
            save(TARGET_NOTE, "Pond")
            save(ARCHIVED_NOTE, "Archived backup entry")
            ArchiveNote(notes, ParseTags(), ResolveWikiLinks(), Clock { 234_567L })(ARCHIVED_NOTE, true)
            save(TRASHED_NOTE, "Trashed backup entry")
            DeleteNote(notes, ParseTags(), ResolveWikiLinks(), Clock { 345_678L })(TRASHED_NOTE)
            var note = save(SOURCE_NOTE, "Backup journal\r\n#work #personal/ideas\n[[Pond]]\nArabic: مرحباً")
            val storage = AttachmentStorageImpl(context)
            for ((id, payload) in payloads) {
                val input = File(context.directory, "input-$id.png").apply { writeBytes(payload) }
                val durable = requireNotNull(storage.store(Uri.fromFile(input).toString(), id)) {
                    "Real image import failed before backup: $id"
                }
                assertEquals("portable key is produced by real storage", "attachments/$id", durable)
                val location = if (id == LEGACY_IMAGE) {
                    requireNotNull(AttachmentFiles(context.filesDir).resolve(durable)).absolutePath
                } else durable
                note = save(
                    SOURCE_NOTE,
                    MarkdownFormat.appendImage(note.body, id),
                    attachment = Attachment(id, SOURCE_NOTE, location, "image/png", createdAt = 123_456L),
                )
                assertTrue("source image removed independently", input.delete())
                assertTrue("import is independently durable", storage.exists(location))
            }
            val tag = requireNotNull(database.tagDao().getByName("work"))
            database.tagDao().upsert(tag.copy(isPinned = true, iconKey = "tag"))
            snapshot = rows(database)
            assertEquals(setOf(SOURCE_NOTE, TARGET_NOTE, ARCHIVED_NOTE, TRASHED_NOTE), snapshot.notes.map { it.id }.toSet())
            assertTrue(snapshot.notes.single { it.id == ARCHIVED_NOTE }.isArchived)
            assertTrue(snapshot.notes.single { it.id == TRASHED_NOTE }.isTrashed)
            assertEquals(345_678L, requireNotNull(snapshot.notes.single { it.id == TRASHED_NOTE }.trashedAt))
            assertEquals(setOf("work", "personal/ideas"), snapshot.tags.map { it.name }.toSet())
            assertEquals(setOf(PORTABLE_IMAGE, LEGACY_IMAGE), snapshot.attachments.map { it.id }.toSet())
            assertEquals(listOf(BacklinkRow(SOURCE_NOTE, TARGET_NOTE, "Pond", "Backup journal")), snapshot.backlinks)
            assertEquals(
                setOf(TagNoteRefRow("work", SOURCE_NOTE), TagNoteRefRow("personal/ideas", SOURCE_NOTE)),
                snapshot.tagRefs.toSet(),
            )
        } finally {
            database.close()
        }
        val preferences = preferences(context, seed = true)
        return Expected(snapshot, payloads, preferences)
    }

    private suspend fun verify(context: IsolatedAppDataContext, expected: Expected) {
        val database = DatabaseModule.provideDatabase(context)
        try {
            assertEquals("exact IDs, bodies, flags, timestamps, tags and attachment rows", expected.rows, rows(database))
            assertEquals("FTS remains usable after restore", listOf(SOURCE_NOTE), database.noteDao().search("journal").map { it.id })
            val storage = AttachmentStorageImpl(context)
            expected.rows.attachments.forEach { attachment ->
                assertTrue("restored path resolves without the old installation: ${attachment.uri}", storage.exists(attachment.uri))
                val file = requireNotNull(AttachmentFiles(context.filesDir).resolve(attachment.uri))
                assertEquals(context.filesDir.canonicalFile, file.canonicalFile.parentFile?.parentFile)
                assertArrayEquals(expected.images.getValue(attachment.id), file.readBytes())
            }
        } finally {
            database.close()
        }
        assertEquals("both preference protobufs retain exact typed values", expected.preferences, preferences(context, seed = false))
    }

    private suspend fun rows(database: DuckDatabase) = Rows(
        notes = (database.noteDao().observeNotes().first() + database.noteDao().observeTrashed().first()).sortedBy { it.id },
        tags = database.tagDao().observeAll().first().sortedBy { it.id },
        tagRefs = database.tagDao().observeNoteRefs().first().sortedWith(compareBy({ it.name }, { it.noteId })),
        attachments = database.attachmentDao().forNote(SOURCE_NOTE).sortedBy { it.id },
        backlinks = database.noteLinkDao().observeBacklinks(TARGET_NOTE).first(),
    )

    private suspend fun preferences(context: Context, seed: Boolean): Map<String, Map<String, Any>> {
        val scope = SettingsModule.provideSettingsScope()
        try {
            val notices = SettingsModule.provideSettingsNotices()
            val settingsStore = SettingsModule.provideSettingsDataStore(context, scope, notices)
            val welcomeStore = WelcomeModule.provideStore(context, scope)
            val settings = SettingsRepository(settingsStore, notices, scope)
            val welcome = WelcomeSeedState(welcomeStore)
            if (seed) {
                settings.setTheme(ThemePreference.Dark)
                settings.setLockEnabled(true)
                settings.setLockTimeoutMs(300_000L)
                settings.setHideRecents(false)
                assertTrue("fresh welcome marker positive control", welcome.claim(databaseExistedAtStartup = false))
            }
            val expectedSettings = AppSettings(ThemePreference.Dark, true, 300_000L, false)
            val actual = withTimeout(5_000L) {
                settings.state.first { it.readStatus == SettingsReadStatus.Ready && (!seed || it.settings == expectedSettings) }
            }
            assertEquals(expectedSettings, actual.settings)
            val values = mapOf(
                "settings" to settingsStore.data.first().asMap().entries.associate { it.key.name to it.value },
                "welcome" to welcomeStore.data.first().asMap().entries.associate { it.key.name to it.value },
            )
            assertEquals(mapOf("installation_origin_v1" to "fresh", "welcome_attempted_v1" to true), values["welcome"])
            assertFalse("restored marker must prevent another welcome seed", welcome.claim(databaseExistedAtStartup = false))
            return values
        } finally {
            requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
        }
    }

    /** Read the manifest's actual resource references, including the allowBackup gate. */
    private fun packagedRules(mode: RuleSet): List<Rule> {
        var resource = 0
        base.assets.openXmlResourceParser("AndroidManifest.xml").use { manifest ->
            while (manifest.eventType != XmlPullParser.END_DOCUMENT) {
                if (manifest.eventType == XmlPullParser.START_TAG && manifest.name == "application") {
                    assertTrue("installed manifest disables backup", manifest.getAttributeBooleanValue(ANDROID, "allowBackup", false))
                    resource = manifest.getAttributeResourceValue(
                        ANDROID,
                        if (mode == RuleSet.Legacy) "fullBackupContent" else "dataExtractionRules",
                        0,
                    )
                    break
                }
                manifest.next()
            }
        }
        assertTrue("installed manifest has no backup XML resource", resource != 0)
        return base.resources.getXml(resource).use { xml ->
            val rules = mutableListOf<Rule>()
            var active = false
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG) {
                    if (xml.name == mode.section) active = true
                    if (active && xml.name in setOf("include", "exclude")) {
                        // Fail rather than silently invent semantics for future conditional rules.
                        assertNull("transport flags require a dedicated transport test", xml.getAttributeValue(null, "requireFlags"))
                        val domain = requireNotNull(xml.getAttributeValue(null, "domain"))
                        check(domain in setOf("file", "database")) { "Unsupported domain in test copier: $domain" }
                        val path = requireNotNull(xml.getAttributeValue(null, "path")).trimEnd('/')
                        check(!File(path).isAbsolute && path.split('/').none { it == ".." })
                        rules += Rule(domain, path, xml.name == "include")
                    }
                } else if (xml.eventType == XmlPullParser.END_TAG && xml.name == mode.section) {
                    active = false
                }
                xml.next()
            }
            assertTrue("no include controls were parsed for $mode", rules.any { it.include })
            rules
        }
    }

    private fun copySelectedFiles(source: IsolatedAppDataContext, destination: IsolatedAppDataContext, rules: List<Rule>): Set<String> {
        val copied = mutableSetOf<String>()
        for ((domain, directory) in mapOf("file" to "files", "database" to "databases")) {
            val root = File(source.directory, directory)
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                fun Rule.matches() = this.domain == domain && (path == "." || relative == path || relative.startsWith("$path/"))
                if (rules.any { it.include && it.matches() } && rules.none { !it.include && it.matches() }) {
                    val target = File(destination.directory, "$directory/$relative")
                    check(target.canonicalPath.startsWith(destination.directory.canonicalPath + File.separator))
                    target.parentFile?.mkdirs()
                    file.copyTo(target)
                    copied += "$directory/$relative"
                }
            }
        }
        return copied
    }

    private fun isolated(label: String) = IsolatedAppDataContext(
        base,
        File(base.cacheDir, "backup-$label-${UUID.randomUUID()}").apply { check(mkdirs()) },
    )

    private fun remove(context: IsolatedAppDataContext) {
        check(context.directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
        if (context.directory.exists()) check(context.directory.deleteRecursively())
    }

    private fun png(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private enum class RuleSet(val section: String) {
        Legacy("full-backup-content"), Cloud("cloud-backup"), Transfer("device-transfer"),
    }

    private data class Rule(val domain: String, val path: String, val include: Boolean)
    private data class Rows(
        val notes: List<NoteEntity>,
        val tags: List<TagEntity>,
        val tagRefs: List<TagNoteRefRow>,
        val attachments: List<AttachmentEntity>,
        val backlinks: List<BacklinkRow>,
    )
    private data class Expected(
        val rows: Rows,
        val images: Map<String, ByteArray>,
        val preferences: Map<String, Map<String, Any>>,
    )

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val SOURCE_NOTE = "backup-source"
        const val TARGET_NOTE = "backup-target"
        const val ARCHIVED_NOTE = "backup-archived"
        const val TRASHED_NOTE = "backup-trashed"
        const val PORTABLE_IMAGE = "00000000-0000-0000-0000-000000000001"
        const val LEGACY_IMAGE = "00000000-0000-0000-0000-000000000002"
    }
}
