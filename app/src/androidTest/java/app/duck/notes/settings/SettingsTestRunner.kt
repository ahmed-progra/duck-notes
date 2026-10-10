package app.duck.notes.settings

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.ViewTreeObserver
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.runner.AndroidJUnitRunner
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.presentation.MainActivity
import app.duck.notes.presentation.settings.SettingsViewModel
import dagger.hilt.android.testing.HiltTestApplication
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList

class SettingsTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        val start = SystemClock.elapsedRealtime()
        if (SettingsDrawProbe.enabled && activity is MainActivity) {
            SettingsDrawProbe.beforeCreate(activity, start)
        }
        super.callActivityOnCreate(activity, icicle)
        if (SettingsDrawProbe.enabled && activity is MainActivity) {
            SettingsDrawProbe.afterCreate(activity, start)
        }
    }
}

internal data class SettingsDrawObservation(
    val entryElapsedMs: Long,
    val drawElapsedMs: Long,
    val readStatus: SettingsReadStatus,
    val resourceNightMask: Int,
    val windowBackground: Int?,
    val lightStatusBars: Boolean,
    val lightNavigationBars: Boolean,
    val preDrawAttempt: SettingsPreDrawObservation? = null,
    @Volatile var pixelCopyResult: Int? = null,
    @Volatile var bitmap: Bitmap? = null,
)

internal data class SettingsPreDrawObservation(
    val activityIdentity: Int,
    val entryElapsedMs: Long,
    val preDrawElapsedMs: Long,
    val readStatus: SettingsReadStatus,
    val resourceNightMask: Int,
    val windowBackground: Int?,
    val lightStatusBars: Boolean,
    val lightNavigationBars: Boolean,
    @Volatile var followedByDraw: Boolean = false,
)

/**
 * Enabled before launch; attached when Activity.onCreate returns, before instrumentation gives
 * the main thread back to its frame loop. OnDraw observes a draw that survived
 * every pre-draw veto. PixelCopy is the first available asynchronous capture, not a guarantee
 * that every physical frame or the platform's separate starting window was observed.
 */
internal object SettingsDrawProbe {
    @Volatile var enabled = false
    @Volatile var latestEntryElapsedMs: Long? = null
    @Volatile var latestCreateCompletedElapsedMs: Long? = null
    @Volatile var firstCreateResourceNightMask: Int? = null
    @Volatile var createCount = 0
    @Volatile var firstAvailableCapture: SettingsDrawObservation? = null
    val observations = CopyOnWriteArrayList<SettingsDrawObservation>()
    val preDrawAttempts = CopyOnWriteArrayList<SettingsPreDrawObservation>()
    private val origins = IdentityHashMap<SettingsViewModel, Long>()
    private val main = Handler(Looper.getMainLooper())
    private var epoch = 0L

    fun afterCreate(activity: MainActivity, entryElapsedMs: Long) {
        createCount++
        if (firstCreateResourceNightMask == null) {
            firstCreateResourceNightMask = activity.resources.configuration.uiMode and 0x30
        }
        val viewModel = ViewModelProvider(activity)[SettingsViewModel::class.java]
        if (!origins.containsKey(viewModel)) origins[viewModel] = entryElapsedMs
        installDrawListener(activity, entryElapsedMs)
        latestCreateCompletedElapsedMs = SystemClock.elapsedRealtime()
    }

    fun beforeCreate(activity: MainActivity, entryElapsedMs: Long) {
        latestEntryElapsedMs = entryElapsedMs
    }

    private fun installDrawListener(activity: MainActivity, entryElapsedMs: Long) {
        val installedEpoch = epoch
        val decor = activity.window.decorView
        var observed = false
        var latestAttempt: SettingsPreDrawObservation? = null
        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (!observed && installedEpoch == epoch && enabled) {
                val viewModel = ViewModelProvider(activity)[SettingsViewModel::class.java]
                val bars = WindowInsetsControllerCompat(activity.window, decor)
                latestAttempt = SettingsPreDrawObservation(
                    activityIdentity = System.identityHashCode(activity),
                    entryElapsedMs = origins[viewModel] ?: entryElapsedMs,
                    preDrawElapsedMs = SystemClock.elapsedRealtime(),
                    readStatus = viewModel.snapshot.value.readStatus,
                    resourceNightMask = activity.resources.configuration.uiMode and 0x30,
                    windowBackground = (decor.background as? ColorDrawable)?.color,
                    lightStatusBars = bars.isAppearanceLightStatusBars,
                    lightNavigationBars = bars.isAppearanceLightNavigationBars,
                ).also { preDrawAttempts += it }
            }
            true
        }
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (observed || installedEpoch != epoch || !enabled) return
                observed = true
                latestAttempt?.followedByDraw = true
                val viewModel = ViewModelProvider(activity)[SettingsViewModel::class.java]
                val bars = WindowInsetsControllerCompat(activity.window, decor)
                val record = SettingsDrawObservation(
                    entryElapsedMs = origins[viewModel] ?: entryElapsedMs,
                    drawElapsedMs = SystemClock.elapsedRealtime(),
                    readStatus = viewModel.snapshot.value.readStatus,
                    resourceNightMask = activity.resources.configuration.uiMode and 0x30,
                    windowBackground = (decor.background as? ColorDrawable)?.color,
                    lightStatusBars = bars.isAppearanceLightStatusBars,
                    lightNavigationBars = bars.isAppearanceLightNavigationBars,
                    preDrawAttempt = latestAttempt,
                )
                observations += record
                // Removing an OnDrawListener while dispatching onDraw is forbidden.
                main.post {
                    if (decor.viewTreeObserver.isAlive) {
                        decor.viewTreeObserver.removeOnDrawListener(this)
                        decor.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
                    }
                    if (installedEpoch != epoch || !enabled) return@post
                    if (activity.isDestroyed || decor.width <= 0 || decor.height <= 0) {
                        record.pixelCopyResult = PixelCopy.ERROR_SOURCE_INVALID
                        return@post
                    }
                    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(activity.window, bitmap, { result ->
                        if (installedEpoch != epoch || !enabled) {
                            bitmap.recycle()
                            return@request
                        }
                        if (result == PixelCopy.SUCCESS) {
                            record.bitmap = bitmap
                            record.pixelCopyResult = result
                            if (firstAvailableCapture == null) firstAvailableCapture = record
                        } else {
                            bitmap.recycle()
                        }
                        record.pixelCopyResult = result
                    }, main)
                }
            }
        }
        decor.viewTreeObserver.addOnDrawListener(listener)
        decor.viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    fun reset() {
        enabled = false
        epoch++
        observations.forEach { it.bitmap?.recycle() }
        observations.clear()
        preDrawAttempts.clear()
        origins.clear()
        latestEntryElapsedMs = null
        latestCreateCompletedElapsedMs = null
        firstCreateResourceNightMask = null
        createCount = 0
        firstAvailableCapture = null
    }
}
