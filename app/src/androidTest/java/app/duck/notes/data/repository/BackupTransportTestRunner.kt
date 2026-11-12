package app.duck.notes.data.repository

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Real DuckApp graph; only explicit disposable-emulator transport scenarios may select this test. */
class BackupTransportTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        val selected = Bundle(arguments ?: Bundle()).apply {
            remove("notClass")
            putString("class", BackupTransportTest::class.java.name)
        }
        super.onCreate(selected)
    }
}
