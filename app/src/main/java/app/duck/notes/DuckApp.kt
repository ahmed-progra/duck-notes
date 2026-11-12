package app.duck.notes

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import android.os.Process
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.startup.WelcomeStartup
import app.duck.notes.di.WelcomeScope
import app.duck.notes.domain.usecase.RecoverAttachmentDeletions
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Application entry point. Startup dependencies keep note/database access lazy. */
@HiltAndroidApp
open class DuckApp : Application() {
    @Inject lateinit var welcomeStartup: WelcomeStartup
    @Inject lateinit var attachmentRecovery: Provider<RecoverAttachmentDeletions>
    @Inject @WelcomeScope lateinit var startupScope: CoroutineScope

    protected open val welcomeSeedingEnabled: Boolean = true

    override fun onCreate() {
        super.onCreate()
        // Capture before activities or the lazy seeder can open Room and create its file.
        val databaseExisted = getDatabasePath(DuckDatabase.NAME).exists()
        val processName = if (Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            getSystemService(ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        welcomeStartup.start(welcomeSeedingEnabled && processName == packageName, databaseExisted)
        if (processName == packageName) startupScope.launch {
            try { attachmentRecovery.get()() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.util.Log.e("DuckStorage", "Attachment cleanup unavailable") }
        }
    }
}
