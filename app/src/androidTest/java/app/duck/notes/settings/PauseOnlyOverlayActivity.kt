package app.duck.notes.settings

import android.app.Activity
import android.os.Bundle
import android.widget.Button

/** Test-only translucent external Activity: pauses Duck without stopping it. */
class PauseOnlyOverlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(Button(this).apply {
            text = "Close security test overlay"
            setOnClickListener { finish() }
        })
    }
}
