package app.duck.notes.startup

import android.util.Log
import app.duck.notes.di.WelcomeScope
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class WelcomeStartupState { Loading, Ready, Skipped, Failed }

@Singleton
class WelcomeStartup @Inject constructor(
    private val seeder: Provider<SeedWelcomeNotes>,
    @WelcomeScope private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(WelcomeStartupState.Loading)
    val state: StateFlow<WelcomeStartupState> = mutableState.asStateFlow()
    private var started = false

    @Synchronized
    fun start(enabled: Boolean, databaseExistedAtStartup: Boolean) {
        if (started) return
        started = true
        if (!enabled) {
            mutableState.value = WelcomeStartupState.Skipped
            return
        }
        // Storage can ignore cancellation, so its deadline runs independently.
        val watchdog = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            delay(MAX_WAIT_MS)
            mutableState.compareAndSet(WelcomeStartupState.Loading, WelcomeStartupState.Failed)
        }
        scope.launch {
            try {
                seeder.get()(databaseExistedAtStartup)
                mutableState.value = WelcomeStartupState.Ready
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Log.e("DuckWelcome", "Welcome setup unavailable")
                mutableState.value = WelcomeStartupState.Failed
            }
        }.invokeOnCompletion {
            watchdog.cancel()
            mutableState.compareAndSet(WelcomeStartupState.Loading, WelcomeStartupState.Failed)
        }
    }

    private companion object {
        const val MAX_WAIT_MS = 8_000L
    }
}
