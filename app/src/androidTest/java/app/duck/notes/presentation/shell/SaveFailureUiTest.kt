package app.duck.notes.presentation.shell

import androidx.activity.compose.LocalActivityResultRegistryOwner

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.core.theme.DuckTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaveFailureUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun saveFailureRemainsVisibleAndRetryReachesTheRealShellAction() {
        assertSaveFailure(
            language = "en",
            direction = LayoutDirection.Ltr,
            message = "Your edits were not saved. Try again before leaving.",
            retry = "Try again",
        )
    }

    @Test
    fun arabicSaveFailureRemainsVisibleAndRetryReachesTheRealShellAction() {
        assertSaveFailure(
            language = "ar",
            direction = LayoutDirection.Rtl,
            message = "لم تُحفظ تعديلاتك. حاول مجددًا قبل المغادرة.",
            retry = "حاول مجددًا",
        )
    }

    private fun assertSaveFailure(language: String, direction: LayoutDirection, message: String, retry: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = Locale.forLanguageTag(language)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        val localizedContext = context.createConfigurationContext(configuration)
        val failed = mutableStateOf(true)
        var retries = 0
        rule.setContent {
            val registry = requireNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides registry,
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
                LocalLayoutDirection provides direction,
            ) {
                DuckTheme {
                    Box(Modifier.requiredSize(360.dp, 640.dp)) {
                        DuckShell(
                            state = ShellUiState(saveFailed = failed.value),
                            buffer = TextFieldState(),
                            actions = ShellActions(onRetryNavigation = { retries++ }),
                        )
                    }
                }
            }
        }
        rule.onNodeWithText(message).assertIsDisplayed()
        rule.onNodeWithText(retry).assertIsDisplayed().assertHasClickAction().performClick()
        rule.runOnIdle { assertEquals(1, retries) }
        rule.onNodeWithText(message).assertIsDisplayed()
        rule.runOnIdle { failed.value = false }
        rule.onNodeWithText(message).assertDoesNotExist()
    }
}
