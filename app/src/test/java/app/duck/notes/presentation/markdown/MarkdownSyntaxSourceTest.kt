package app.duck.notes.presentation.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **No layer above `domain/markdown` may spell Duck's markdown out loud.**
 *
 * Phase 8c gave the editor buttons that *write* syntax, which is the first time anything outside the
 * scanner had a reason to know what bold looks like. A `"**"` in a composable would be the third
 * implementation of one syntax — the exact defect that once split `[[WikiLink]]` across
 * `ResolveWikiLinks`, `MarkdownScanner` and `DeriveTitle` until Phase 9a gave it one home in
 * `WikiLinkScanner`. Cheaper to forbid it than to reconcile it later.
 *
 * The legal route is `MarkdownScanner.markerFor` / `prefixFor`, whose output `MarkdownFormatTest`
 * round-trips back through the real parser.
 *
 * ## Scope, and why it stops where it does
 *
 * The headline says *"no layer above `domain/markdown`"* and the two scans stop in different places,
 * so both boundaries are argued rather than left to be inferred from a list.
 *
 * **The syntax-literal ban covers `presentation/`, `core/` and `data/`.** `data/` was originally
 * absent with no reason given — exempt by omission — and it is the layer holding `TagRepositoryImpl`,
 * which *rewrites note bodies*. A `"**"` there would be as much a second implementation as one in a
 * composable. Audit finding 14.
 *
 * **The regex ban stops at `presentation/` and `core/`.** `NoteDao` splits an FTS search term on
 * `Regex("\\s+")`, which is a query tokeniser and not the start of a second markdown parser — the
 * thing this ban is for. Widening it would mean an allow-list naming one of the files most worth
 * watching, so the narrower scope is stated instead.
 *
 * `domain/` is **not** scanned by the syntax-literal or the `Regex(` ban, and that is deliberate:
 * `domain/markdown` is where the scanners legitimately spell syntax, and `DeriveTitle` in
 * `domain/usecase` still hand-rolls the *rest* of its title stripping (block, task, image and link
 * markers) as regexes — its own separate item, not this guard's. The wiki-link rule that used to be
 * duplicated there is the one exception now closed: Phase 9a extracted `WikiLinkScanner`, and
 * [theWikiLinkRuleHasOneHome] holds that specific line so a fourth copy cannot creep back.
 */
class MarkdownSyntaxSourceTest {

    @Test
    fun noSyntaxLiteralAppearsAboveTheScanner() {
        val offenders = filesIn(SYNTAX_SCANNED).flatMap { file ->
            syntaxLiteralsIn(file.readText()).map { "${file.name}: $it" }
        }
        assertEquals(
            "markdown syntax written outside domain/markdown — use MarkdownScanner.markerFor / " +
                "prefixFor, which MarkdownFormatTest round-trips through the parser",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun noRegexIsBuiltAboveTheScanner() {
        val offenders = filesIn(REGEX_SCANNED)
            .filter { Regex("\\bRegex\\s*\\(").containsMatchIn(stripStringsAndComments(it.readText())) }
            .map { it.name }
        assertEquals(
            "a regex in presentation/ or core/ is how a second parser starts",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The `[[WikiLink]]` bracket rule lives in exactly one file: `WikiLinkScanner`.
     *
     * `ResolveWikiLinks` and `DeriveTitle` each carried a byte-identical `Regex("\\[\\[([^\\[\\]]+)]]")`
     * until Phase 9a reconciled them — three implementations of one syntax, disagreeing about code
     * masking. This catches any of them coming back, or a fourth site appearing anywhere in `main`,
     * which `TagAgreementTest` cannot: a re-added regex that happens to agree on the corpus still
     * passes the behavioural check while diverging on the input the corpus does not cover.
     *
     * The needle is the opening of that regex's capture group, `[[([^`, matched against source with
     * its backslashes stripped so the escaping in the literal does not have to be mirrored here. It is
     * specific to the negated-bracket capture: `DeriveTitle`'s surviving task/image/link regexes do
     * not contain it, and `MarkdownScanner`/`WikiLinkScanner`'s `startsWith("[[")` is not a regex.
     */
    @Test
    fun theWikiLinkRuleHasOneHome() {
        val needle = "[[([^"
        val offenders = filesIn(listOf(MAIN_SOURCE))
            .filter { it.name != "WikiLinkScanner.kt" }
            .filter { needle in it.readText().replace("\\", "") }
            .map { it.name }
        assertEquals(
            "the [[WikiLink]] bracket rule reappeared outside WikiLinkScanner — route it through the " +
                "scanner (WikiLinkScanner.titlesIn / matchAt / stripInline), not a second regex",
            emptyList<String>(),
            offenders,
        )
        // Positive control for the detector: it fires on the retired regex's own source form.
        assertTrue(
            "the one-home detector no longer recognises the wiki-link regex it exists to catch",
            needle in """Regex("\[\[([^\[\]]+)]]")""".replace("\\", ""),
        )
    }

    /**
     * Positive control for both absence assertions above.
     *
     * Each of those reports green over an empty list if the walk resolves nothing or the detector is
     * dead — the failure mode a whole-tree scan is most prone to, and the one an audit of this suite
     * already caught once. So: the tree really has files, one of them really is the toolbar, and the
     * detector really fires on a planted string.
     *
     * **Reach is checked per sub-package, not by a count.** It was `files.size >= 10` against a real
     * 31; `TokenSourceTest` had the same shape and an audit proved there that half a tree can drop
     * out of a scan while a count floor stays green. The expected set comes off the filesystem, so it
     * cannot go stale and a failure names the package that vanished.
     */
    @Test
    fun theScanIsPointedAtRealFilesAndTheDetectorWorks() {
        val files = filesIn(SYNTAX_SCANNED)
        assertTrue("the source walk found nothing — check SYNTAX_SCANNED", files.isNotEmpty())
        SYNTAX_SCANNED.forEach { root ->
            val packages = root.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()
            val seen = filesIn(listOf(root))
                .map { it.relativeTo(root).invariantSeparatorsPath.substringBefore('/') }
                .toSet()
            assertEquals(
                "a sub-package of ${root.name} scanned to nothing — everything in it is silently " +
                    "exempt from the syntax-literal ban",
                emptySet<String>(),
                packages - seen,
            )
        }
        assertTrue(
            "FormatToolbar.kt is the file this guard exists for and it is not being scanned",
            files.any { it.name == "FormatToolbar.kt" },
        )
        assertTrue(
            "TagRepositoryImpl.kt rewrites note bodies and is why data/ is in the syntax scan",
            files.any { it.name == "TagRepositoryImpl.kt" },
        )
        // The exact shape the plant takes: a task marker spelled out in a composable.
        assertEquals(
            listOf("\"- [ ] \""),
            syntaxLiteralsIn("""private const val TASK = "- [ ] """" + "\n"),
        )
        assertTrue(
            Regex("\\bRegex\\s*\\(").containsMatchIn(
                stripStringsAndComments("""val T = Regex("- \\[[ xX]]")"""),
            ),
        )
    }

    private companion object {
        val PRESENTATION = File("src/main/java/app/duck/notes/presentation")
        val CORE = File("src/main/java/app/duck/notes/core")

        /** Whole `main` tree — the one-home wiki-link check scans everywhere, not just above the scanner. */
        val MAIN_SOURCE = File("src/main/java/app/duck/notes")

        /** Includes `data/`, which holds `TagRepositoryImpl`. See the class KDoc for the argument. */
        val SYNTAX_SCANNED = listOf(PRESENTATION, CORE, File("src/main/java/app/duck/notes/data"))

        /** Excludes `data/`: `NoteDao`'s FTS term split is a tokeniser, not a parser. Same KDoc. */
        val REGEX_SCANNED = listOf(PRESENTATION, CORE)

        /**
         * String literals and comments, in one alternation, string-first.
         *
         * Copied from `TokenSourceTest` and load-bearing for the same reason: a bare `//.*` truncates
         * at the first `//` inside a URL and silently erases any real violation later on the line —
         * a false negative that reports green.
         */
        val STRING_OR_COMMENT = Regex(
            "\"\"\"[\\s\\S]*?\"\"\"" +
                "|\"(?:\\\\.|[^\"\\\\\\n])*\"" +
                "|'(?:\\\\.|[^'\\\\\\n])*'" +
                "|/\\*[\\s\\S]*?\\*/" +
                "|//[^\\n]*",
        )

        /**
         * Duck's syntax, as a string a call site could write.
         *
         * Block prefixes carry their trailing space, which is what keeps them from matching ordinary
         * prose — `"- "` is a bullet marker, while a bare `"-"` is a hyphen and matches everything.
         *
         * `"!["` joined in 8d-b: the attachment reference `![alt](attachment:id)` is written in exactly
         * one place, `MarkdownScanner.imageRef`, and `MarkdownFormatTest` round-trips it through the
         * parser. A `"!["` in a composable or repository is the same second implementation `"**"` is.
         */
        val SYNTAX =
            listOf("**", "__", "~~", "==", "`", "[[", "]]", "# ", "> ", "- ", "[ ]", "[x]", "![")

        /**
         * Only **short** literals are offenders, and that is the whole exemption mechanism.
         *
         * `SAMPLE_NOTE` in `MarkdownEditorScreen` is a 500-character raw string containing every
         * token kind, because it is the preview and gallery *fixture* — sample data, not an
         * implementation. Nobody implements markdown in a 500-character literal, so length separates
         * the two cleanly and needs no per-file allow-list. An allow-list would name the one file
         * this guard exists to watch, and would grow quietly.
         *
         * **Known limit:** `"*" + "*"` defeats it, as does any literal split across concatenation.
         * A real offender is written the obvious way; this stops that, and the `Regex(` ban next door
         * stops the other obvious route. It is not a proof, and it is not claimed as one.
         */
        const val MAX_LITERAL = 24

        fun filesIn(roots: List<File>): List<File> = roots.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
        }

        /** Comments and char literals dropped; only real string literals are searched. */
        fun syntaxLiteralsIn(source: String): List<String> =
            STRING_OR_COMMENT.findAll(source)
                .map { it.value }
                .filter { it.startsWith("\"") && it.length <= MAX_LITERAL }
                .filter { literal -> SYNTAX.any { it in literal } }
                .toList()

        fun stripStringsAndComments(source: String): String =
            source.replace(STRING_OR_COMMENT) { if (it.value.first() in "\"'") "\"\"" else "" }
    }
}
