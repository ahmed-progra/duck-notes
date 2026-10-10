package app.duck.notes.settings

import android.app.UiModeManager
import android.content.Context
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Display
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.settings.SettingsRepository
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.domain.model.ThemePreference
import app.duck.notes.presentation.MainActivity
import app.duck.notes.presentation.settings.SettingsViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FIRST_FRAME: a real isolated store is held on StandardTestDispatcher until 300ms after Activity entry.
 * FIRST_FRAME_WINDOW inverts projectWindow's dark argument; the actual first window assertion fails.
 * Premature-release mutations can hit an earlier recreation control; they are not credited here.
 * APPEARANCE_SNAPSHOT and APPEARANCE_MASK exercise the actual production readiness helper on JVM.
 * OnDraw and a successful first available PixelCopy are separate evidence. This does not claim
 * complete capture of every physical frame or the platform starting window. The literal cap
 * bounds the unresolved read; a loaded appearance can await platform configuration settlement.
 * Recreation checks the retained VM cap decision; actual frame latency has no upper-bound claim.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FirstFrameThemeTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var resources: SettingsTestResources
    @Inject lateinit var repository: SettingsRepository
    @Inject lateinit var database: DuckDatabase
    private var scenario: ActivityScenario<MainActivity>? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var initialSystemDark = false
    private var systemWindowContext: Context? = null
    @Volatile private var settingsWorkReleasedAtElapsedMs: Long? = null
    @Volatile private var settingsReadyObservedAtElapsedMs: Long? = null

    @Before fun setUp() {
        if (Build.VERSION.SDK_INT >= 31) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val display = requireNotNull(context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY))
            systemWindowContext = context.createWindowContext(display,
                WindowManager.LayoutParams.TYPE_APPLICATION, null)
        }
        initialSystemDark = systemNightMask() == 0x20
        SettingsTestOptions.initialTheme = if (initialSystemDark) ThemePreference.Light else ThemePreference.Dark
        hilt.inject()
        assertEquals("Positive control: real store read must actually be paused",
            SettingsReadStatus.Loading, repository.state.value.readStatus)
        SettingsDrawProbe.reset()
        SettingsDrawProbe.enabled = true
    }

    @After fun tearDown() {
        worker.shutdownNow()
        assertTrue("Settings worker did not stop", worker.awaitTermination(10L, TimeUnit.SECONDS))
        try {
            scenario?.close()
        } finally {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync {
                if (Build.VERSION.SDK_INT >= 31) instrumentation.targetContext
                    .getSystemService(UiModeManager::class.java)
                    .setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                SettingsDrawProbe.reset()
            }
            if (::resources.isInitialized) runBlocking { resources.close() }
            if (::database.isInitialized) database.close()
            SettingsTestOptions.reset()
            systemWindowContext = null
        }
    }

    @Test fun delayedOppositePreferenceIsReadyAtTheFirstPermittedAppDraw() {
        forceAppAppearance(null)
        val expectedDark = !initialSystemDark
        worker.submit {
            while (SettingsDrawProbe.latestEntryElapsedMs == null) Thread.sleep(2L)
            val entry = requireNotNull(SettingsDrawProbe.latestEntryElapsedMs)
            while (SystemClock.elapsedRealtime() - entry < 300L) Thread.sleep(2L)
            settingsWorkReleasedAtElapsedMs = SystemClock.elapsedRealtime()
            val end = SystemClock.elapsedRealtime() + 5_000L
            while (repository.state.value.readStatus == SettingsReadStatus.Loading &&
                SystemClock.elapsedRealtime() < end) {
                resources.advanceSettingsWork()
                Thread.sleep(5L)
            }
            if (repository.state.value.readStatus == SettingsReadStatus.Ready) {
                settingsReadyObservedAtElapsedMs = SystemClock.elapsedRealtime()
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val record = firstDraw()
        assertEquals("Positive control: launch must start in System appearance",
            if (initialSystemDark) 0x20 else 0x10, SettingsDrawProbe.firstCreateResourceNightMask)
        assertTrue("Saved opposite appearance never recreated the actual Activity",
            SettingsDrawProbe.createCount >= 2)
        assertEquals("FIRST_FRAME: real Loading preference reached an actual app draw",
            SettingsReadStatus.Ready, record.readStatus)
        val releaseAt = requireNotNull(settingsWorkReleasedAtElapsedMs)
        assertTrue("Positive control: real read was not held for the literal 300ms pause",
            releaseAt - record.entryElapsedMs >= 300L)
        assertTrue("Positive control: real read missed the literal 1000ms read cap: " +
            "read=${releaseAt - record.entryElapsedMs}ms draw=${record.drawElapsedMs - record.entryElapsedMs}ms",
            releaseAt - record.entryElapsedMs < 1_000L)
        android.util.Log.i("DuckSettingsDrawProbe",
            "FIRST_FRAME_TIMING: read=${releaseAt - record.entryElapsedMs}ms " +
                "draw=${record.drawElapsedMs - record.entryElapsedMs}ms mask=${record.resourceNightMask} " +
                "creates=${SettingsDrawProbe.createCount} " +
                "readyObserved=${settingsReadyObservedAtElapsedMs?.minus(record.entryElapsedMs)}ms " +
                "activity=${record.preDrawAttempt?.activityIdentity}")
        SettingsDrawProbe.preDrawAttempts.forEach {
            android.util.Log.i("DuckSettingsDrawProbe", "FIRST_FRAME_ATTEMPT: $it")
        }
        SettingsDrawProbe.observations.forEach {
            android.util.Log.i("DuckSettingsDrawProbe", "FIRST_FRAME_DRAW: $it")
        }
        val readyObservedAt = requireNotNull(settingsReadyObservedAtElapsedMs) {
            "Positive control: the real read never became Ready on the worker"
        }
        assertTrue("Positive control: real read was not Ready before the literal 1000ms cap: " +
            "readyObserved=${readyObservedAt - record.entryElapsedMs}ms",
            readyObservedAt - record.entryElapsedMs < 1_000L)
        assertTrue("No pre-draw callbacks were measured despite an actual observed draw",
            SettingsDrawProbe.preDrawAttempts.isNotEmpty())
        assertTrue("Actual OnDraw was not linked to a measured pre-draw attempt",
            record.preDrawAttempt?.followedByDraw == true)
        val pausedAttempts = SettingsDrawProbe.preDrawAttempts.filter { it.preDrawElapsedMs < releaseAt }
        if (pausedAttempts.isNotEmpty()) {
            assertTrue("Paused real store must remain Loading at every measured pre-draw attempt",
                pausedAttempts.all { it.readStatus == SettingsReadStatus.Loading })
            assertTrue("A Loading pre-draw attempt unexpectedly reached an actual draw before release",
                pausedAttempts.none { it.followedByDraw })
        } else {
            android.util.Log.i("DuckSettingsDrawProbe",
                "No pre-draw callbacks observed during the paused300ms interval; blocked-attempt trace unavailable. First OnDraw assertions remain active.")
        }
        assertProjection(record, expectedDark)
        assertFirstAvailablePixel(if (expectedDark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt())
    }

    @Test fun stuckRealStoreReleasesAtTheLiteralCapAndLateReadClearsTheNotice() {
        forceAppAppearance(!initialSystemDark)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val record = firstDraw()
        assertEquals("Positive control: prior forced app configuration must oppose global System",
            if (initialSystemDark) 0x10 else 0x20, SettingsDrawProbe.firstCreateResourceNightMask)
        assertEquals(SettingsReadStatus.Loading, record.readStatus)
        assertTrue("An unresolved real read was drawn before the literal 1000ms cap",
            record.drawElapsedMs - record.entryElapsedMs >= 1_000L)
        assertProjection(record, initialSystemDark, requireSettledConfiguration = false)
        assertFirstAvailablePixel(if (initialSystemDark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt())
        var timedOut = false
        scenario?.onActivity { timedOut = ViewModelProvider(it)[SettingsViewModel::class.java].uiState.value.startupTimedOut }
        assertTrue(timedOut)
        compose.waitUntil(timeoutMillis = 10_000L) {
            resources.advanceSettingsWork()
            repository.state.value.readStatus == SettingsReadStatus.Ready
        }
        compose.waitUntil(timeoutMillis = 10_000L) {
            var cleared = false
            scenario?.onActivity { cleared = !ViewModelProvider(it)[SettingsViewModel::class.java].uiState.value.startupTimedOut }
            cleared
        }
    }

    @Test fun recreationDuringThePausedReadDoesNotRestartTheOriginalCap() {
        forceAppAppearance(null)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val originalEntry = requireNotNull(SettingsDrawProbe.latestEntryElapsedMs)
        lateinit var original: SettingsViewModel
        scenario?.onActivity { original = ViewModelProvider(it)[SettingsViewModel::class.java] }
        val retainedOrigin = requireNotNull(original.firstLaunchElapsedMs)
        while (SystemClock.elapsedRealtime() - originalEntry < 300L) Thread.sleep(2L)
        scenario?.recreate()
        val recreatedEntry = requireNotNull(SettingsDrawProbe.latestEntryElapsedMs)
        assertTrue("Positive control: a later actual onCreate was not observed", recreatedEntry > originalEntry)
        var recreatedIdentity = 0
        scenario?.onActivity {
            val retained = ViewModelProvider(it)[SettingsViewModel::class.java]
            assertSame(original, retained)
            assertEquals("SPLASH_ORIGIN: recreation changed the retained launch origin",
                retainedOrigin, requireNotNull(retained.firstLaunchElapsedMs))
            recreatedIdentity = System.identityHashCode(it)
        }
        val record = firstDraw(recreatedIdentity)
        assertEquals(recreatedIdentity, record.preDrawAttempt?.activityIdentity)
        assertEquals(SettingsReadStatus.Loading, record.readStatus)
        assertEquals("Independent observer lost the original retained VM's launch origin",
            originalEntry, record.entryElapsedMs)
        assertTrue("Actual Loading draw occurred before the original literal cap",
            record.drawElapsedMs - originalEntry >= 1_000L)
        assertProjection(record, initialSystemDark, requireSettledConfiguration = false)
        assertFirstAvailablePixel(if (initialSystemDark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt(), recreatedIdentity)
        assertFalse("SPLASH_ORIGIN: retained VM restarted the literal 1000ms Loading cap",
            original.shouldHoldStartup(retainedOrigin + 1_000L, false))
    }

    private fun forceAppAppearance(dark: Boolean?) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val platformMode = when (dark) {
                true -> UiModeManager.MODE_NIGHT_YES
                false -> UiModeManager.MODE_NIGHT_NO
                null -> UiModeManager.MODE_NIGHT_AUTO
            }
            if (Build.VERSION.SDK_INT >= 31) instrumentation.targetContext
                .getSystemService(UiModeManager::class.java).setApplicationNightMode(platformMode)
            AppCompatDelegate.setDefaultNightMode(when (dark) {
                true -> AppCompatDelegate.MODE_NIGHT_YES
                false -> AppCompatDelegate.MODE_NIGHT_NO
                null -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            })
        }
        assertEquals("Test must change app appearance without changing global System appearance",
            initialSystemDark, systemNightMask() == 0x20)
    }

    private fun systemNightMask(): Int =
        (systemWindowContext?.resources ?: Resources.getSystem()).configuration.uiMode and 0x30

    private fun firstDraw(activityIdentity: Int? = null): SettingsDrawObservation {
        // Compose idling while the splash is deliberately held would mask the ordering being tested.
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SettingsDrawProbe.observations.none {
                activityIdentity == null || it.preDrawAttempt?.activityIdentity == activityIdentity
            } && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5L)
        return SettingsDrawProbe.observations.firstOrNull {
            activityIdentity == null || it.preDrawAttempt?.activityIdentity == activityIdentity
        } ?: throw AssertionError("No actual app OnDraw observed for Activity $activityIdentity")
    }

    private fun assertProjection(record: SettingsDrawObservation, dark: Boolean, requireSettledConfiguration: Boolean = true) {
        if (requireSettledConfiguration) assertEquals(if (dark) 0x20 else 0x10, record.resourceNightMask)
        assertEquals(if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt(), record.windowBackground)
        assertEquals(!dark, record.lightStatusBars)
        assertEquals(!dark, record.lightNavigationBars)
    }

    private fun assertFirstAvailablePixel(expected: Int, activityIdentity: Int? = null) {
        fun capture() = if (activityIdentity == null) SettingsDrawProbe.firstAvailableCapture
            else SettingsDrawProbe.observations.firstOrNull {
                it.preDrawAttempt?.activityIdentity == activityIdentity &&
                    it.pixelCopyResult == PixelCopy.SUCCESS && it.bitmap != null
            }
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (capture() == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5L)
        val record = capture() ?: throw AssertionError(
            "No real app PixelCopy succeeded; results=${SettingsDrawProbe.observations.map { it.pixelCopyResult }}",
        )
        assertEquals("A failed capture is not rendering proof", PixelCopy.SUCCESS, record.pixelCopyResult)
        val bitmap = requireNotNull(record.bitmap)
        // Compact launcher page right margin, away from system bars and central empty-state artwork.
        val actual = bitmap.getPixel(bitmap.width - 8, bitmap.height / 2)
        assertTrue("First available app surface pixel ${actual.toUInt().toString(16)} != ${expected.toUInt().toString(16)}",
            listOf(16, 8, 0).all { shift -> abs((actual shr shift and 255) - (expected shr shift and 255)) <= 3 })
    }
}
