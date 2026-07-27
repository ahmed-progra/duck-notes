package app.duck.notes.presentation.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duck.notes.R
import app.duck.notes.core.icons.DuckIcon
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.ui.DuckEmptyState
import app.duck.notes.core.ui.DuckIconButton
import app.duck.notes.presentation.markdown.MarkdownEditorScreen
import kotlinx.coroutines.launch

/**
 * The three-pane shell: sidebar, note list, editor.
 *
 * ## Panes come from state, not from routes
 *
 * There is no `NavHost`. A list-detail layout that also has a `NavHost` keeps `selectedNoteId` in
 * two places — route args and the ViewModel — and every Expanded↔Compact resize then has to
 * reconcile a back stack against pane state. Deriving the panes from one piece of state removes the
 * reconciliation rather than implementing it, and back becomes four lines of [BackHandler].
 *
 * Likewise there is no `WindowSizeClass`: [paneModeFor] is a pure function over the width
 * [BoxWithConstraints] already measures, so the breakpoints are a JVM unit test instead of a manual
 * resize on a device. `calculateWindowSizeClass` is experimental, soft-deprecated in favour of the
 * adaptive libraries, needs an `Activity` handle and a dependency, and could not be tested here.
 *
 * Both are deviations from the phase spec, recorded in `DUCK_NOTES_BUILD_PROMPT.md`.
 *
 * ## Two Material alpha composites reach the screen, and both are exempt
 *
 * Phase 5's prerequisite is about **a colour on screen that is in no palette** — `surfaceTint`'s
 * blend and the chip's `0.38`/`0.12` disabled alphas are instances of that, not its definition. No
 * component here gains `enabled`, but two composites arrive anyway. Both were read out of the
 * `material3 1.4.0` bytecode — the version the build resolves, not the stale 1.3.0 AAR also sitting
 * in the Gradle cache — rather than recalled:
 *
 * - **The drawer scrim.** `DrawerDefaults.getScrimColor` reads `ScrimTokens.ContainerColor` and
 *   applies `Color.copy(alpha = 0.32f)`. Our projection pins that token to black, so it is black at
 *   32%. **Exempt: it is occlusion by design.** Its whole function is to make what is behind it not
 *   readable, and 1.4.3 governs text meant to be perceived. What must stay legible is the sheet in
 *   front, a fully opaque `surfaceElevated` fill whose pairs are all already floored.
 * - **The ripple.** `RippleDefaults.RippleAlpha` is `RippleAlpha(0.16f, 0.10f, 0.08f, 0.10f)` —
 *   `material-ripple`'s field order is dragged, focused, hovered, pressed, so pressed is 0.10f —
 *   resolved against `LocalContentColor`, since the default `RippleConfiguration` leaves colour
 *   `Unspecified`. So a pressed sidebar row composites `onSurface` at 10% over `surfaceElevated`.
 *   **Exempt: it is transient feedback, not a state.** It lives between press and release and says
 *   nothing the accent bar and the SemiBold weight do not, both of which survive the finger lifting.
 *
 * Neither can live in `ContrastTest`: that suite compares two opaque colours, which is exactly the
 * property both of these lack. Recorded here and in `ContrastTest.duckPairs`' KDoc, because the
 * prerequisite forbids silence, not alpha.
 */
@Composable
fun DuckShell(modifier: Modifier = Modifier, viewModel: DuckShellViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DuckShell(
        state = state,
        actions = ShellActions(
            onFilter = viewModel::onFilter,
            onSelectNote = viewModel::onSelectNote,
            onClearSelection = viewModel::onClearSelection,
            onToggleTag = viewModel::onToggleTag,
        ),
        modifier = modifier,
    )
}

/**
 * The stateless half. Ordinary Compose state hoisting, with two real callers: [DuckShell] above and
 * the debug gallery, which drives it from a fixture so the eight palettes can be checked against a
 * populated list rather than whatever happens to be in the database.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuckShell(
    state: ShellUiState,
    actions: ShellActions,
    modifier: Modifier = Modifier,
) {
    val colors = DuckTheme.colors

    BoxWithConstraints(modifier.fillMaxSize().background(colors.surface)) {
        val mode = paneModeFor(maxWidth)
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        val sidebar: @Composable () -> Unit = {
            DuckSidebar(
                filter = state.filter,
                pinnedTags = state.pinnedTags,
                tagRows = state.tagRows,
                onFilter = {
                    actions.onFilter(it)
                    scope.launch { drawer.close() }
                },
                onToggleTag = actions.onToggleTag,
            )
        }

        val list: @Composable (Modifier) -> Unit = { m ->
            DuckNoteList(
                filter = state.filter,
                notes = state.notes,
                selectedNoteId = state.selectedNoteId,
                onSelectNote = actions.onSelectNote,
                modifier = m,
            )
        }

        val editor: @Composable (Modifier) -> Unit = { m ->
            if (state.selectedNoteId == null) {
                DuckEmptyState(stringResource(R.string.shell_no_note_selected), m)
            } else {
                MarkdownEditorScreen(text = state.selectedNoteBody, modifier = m)
            }
        }

        // statusBarsPadding on the pane rows, not on the root: only Compact routes through a
        // `Scaffold` (which supplies insets) and only the drawer sheet gets them from
        // `ModalDrawerSheet`. Expanded and Medium lay panes out directly, so without this the
        // sidebar's first row draws under the clock — and it does so ONLY on wide screens, which is
        // the kind of defect a phone-only check never sees.
        // The VerticalDividers below are NOT decoration — do not remove them to "let the elevation do
        // the work". `surfaceElevated on surface` is 1.06 in SepiaLight, lowest of the eight, and
        // checked on device the panes do not separate as planes there at all: the elevated fill
        // contributes nothing and the rule is what marks the boundary. On the dark palettes
        // (1.13-1.19, MidnightDark best) the planes do separate unaided, which is exactly why a
        // reviewer looking at one dark theme would conclude the lines are redundant.
        //
        // Not the *sole* cue — nav rows, cards and prose differ by content, and that second cue is
        // what keeps `divider` exempt from a 1.4.11 floor at all. So "do not delete" is a Duck design
        // rule, not a WCAG one, and is negotiable with eyes open. The numbers and the full argument
        // live in ContrastTest.dividerAndElevationAreRecorded.
        when (mode) {
            PaneMode.Expanded -> Row(Modifier.fillMaxSize().statusBarsPadding()) {
                androidx.compose.foundation.layout.Box(Modifier.width(SIDEBAR)) { sidebar() }
                VerticalDivider(color = colors.divider)
                list(Modifier.width(NOTE_LIST))
                VerticalDivider(color = colors.divider)
                editor(Modifier.weight(1f))
            }

            // Medium keeps the sidebar in the drawer and shows list + editor together: at 600-839dp
            // three panes leave the editor too narrow to read a serif body in.
            PaneMode.Medium -> DrawerHost(drawer, sidebar) {
                Row(Modifier.fillMaxSize().statusBarsPadding()) {
                    list(Modifier.width(NOTE_LIST))
                    VerticalDivider(color = colors.divider)
                    editor(Modifier.weight(1f))
                }
            }

            PaneMode.Compact -> DrawerHost(drawer, sidebar) {
                // Back walks editor -> list, then falls through to the system, which exits. On the
                // wider modes both panes are already visible, so the handler stays disabled and back
                // exits directly — there is nothing stacked to pop.
                BackHandler(enabled = state.selectedNoteId != null, onBack = actions.onClearSelection)

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        // statusBarsPadding, because `Scaffold`'s topBar slot does NOT apply window
                        // insets to arbitrary content — only its own `TopAppBar` does. Without it
                        // the button draws over the clock under `enableEdgeToEdge`.
                        DuckIconButton(
                            icon = DuckIcon.Tag,
                            contentDescription = stringResource(R.string.shell_open_sidebar),
                            onClick = { scope.launch { drawer.open() } },
                            modifier = Modifier.statusBarsPadding(),
                        )
                    },
                ) { padding ->
                    if (state.selectedNoteId == null) {
                        list(Modifier.padding(padding))
                    } else {
                        editor(Modifier.padding(padding))
                    }
                }
            }
        }
    }
}

/**
 * `ModalNavigationDrawer` with the sheet container named explicitly.
 *
 * `ModalDrawerSheet` defaults `drawerContainerColor` to `surfaceContainerLow`, which our projection
 * maps to `surface` — so the sidebar would silently render on the page colour rather than the
 * elevated one, and only on phones. The two panes would then disagree about what a sidebar is.
 */
@Composable
private fun DrawerHost(
    drawer: androidx.compose.material3.DrawerState,
    sidebar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = DuckTheme.colors.surfaceElevated,
                drawerContentColor = DuckTheme.colors.onSurface,
            ) {
                sidebar()
            }
        },
        content = content,
    )
}

private val SIDEBAR = 280.dp
private val NOTE_LIST = 320.dp
