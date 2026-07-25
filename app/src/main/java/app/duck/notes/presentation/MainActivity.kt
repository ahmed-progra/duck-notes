package app.duck.notes.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.duck.notes.core.theme.DuckTheme
import dagger.hilt.android.AndroidEntryPoint

/** Single launcher entry point. Hosts Compose content under the Duck theme, edge-to-edge. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DuckTheme {
                DuckScreen()
            }
        }
    }
}
