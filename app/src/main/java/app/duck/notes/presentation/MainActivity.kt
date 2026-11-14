package app.duck.notes.presentation

import android.app.KeyguardManager
import android.app.UiModeManager
import android.content.Intent
import android.provider.Settings
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.duck.notes.R
import app.duck.notes.presentation.shell.DuckShellViewModel
import app.duck.notes.presentation.settings.AppLockScreen
import app.duck.notes.presentation.settings.AppLockSession
import app.duck.notes.presentation.settings.mayShowNotes
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.theme.DuckThemeSpec
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.domain.model.ThemePreference
import app.duck.notes.domain.model.effectiveDark
import app.duck.notes.presentation.settings.SettingsViewModel
import app.duck.notes.presentation.settings.isAppearanceReady
import app.duck.notes.startup.WelcomeStartup
import app.duck.notes.startup.WelcomeStartupState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var welcomeStartup: WelcomeStartup
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val shellOwner = viewModels<DuckShellViewModel>()
    private val shellViewModel by shellOwner
    private val lockSession: AppLockSession by viewModels()
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private var deviceSecure by mutableStateOf(false)
    private val authenticate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        lockSession.finish(result.resultCode == RESULT_OK)?.let(settingsViewModel::setLockEnabled)
    }
    private var appliedTheme: ThemePreference? = null
    private var appliedDark: Boolean? = null
    // A display-area context excludes the persisted app override from the System appearance.
    private val systemWindowContext by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
        } else null
    }
    private val systemResources get() = systemWindowContext?.resources ?: Resources.getSystem()
    private var fallbackProjected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val firstEntry = SystemClock.elapsedRealtime()
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settingsViewModel.retainFirstLaunch(firstEntry)
        splash.setKeepOnScreenCondition {
            val snapshot = settingsViewModel.snapshot.value
            val ready = isAppearanceReady(snapshot, appliedTheme, appliedDark,
                isNight(resources.configuration), isNight(systemResources.configuration))
            val keep = settingsViewModel.shouldHoldStartup(SystemClock.elapsedRealtime(), ready)
            if (!keep && settingsViewModel.snapshot.value.readStatus == SettingsReadStatus.Loading && !fallbackProjected) {
                fallbackProjected = true
                applyAppearance(ThemePreference.System)
            }
            keep || welcomeStartup.state.value == WelcomeStartupState.Loading
        }
        projectWindow(isNight(resources.configuration))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.snapshot.collect { snapshot ->
                    if (snapshot.readStatus != SettingsReadStatus.Loading) {
                        applyAppearance(snapshot.settings.theme)
                    }
                }
            }
        }
        setContent {
            val ui by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val securitySnapshot by settingsViewModel.snapshot.collectAsState()
            val lock by lockSession.state.collectAsState()
            val welcomeState by welcomeStartup.state.collectAsStateWithLifecycle()
            // Register configuration invalidation while sampling System outside the app override.
            val configuration = LocalConfiguration.current
            val systemDark = isNight(if (ui.snapshot.settings.theme == ThemePreference.System) systemResources.configuration else configuration)
            val dark = effectiveDark(ui.snapshot.settings.theme, systemDark)
            DuckTheme(dark = dark) {
                if (welcomeState != WelcomeStartupState.Loading &&
                    (ui.snapshot.readStatus != SettingsReadStatus.Loading || ui.startupTimedOut)) {
                    if (mayShowNotes(securitySnapshot, lock.authenticated) && !lock.authenticating) {
                        DuckScreen(settingsViewModel = settingsViewModel, shellViewModel = shellViewModel,
                            canAuthenticate = deviceSecure, onLockChange = ::requestLockChange)
                    } else {
                        AppLockScreen(deviceSecure, lock.authenticating, onUnlock = { requestAuthentication() },
                            onRetry = settingsViewModel::retryRead, onConfigureLock = ::configureDeviceLock)
                    }
                } else {
                    Box(Modifier.fillMaxSize().background(DuckTheme.colors.surface))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        deviceSecure = keyguard.isDeviceSecure
    }

    public override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        lockWhenInactive(changingConfigurations = false)
    }

    override fun onPause() {
        super.onPause()
        lockWhenInactive(isChangingConfigurations)
    }

    @androidx.annotation.RequiresApi(29)
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (!isTopResumedActivity) lockWhenInactive(isChangingConfigurations)
    }

    override fun onStop() {
        super.onStop()
        lockWhenInactive(isChangingConfigurations)
    }

    private fun lockWhenInactive(changingConfigurations: Boolean) {
        // The editor can be absent from composition while authentication is showing.
        if (shellOwner.isInitialized()) shellViewModel.onStop()
        lockSession.background(changingConfigurations)
    }

    private fun requestLockChange(enabled: Boolean) {
        requestAuthentication(enabled)
    }

    @Suppress("DEPRECATION")
    private fun requestAuthentication(changeLock: Boolean? = null) {
        val intent = keyguard.createConfirmDeviceCredentialIntent(getString(R.string.app_name), getString(R.string.lock_message))
        if (intent == null) {
            configureDeviceLock()
            return
        }
        if (changeLock != null) shellViewModel.onStop()
        if (!lockSession.begin(changeLock)) return
        try {
            authenticate.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            lockSession.finish(false)
        } catch (_: SecurityException) {
            lockSession.finish(false)
        }
    }

    private fun configureDeviceLock() {
        try {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        } catch (_: android.content.ActivityNotFoundException) {
            deviceSecure = false
        } catch (_: SecurityException) {
            deviceSecure = false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val snapshot = settingsViewModel.snapshot.value
        if (snapshot.readStatus != SettingsReadStatus.Loading) applyAppearance(snapshot.settings.theme)
    }

    private fun applyAppearance(preference: ThemePreference) {
        appliedTheme = null
        appliedDark = null
        val platformMode = when (preference) {
            ThemePreference.System -> UiModeManager.MODE_NIGHT_AUTO
            ThemePreference.Light -> UiModeManager.MODE_NIGHT_NO
            ThemePreference.Dark -> UiModeManager.MODE_NIGHT_YES
        }
        val compatMode = when (preference) {
            ThemePreference.System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemePreference.Light -> AppCompatDelegate.MODE_NIGHT_NO
            ThemePreference.Dark -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (Build.VERSION.SDK_INT >= 31) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            getSystemService(UiModeManager::class.java)?.setApplicationNightMode(platformMode)
        } else {
            AppCompatDelegate.setDefaultNightMode(compatMode)
        }
        val dark = effectiveDark(preference, isNight(systemResources.configuration))
        projectWindow(dark)
        appliedTheme = preference
        appliedDark = dark
        if (isAppearanceReady(settingsViewModel.snapshot.value, appliedTheme, appliedDark,
                isNight(resources.configuration), isNight(systemResources.configuration))) {
            settingsViewModel.markAppearanceReady()
        }
    }

    private fun projectWindow(dark: Boolean) {
        val surface = DuckThemeSpec.DuckRed.colors(dark).surface.toArgb()
        window.setBackgroundDrawable(ColorDrawable(surface))
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(surface, surface) { dark },
            navigationBarStyle = SystemBarStyle.auto(surface, surface) { dark },
        )
    }

    private fun isNight(configuration: Configuration) =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
