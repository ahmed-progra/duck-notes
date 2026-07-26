package app.duck.notes.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Debug-only host for [DuckGalleryScreen]. Not in the release APK: `src/debug` is never compiled into
 * a release variant, and nothing in `src/main` references this — which is also the proof, since
 * `assembleRelease` would fail to compile if anything did.
 *
 * Deliberately has **no** LAUNCHER intent filter, so it adds no second home-screen icon. Start it by
 * hand:
 * ```
 * adb shell am start -n app.duck.notes/app.duck.notes.gallery.DuckGalleryActivity
 * ```
 *
 * A plain [ComponentActivity], not `@AndroidEntryPoint`, because as of Phase 5a the gallery has no
 * injected dependencies and `MarkdownEditorScreen` is self-contained — no ViewModel, no repository,
 * state that dies with the composable.
 *
 * **When Phase 6 or 8 gives the editor a ViewModel, this class must gain `@AndroidEntryPoint`** or it
 * will crash at launch the first time `hiltViewModel()` is called. `DuckApp` is already
 * `@HiltAndroidApp`, so it is a one-annotation change — the point of this note is that the failure is
 * diagnosed here rather than discovered on a device.
 */
class DuckGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DuckGalleryScreen() }
    }
}
