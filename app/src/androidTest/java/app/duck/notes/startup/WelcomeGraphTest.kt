package app.duck.notes.startup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.Application
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ordinary discovery includes this real disabled coordinator. WELCOME_DISABLE_BYPASSED turns it RED. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WelcomeGraphTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var startup: WelcomeStartup

    @Test
    fun ordinaryGraphSkipsStartupWithoutClaimingTheTour() {
        hilt.inject()
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(WelcomeStartupState.Skipped, startup.state.value)
        assertFalse(app.filesDir.resolve("datastore/welcome.preferences_pb").exists())
    }
}
