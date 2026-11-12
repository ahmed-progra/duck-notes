package app.duck.notes.data.local

import android.content.Context
import android.content.ContextWrapper
import java.io.File

internal class IsolatedAppDataContext(base: Context, val directory: File) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
    override fun getNoBackupFilesDir(): File = File(directory, "no_backup").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(directory, "databases/$name").apply { parentFile?.mkdirs() }
}
