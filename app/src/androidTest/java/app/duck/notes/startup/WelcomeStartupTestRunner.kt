package app.duck.notes.startup

import android.os.Bundle
import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import app.duck.notes.DuckApp

/** Leaves the real production Application selected and runs only its dedicated startup test. */
class WelcomeStartupTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        val selected = Bundle(arguments ?: Bundle())
        selected.remove("notClass")
        selected.putString("class", WelcomeStartupTest::class.java.name)
        super.onCreate(selected)
    }
}

/** Creates the old empty or explicitly populated database before production startup is exercised. */
class WelcomeExistingInstallTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, WelcomeDisabledTestApp::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        val selected = Bundle(arguments ?: Bundle())
        selected.remove("notClass")
        selected.putString("class", WelcomeStartupTest::class.java.name)
        val scenario = selected.getString("scenario") ?: "prepare_existing"
        require(scenario == "prepare_existing" || scenario == "prepare_existing_populated") {
            "Unknown existing-install preparation scenario: $scenario"
        }
        selected.putString("scenario", scenario)
        super.onCreate(selected)
    }
}

/** Explicit existing-install fixture only; ordinary discovery uses the pending Settings runner. */
class WelcomeDisabledTestApp : DuckApp() {
    override val welcomeSeedingEnabled = false
}
