package app.duck.notes.startup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.SystemClock
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.settings.SettingsRepository
import app.duck.notes.di.DatabaseModule
import app.duck.notes.di.SettingsModule
import app.duck.notes.di.SettingsScope
import app.duck.notes.di.WelcomeModule
import app.duck.notes.di.WelcomeScope
import app.duck.notes.di.WelcomeStore
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.domain.model.ThemePreference
import app.duck.notes.presentation.MainActivity
import app.duck.notes.presentation.settings.isAppearanceReady
import app.duck.notes.settings.SettingsTestBindings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/**
 * Real coordinator, disk stores, Room and Activity; only the welcome store call is decorated.
 * Run each method in its own process after pm clear, with SettingsTestRunner.
 * Plants: WATCHDOG_NO_RELEASE, SPLASH_STILL_HELD and COMPOSE_STILL_HELD.
 * shortcut: this harness targets API 35; measure physical devices before release.
 */
@HiltAndroidTest
@UninstallModules(SettingsTestBindings::class, WelcomeModule::class)
@RunWith(AndroidJUnit4::class)
class WelcomeStartupGateTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @BindValue @JvmField val stall = WelcomeStorageStall()
    @Inject lateinit var startup: WelcomeStartup
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var database: DuckDatabase
    @Inject @WelcomeStore lateinit var store: DataStore<Preferences>
    @Inject @WelcomeScope lateinit var welcomeScope: CoroutineScope
    @Inject @SettingsScope lateinit var settingsScope: CoroutineScope

    private val observerJob = SupervisorJob()
    @Volatile private var activity: MainActivity? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var notesMs: Long? = null
    @Volatile private var samplingStartMs: Long? = null
    @Volatile private var rootSeen = false
    @Volatile private var settingsAppearanceReady = false
    @Volatile private var harnessError: String? = null
    private var summary = emptyMap<String, Any?>()
    private var boundMs: Long? = null
    private var application: Application? = null
    private val attempts = CopyOnWriteArrayList<PreDrawAttempt>()
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var drawListener: ViewTreeObserver.OnDrawListener? = null
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(created: Activity, savedInstanceState: Bundle?) {
            if (created !is MainActivity) return
            if (activity != null) harnessError = "HARNESS_MULTIPLE_ACTIVITIES"
            activity = created
            val decor = created.window.decorView
            preDrawListener = ViewTreeObserver.OnPreDrawListener {
                val now = SystemClock.elapsedRealtime()
                if (stall.predrawMs == null) {
                    stall.predrawMs = now
                    stall.event("FIRST_PREDRAW", now)
                }
                val attempt = PreDrawAttempt(attempts.size + 1, now, decor)
                attempts += attempt
                main.post { completeAttempt(created, attempt) }
                true
            }
            drawListener = ViewTreeObserver.OnDrawListener {
                attempts.lastOrNull()?.let { attempt ->
                    if (attempt.drawMs == null) {
                        val now = SystemClock.elapsedRealtime()
                        attempt.drawMs = now
                        attempt.outcome = "ACCEPTED"
                        stall.event("DRAW", now, mapOf("id" to attempt.id))
                    }
                } ?: run { harnessError = "HARNESS_DRAW_WITHOUT_PREDRAW" }
            }
            decor.viewTreeObserver.addOnPreDrawListener(requireNotNull(preDrawListener))
            decor.viewTreeObserver.addOnDrawListener(requireNotNull(drawListener))
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @Test fun noStallReleasesBothGates() {
        launch(block = false)
        observeGates()
        assertHarness()
        assertDraw()
        assertNotes()
        assertEquals("healthy startup must finish seeding, not merely time out", WelcomeStartupState.Ready, startup.state.value)
        assertEquals("healthy store call must have returned", 0L, stall.exited.count)
        assertEquals(4, runBlocking { database.noteDao().count() })
        assertSettingsUsable()
    }

    @Test fun blockedStorageReleasesSplash() {
        launch(block = true)
        observeGates()
        assertHarness()
        assertDraw()
        assertSettingsUsable()
    }

    @Test fun blockedStorageReleasesCompose() {
        launch(block = true)
        observeGates()
        assertHarness()
        assertNotes()
        assertSettingsUsable()
        stall.event("LATE_LATCH_RELEASE", SystemClock.elapsedRealtime(), mapOf("pre_release_count" to stall.release.count))
        stall.release.countDown()
        runBlocking { withTimeout(10_000L) { startup.state.first { it == WelcomeStartupState.Ready } } }
        assertEquals("late seeding must produce exactly one tour", 4, runBlocking { database.noteDao().count() })
        startup.start(enabled = false, databaseExistedAtStartup = true)
        assertEquals("a repeated start must not replace the completed decision", WelcomeStartupState.Ready, startup.state.value)
        assertEquals(1, stall.entries.get())
        stall.event("LATE_SEED_COMPLETE", SystemClock.elapsedRealtime())
    }

    private fun launch(block: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val existed = context.getDatabasePath(DuckDatabase.NAME).exists()
        assertFalse("HARNESS_FIRST_RUN_DATABASE_PRESENT: external pm clear required", existed)
        assertFalse("HARNESS_FIRST_RUN_WELCOME_MARKER_PRESENT", context.preferencesDataStoreFile("welcome").exists())
        stall.block = block
        hilt.inject()
        runBlocking { withTimeout(10_000L) { settings.state.first { it.readStatus == SettingsReadStatus.Ready } } }
        application = context as Application
        application?.registerActivityLifecycleCallbacks(callbacks)
        CoroutineScope(observerJob + Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            startup.state.first { it == WelcomeStartupState.Ready }
            val now = SystemClock.elapsedRealtime()
            stall.readyMs = now
            stall.event("READY", now)
        }
        val start = SystemClock.elapsedRealtime()
        stall.startMs = start
        startup.start(enabled = true, databaseExistedAtStartup = existed)
        stall.event("START", start)
        // startActivitySync can wait for an enter animation held by the very splash under test.
        val launch = SystemClock.elapsedRealtime()
        stall.launchMs = launch
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        stall.event("LAUNCH", launch)
    }

    private fun observeGates() {
        val launch = requireNotNull(stall.launchMs)
        while (stall.predrawMs == null && SystemClock.elapsedRealtime() - launch < 30_000L) {
            sample()
            SystemClock.sleep(10L)
        }
        val first = stall.predrawMs
        assertTrue("HARNESS_MAIN_THREAD_UNRESPONSIVE ${stall.fields(SystemClock.elapsedRealtime())}",
            first != null && first - launch <= 30_000L)
        val bound = requireNotNull(first) + 12_000L
        boundMs = bound
        while (SystemClock.elapsedRealtime() < bound) {
            sample(bound)
            SystemClock.sleep(minOf(10L, (bound - SystemClock.elapsedRealtime()).coerceAtLeast(0L)))
        }
        val observed = SystemClock.elapsedRealtime()
        val stateAtBound = startup.state.value
        // StateFlow and timestamped observations need no extra main-queue response at the boundary.
        val draw = attempts.mapNotNull { it.drawMs }.minOrNull()
        val vetoed = attempts.count { it.absMs <= bound && it.outcome == "VETOED" }
        summary = stall.fields(observed) + mapOf(
            "bound_abs_ms" to bound, "bound_observed_abs_ms" to observed,
            "boundary_sample_lag_ms" to (observed - bound),
            "state_at_bound" to stateAtBound.name, "vetoed" to vetoed,
            "accepted_draw_abs_ms" to draw, "notes_abs_ms" to notesMs,
            "sampling_start_abs_ms" to samplingStartMs, "root_seen" to rootSeen,
            "settings_ready" to settingsAppearanceReady, "liveness_passed" to true,
            "latch_closed" to (stall.release.count == 1L), "seam_enter_abs_ms" to stall.enterMs,
            "seam_exit_abs_ms" to stall.exitMs, "ready_abs_ms" to stall.readyMs,
            "actual_veto_observer" to (vetoed > 0), "predraw_count" to attempts.size,
            "pending_at_capture" to attempts.count { it.absMs <= bound && it.outcome == "PENDING" },
            "seam_thread" to stall.enterThread, "seam_block" to stall.enterBlock,
            "observer_error" to harnessError,
            "origin_order" to "T_start<=T_launch<=T_predraw1",
        )
        stall.event("SUMMARY", observed, summary)
    }

    private fun assertHarness() {
        assertEquals("HARNESS_OBSERVER_ERROR", null, harnessError)
        assertTrue("HARNESS_SETTINGS_APPEARANCE_NOT_READY", settingsAppearanceReady)
        assertEquals("HARNESS_STALL_SEAM_NOT_ENTERED_ONCE", 1, stall.entries.get())
        assertTrue("HARNESS_SEAM_ENTRY_NOT_RECORDED", stall.enterMs != null && !stall.enterThread.isNullOrBlank())
        assertEquals("HARNESS_SEAM_BLOCK_FLAG_NOT_RECORDED", stall.block, stall.enterBlock)
        assertTrue("HARNESS_COMPOSE_ROOT_NOT_DISCOVERED", rootSeen)
        assertTrue("HARNESS_SAMPLING_NOT_STARTED", samplingStartMs != null)
        if (stall.block) {
            assertEquals("HARNESS_STALL_RELEASED_BEFORE_ASSERTION", 1L, stall.release.count)
            assertEquals("HARNESS_STALL_EXITED_BEFORE_ASSERTION", 1L, stall.exited.count)
            assertEquals("HARNESS_NO_ACTUAL_SPLASH_VETO", true, summary["actual_veto_observer"])
        }
    }

    private fun assertSettingsUsable() {
        assertEquals(if (stall.block) "Failed" else "Ready", summary["state_at_bound"])
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val clicked = clickAccessible("Settings", "Open sidebar")
        assertTrue("Settings or sidebar must respond", clicked != null)
        if (clicked == "Open sidebar") {
            assertEquals("Settings must respond", "Settings", clickAccessible("Settings"))
        }
        val until = SystemClock.elapsedRealtime() + 5_000L
        while (findAccessible(automation.rootInActiveWindow, "Theme") == null && SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(10L)
        }
        assertTrue("Settings content must be visible", findAccessible(automation.rootInActiveWindow, "Theme") != null)
        assertEquals(if (stall.block) WelcomeStartupState.Failed else WelcomeStartupState.Ready, startup.state.value)
        stall.event("SETTINGS_USABLE", SystemClock.elapsedRealtime())
    }

    private fun findAccessible(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.packageName?.toString() == "app.duck.notes" && node.isVisibleToUser &&
            (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
        for (index in 0 until node.childCount) findAccessible(node.getChild(index), label)?.let { return it }
        return null
    }

    private fun clickAccessible(vararg labels: String): String? {
        val start = SystemClock.elapsedRealtime()
        val until = start + 5_000L
        var firstLookupHadNeither: Boolean? = null
        while (SystemClock.elapsedRealtime() < until) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val matches = labels.map { label -> label to findAccessible(root, label) }
            if (firstLookupHadNeither == null) {
                firstLookupHadNeither = matches.all { it.second == null }
                stall.event("ACCESSIBLE_FIRST_LOOKUP", SystemClock.elapsedRealtime(), mapOf(
                    "labels" to labels.joinToString(","), "had_neither" to firstLookupHadNeither,
                    "root_present" to (root != null)))
            }
            for ((label, found) in matches) {
                var node = found
                while (node != null) {
                    if (node.isClickable && node.isVisibleToUser &&
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        val now = SystemClock.elapsedRealtime()
                        stall.event("ACCESSIBLE_CLICK", now, mapOf("label" to label,
                            "elapsed_ms" to (now - start), "first_lookup_had_neither" to firstLookupHadNeither))
                        return label
                    }
                    node = node.parent
                }
            }
            SystemClock.sleep(10L)
        }
        return null
    }

    private fun details() = JSONObject(summary).toString()

    private fun assertDraw() = assertTrue("WELCOME_NO_ACCEPTED_DRAW_WITHIN_12000_OF_PREDRAW1 ${details()}",
        (summary["accepted_draw_abs_ms"] as Long?)?.let { it <= summary["bound_abs_ms"] as Long } == true)

    private fun assertNotes() = assertTrue("WELCOME_NO_NOTES_NODE_WITHIN_12000_OF_PREDRAW1 ${details()}",
        notesMs?.let { it <= summary["bound_abs_ms"] as Long } == true)

    private fun completeAttempt(created: MainActivity, attempt: PreDrawAttempt) {
        if (attempt.drawMs != null) {
            attempt.outcome = "ACCEPTED"
        } else if (activity !== created || created.window.decorView !== attempt.decor || attempts.lastOrNull() !== attempt) {
            attempt.outcome = "AMBIGUOUS"
        } else {
            val begin = SystemClock.elapsedRealtime()
            val output = StringWriter()
            val writer = PrintWriter(output)
            // Public API35 dump reports the real traversal's freshly reset cancellation reason.
            try { created.dump("", null, writer, emptyArray()) } catch (failure: Exception) {
                harnessError = "HARNESS_DUMP_EXCEPTION $failure"
            }
            writer.flush()
            attempt.dumpDurationMs = SystemClock.elapsedRealtime() - begin
            val reasons = output.toString().lineSequence().map { it.trim() }
                .filter { it.startsWith("mLastPerformTraversalsFailedReason=") }.toList()
            attempt.reason = reasons.singleOrNull()?.substringAfter('=')
            attempt.outcome = if (attempt.reason?.startsWith("predraw_") == true &&
                attempts.lastOrNull() === attempt) "VETOED" else "OTHER"
            if (attempt.outcome == "OTHER" && harnessError == null) harnessError = "HARNESS_NON_VETO_NO_DRAW reason=${attempt.reason}"
        }
        stall.event("PREDRAW", attempt.absMs, mapOf("id" to attempt.id,
            "outcome" to attempt.outcome, "draw_abs_ms" to attempt.drawMs,
            "reason" to attempt.reason, "dump_duration_ms" to attempt.dumpDurationMs))
    }

    private fun sample(bound: Long = Long.MAX_VALUE) {
        mainSlice(minOf(100L, (bound - SystemClock.elapsedRealtime()).coerceAtLeast(1L))) {
            if (SystemClock.elapsedRealtime() >= bound) return@mainSlice
            val resumed = activity ?: return@mainSlice
            if (samplingStartMs == null) {
                val now = SystemClock.elapsedRealtime()
                samplingStartMs = now
                stall.event("SAMPLING_START", now)
            }
            if (resumed.welcomeStartup !== startup) harnessError = "HARNESS_WRONG_COORDINATOR_INSTANCE"
            val theme = MainActivity::class.java.getDeclaredField("appliedTheme").apply { isAccessible = true }.get(resumed) as? ThemePreference
            val dark = MainActivity::class.java.getDeclaredField("appliedDark").apply { isAccessible = true }.get(resumed) as? Boolean
            val resourceDark = resumed.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val systemResources = MainActivity::class.java.getDeclaredMethod("getSystemResources")
                .apply { isAccessible = true }.invoke(resumed) as Resources
            val systemDark = systemResources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            settingsAppearanceReady = isAppearanceReady(settings.state.value, theme, dark, resourceDark, systemDark)
            findRoots(resumed.window.decorView).forEach { root ->
                val node = root.semanticsOwner.rootSemanticsNode
                if (!rootSeen && node.layoutInfo.isPlaced && node.layoutInfo.isAttached &&
                    node.layoutInfo.width > 0 && node.layoutInfo.height > 0) {
                    rootSeen = true
                    stall.event("ROOT_LAID_OUT", SystemClock.elapsedRealtime(), mapOf(
                        "width" to node.layoutInfo.width, "height" to node.layoutInfo.height,
                        "state" to startup.state.value.name, "draw_seen" to attempts.any { it.drawMs != null }))
                }
                if (notesMs == null && hasNotes(node)) {
                    val now = SystemClock.elapsedRealtime()
                    notesMs = now
                    stall.event("NOTES", now)
                }
            }
        }
    }

    private fun mainSlice(waitMs: Long, action: () -> Unit): Boolean {
        val done = CountDownLatch(1)
        val expired = AtomicBoolean(false)
        main.post {
            if (!expired.get()) {
                val begin = SystemClock.elapsedRealtime()
                try { action() } catch (failure: Exception) {
                    harnessError = "HARNESS_MAIN_SLICE_EXCEPTION $failure"
                } finally {
                    val duration = SystemClock.elapsedRealtime() - begin
                    if (duration > 100L) stall.event("MAIN_SLICE_LAG", SystemClock.elapsedRealtime(), mapOf("duration_ms" to duration))
                }
            }
            done.countDown()
        }
        val completed = done.await(waitMs, TimeUnit.MILLISECONDS)
        if (!completed) expired.set(true)
        return completed
    }

    private fun findRoots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(findRoots(view.getChildAt(index)))
    }

    private fun hasNotes(node: SemanticsNode): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Notes" } == true ||
            node.children.any(::hasNotes)

    @After fun close() {
        val releaseMs = SystemClock.elapsedRealtime()
        stall.event("LATCH_RELEASE", releaseMs, mapOf("pre_release_count" to stall.release.count))
        stall.release.countDown()
        try {
            if (stall.entries.get() > 0) assertTrue("HARNESS_STALL_THREAD_LEAK", stall.exited.await(10L, TimeUnit.SECONDS))
            if (::startup.isInitialized) runBlocking {
                withTimeout(10_000L) { startup.state.first { it != WelcomeStartupState.Loading } }
            }
        } finally {
            try {
                assertTrue("HARNESS_TEARDOWN_MAIN_THREAD_UNRESPONSIVE", mainSlice(10_000L) {
                    activity?.window?.decorView?.viewTreeObserver?.let { observer ->
                        if (observer.isAlive) {
                            preDrawListener?.let(observer::removeOnPreDrawListener)
                            drawListener?.let(observer::removeOnDrawListener)
                        }
                    }
                    activity?.finish()
                })
                val closeStart = SystemClock.elapsedRealtime()
                while (activity?.isDestroyed == false && SystemClock.elapsedRealtime() - closeStart < 10_000L) SystemClock.sleep(10L)
                assertTrue("HARNESS_ACTIVITY_TEARDOWN_FAILED", activity?.isDestroyed != false)
            } finally {
                try {
                    runBlocking {
                        withTimeout(10_000L) {
                            observerJob.cancelAndJoin()
                            if (::welcomeScope.isInitialized) welcomeScope.coroutineContext[Job]?.cancelAndJoin()
                            if (::settingsScope.isInitialized) settingsScope.coroutineContext[Job]?.cancelAndJoin()
                        }
                    }
                } finally {
                    if (::database.isInitialized) database.close()
                    application?.unregisterActivityLifecycleCallbacks(callbacks)
                }
            }
        }
        boundMs?.let { bound ->
            val beforeBound = attempts.filter { it.absMs <= bound }
            stall.event("FINAL_BOUND_COUNTS", SystemClock.elapsedRealtime(), mapOf(
                "bound_abs_ms" to bound,
                "vetoed_at_bound" to beforeBound.count { it.outcome == "VETOED" },
                "accepted_draw_abs_ms" to attempts.mapNotNull { it.drawMs }.filter { it <= bound }.minOrNull(),
                "pending_at_bound" to beforeBound.count { it.outcome == "PENDING" },
                "ambiguous_at_bound" to beforeBound.count { it.outcome == "AMBIGUOUS" },
                "other_at_bound" to beforeBound.count { it.outcome == "OTHER" },
                "predraw_count_at_bound" to beforeBound.size))
        }
        stall.event("TEARDOWN_COMPLETE", SystemClock.elapsedRealtime(), mapOf(
            "release_abs_ms" to releaseMs, "seam_exit_abs_ms" to stall.exitMs,
            "activity_destroyed" to (activity?.isDestroyed != false), "harness_error" to harnessError))
    }

    @Module(includes = [SettingsModule::class, DatabaseModule::class])
    @InstallIn(SingletonComponent::class)
    object RealBindings {
        @Provides @Singleton @WelcomeScope
        fun scope(): CoroutineScope = WelcomeModule.provideScope()

        @Provides @Singleton @WelcomeStore
        fun store(@ApplicationContext context: Context, @WelcomeScope scope: CoroutineScope,
                  stall: WelcomeStorageStall): DataStore<Preferences> {
            val delegate = WelcomeModule.provideStore(context, scope)
            return object : DataStore<Preferences> {
                override val data = delegate.data
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                    stall.entries.incrementAndGet()
                    val entered = SystemClock.elapsedRealtime()
                    stall.enterMs = entered
                    stall.enterThread = Thread.currentThread().name
                    stall.enterBlock = stall.block
                    stall.event("SEAM_ENTER", entered, mapOf("block" to stall.enterBlock, "thread" to stall.enterThread))
                    if (stall.block) {
                        while (stall.release.count != 0L) {
                            try { stall.release.await() } catch (_: InterruptedException) { /* Deliberately ignores cancellation. */ }
                        }
                    }
                    stall.event("SEAM_WRITE_START", SystemClock.elapsedRealtime())
                    return try { delegate.updateData(transform) } finally {
                        val exited = SystemClock.elapsedRealtime()
                        stall.exitMs = exited
                        stall.event("SEAM_EXIT", exited)
                        stall.exited.countDown()
                    }
                }
            }
        }
    }
}

private data class PreDrawAttempt(val id: Int, val absMs: Long, val decor: View) {
    @Volatile var outcome = "PENDING"
    @Volatile var drawMs: Long? = null
    var reason: String? = null
    var dumpDurationMs = 0L
}
