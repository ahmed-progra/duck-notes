package app.duck.notes.presentation.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 8a's saver drop, asserted by a scan of `src/main` — because the obvious assertion does not work.
 *
 * ## The guard this replaces could not fail
 *
 * Two earlier guards inspected `SavedStateHandle` for an oversized value. **`rememberSaveable` never
 * goes near `SavedStateHandle`.** It writes to the composition's `SaveableStateRegistry`, which the
 * *Activity* collects into `onSaveInstanceState`; `SavedStateHandle` is a separate channel keyed under
 * the ViewModel. Both land in the same `Bundle` and contend for the same 1MB Binder buffer, which is
 * why the drop matters — but reading one says nothing about the other.
 *
 * That was not reasoned, it was measured: `rememberSaveable(saver = TextFieldState.Saver)` was
 * temporarily reintroduced into [MarkdownEditorScreen] and **both guards still reported green**.
 * Neither test composes anything, so neither could ever have observed a saver.
 *
 * ## Why a source scan rather than a rotation test
 *
 * A rotation asserting "no `TransactionTooLargeException`" needs a note big enough to blow a 1MB
 * buffer *and* a device, and it fails as a crash in a test process rather than as a readable
 * assertion. The property actually being defended is textual — *the editor holds no saveable state* —
 * and `TokenSourceTest` already established that a source scan is how this repo pins a textual
 * invariant. This follows its idiom exactly, including proving the scan resolved and that the
 * detector catches a planted offender: a scan that finds nothing because its path is wrong reports
 * green, which is the failure mode the whole file exists to avoid.
 *
 * The scan is deliberately narrow — `presentation/markdown/` and `presentation/shell/`. The banned
 * call is legitimate elsewhere: `DuckShell` uses `rememberSaveable` for nothing, but a future settings
 * screen saving a scroll position is fine. What must never come back is a *note body* in the Bundle.
 */
class EditorSaverSourceTest {

    @Test
    fun theScannedDirectoriesResolve() {
        SCANNED.forEach {
            assertTrue("no sources at ${it.absolutePath} — unit tests run with `app/` as the " +
                "working directory, so this path is relative to it", it.isDirectory)
        }
    }

    @Test
    fun theScanSeesTheEditorAndTheShell() {
        val names = sources().map { it.name }.toSet()
        assertTrue(
            "scan missed MarkdownEditorScreen.kt — it saw $names",
            "MarkdownEditorScreen.kt" in names,
        )
        assertTrue("scan missed DuckShell.kt — it saw $names", "DuckShell.kt" in names)
    }

    @Test
    fun detectorFlagsPlantedOffenders() {
        val dirty =
            """
            val a = rememberSaveable(text, saver = TextFieldState.Saver) { TextFieldState(text) }
            val b = rememberTextFieldState(body)
            """.trimIndent()
        assertEquals(
            listOf("TextFieldState.Saver", "rememberTextFieldState"),
            offendersIn(dirty),
        )
    }

    /**
     * The detector reads code, not prose — and this is not a nicety.
     *
     * Both banned strings appear in [MarkdownEditorScreen]'s own KDoc, which explains at length what
     * was removed and why. A scanner that cannot tell a doc comment from a call site would flag the
     * explanation of the fix as the defect, and the only way to keep it green would be to delete the
     * documentation. First run of this test did exactly that.
     */
    @Test
    fun detectorIgnoresCommentsAndDocumentation() {
        val documented =
            """
            /**
             * Used to hold rememberSaveable(text, saver = TextFieldState.Saver), which put the whole
             * body in the Bundle. Do not reintroduce rememberTextFieldState here either.
             */
            // rememberTextFieldState(SAMPLE_NOTE) — also fine, it is commented out
            fun clean() = Unit
            """.trimIndent()
        assertEquals(emptyList<String>(), offendersIn(documented))
    }

    /**
     * The assertion proper: neither the editor nor the shell may hold a note body across process death.
     *
     * `rememberTextFieldState` is banned by name as well as the raw `Saver`, because it *is* the saver
     * — it expands to `rememberSaveable(saver = TextFieldState.Saver)` — and reads as innocuous.
     */
    @Test
    fun neitherTheEditorNorTheShellHoldsASaveableBuffer() {
        val offenders = sources().flatMap { file ->
            offendersIn(file.readText()).map { "${file.name}: $it" }
        }
        assertEquals(
            "a note body is back in the saved-instance Bundle. The buffer belongs to " +
                "DuckShellViewModel; restore is 'read the id, re-query Room'.",
            emptyList<String>(),
            offenders,
        )
    }

    private fun offendersIn(source: String): List<String> =
        stripComments(source).let { code -> BANNED.filter { it in code } }

    /** Block and line comments out; string literals are not a concern for these two tokens. */
    private fun stripComments(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//.*"), "")

    private fun sources(): List<File> =
        SCANNED.flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" } }

    private companion object {
        val SCANNED = listOf(
            File("src/main/java/app/duck/notes/presentation/markdown"),
            File("src/main/java/app/duck/notes/presentation/shell"),
        )

        /** Both spellings of the same mechanism. Order is the order [detectorFlagsPlantedOffenders] expects. */
        val BANNED = listOf("TextFieldState.Saver", "rememberTextFieldState")
    }
}
