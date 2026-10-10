package app.duck.notes.settings

import app.duck.notes.presentation.settings.shouldKeepStartupSplash
import app.duck.notes.domain.model.ThemePreference
import app.duck.notes.domain.model.effectiveDark
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.duck.notes.data.settings.SettingsRepository
import app.duck.notes.data.settings.createSettingsDataStore
import app.duck.notes.domain.model.SettingsNotice
import app.duck.notes.presentation.settings.SettingsViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calls production helpers with independent literal expectations.
 * THEME_TABLE changes System -> !systemDark in effectiveDark.
 * SPLASH_CAP changes the production 1_000L expression to 10_000L.
 * STARTUP_ORIGIN changes retainFirstLaunch's null condition to true.
 * NATIVE_WINDOW changes values/colors.xml duck_window_surface from #FFFFFF to #FEFEFE.
 * Activity configuration readiness, retained origin and real first drawing are tested by
 * the separate Activity instrumentation guard, not reconstructed in this unit test.
 */
class ThemePreferenceTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun systemFollowsBothSystemModesAndForcedChoicesIgnoreThem() {
        assertFalse(effectiveDark(ThemePreference.System, false))
        assertTrue(effectiveDark(ThemePreference.System, true))
        assertFalse(effectiveDark(ThemePreference.Light, false))
        assertFalse(effectiveDark(ThemePreference.Light, true))
        assertTrue(effectiveDark(ThemePreference.Dark, false))
        assertTrue(effectiveDark(ThemePreference.Dark, true))
    }

    @Test fun startupHoldHasALiteralOneThousandMillisecondCap() {
        assertTrue(shouldKeepStartupSplash(12_000L, 12_999L, false))
        assertFalse(shouldKeepStartupSplash(12_000L, 13_000L, false))
        assertFalse(shouldKeepStartupSplash(12_000L, 13_001L, false))
    }

    @Test fun completedAppearanceReleasesBeforeAndAfterTheCap() {
        assertFalse(shouldKeepStartupSplash(12_000L, 12_001L, true))
        assertFalse(shouldKeepStartupSplash(12_000L, 13_001L, true))
    }

    @Test fun nativeWindowAndIconPlateXmlColorsMatchLiteralLightAndDarkValues() {
        assertXmlColor("src/main/res/values/colors.xml", "duck_window_surface", "#FFFFFF")
        assertXmlColor("src/main/res/values-night/colors.xml", "duck_window_surface", "#1C1C1E")
        assertXmlColor("src/main/res/values/colors.xml", "duck_splash_icon_surface", "#F7F7F8")
        assertXmlColor("src/main/res/values-night/colors.xml", "duck_splash_icon_surface", "#F2F2F5")
    }

    private fun assertXmlColor(path: String, name: String, expected: String) {
        val file = File(path)
        assertTrue("Native color resource was not actually read: ${file.absolutePath}", file.isFile)
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("color")
        val matches = (0 until nodes.length).map { nodes.item(it) }
            .filter { it.attributes.getNamedItem("name")?.nodeValue == name }
            .map { it.textContent.trim() }
        org.junit.Assert.assertEquals("Missing, duplicate or incorrect native token $name in $path",
            listOf(expected), matches)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun actualRetainedViewModelKeepsTheFirstOriginAndLoadingCannotReleaseEarly() = runBlocking {
        val main = UnconfinedTestDispatcher()
        val storeDispatcher = StandardTestDispatcher()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + storeDispatcher)
        val notices = MutableStateFlow<SettingsNotice?>(null)
        val store = createSettingsDataStore(File(temporary.newFolder(), "settings.preferences_pb"), scope, notices)
        Dispatchers.setMain(main)
        val viewModel = SettingsViewModel(SettingsRepository(store, notices, scope), SavedStateHandle())
        try {
            viewModel.retainFirstLaunch(0L)
            assertTrue(viewModel.shouldHoldStartup(999L, appearanceReady = true))
            viewModel.retainFirstLaunch(900L)
            assertFalse(viewModel.shouldHoldStartup(1_000L, appearanceReady = false))
            main.scheduler.runCurrent()
            assertTrue(viewModel.uiState.value.startupTimedOut)
            viewModel.markAppearanceReady()
            main.scheduler.runCurrent()
            assertTrue("Loading cannot clear the temporary-defaults notice", viewModel.uiState.value.startupTimedOut)
        } finally {
            viewModel.viewModelScope.cancel()
            job.cancel()
            storeDispatcher.scheduler.advanceUntilIdle()
            job.join()
            Dispatchers.resetMain()
        }
    }
}
