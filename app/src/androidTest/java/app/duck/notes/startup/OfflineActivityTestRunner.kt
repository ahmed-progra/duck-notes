package app.duck.notes.startup

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Dedicated real-Application runner; ordinary Hilt fixtures must never select this test. */
class OfflineActivityTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        val selected = Bundle(arguments ?: Bundle()).apply {
            remove("notClass")
            putString("class", OfflineActivityTest::class.java.name)
        }
        super.onCreate(selected)
    }
}
