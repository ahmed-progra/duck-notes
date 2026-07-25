package app.duck.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 3's headline constraint asserted by code: `domain/` must stay pure JVM, so it is testable
 * with `gradlew test` and no emulator, and so a use case can never quietly depend on a platform type.
 *
 * A file scan can fail silently — a wrong path finds nothing and reports green — so this class also
 * proves it is doing work: that the directory resolved, that it scanned the whole package, and that
 * [offendingImports] actually catches an offender. Only then does a clean result mean anything.
 */
class DomainPurityTest {

    @Test
    fun domainDirectoryResolves() {
        assertTrue(
            "no domain sources at ${DOMAIN_DIR.absolutePath} — unit tests run with the module dir " +
                "as the working directory, so this path is relative to `app/`",
            DOMAIN_DIR.isDirectory,
        )
    }

    @Test
    fun scansEveryDomainFile() {
        val scanned = sources().size
        assertTrue(
            "scanned $scanned Kotlin files under ${DOMAIN_DIR.absolutePath}, expected at least " +
                "$MIN_SOURCES — a scan this small means the path is wrong, not that the package shrank",
            scanned >= MIN_SOURCES,
        )
    }

    @Test
    fun detectorFlagsAnAndroidImport() {
        val dirty =
            """
            package app.duck.notes.domain.model

            import androidx.room.Entity
            import android.net.Uri
            import kotlinx.coroutines.flow.Flow
            """.trimIndent()

        assertEquals(
            listOf("import androidx.room.Entity", "import android.net.Uri"),
            offendingImports(dirty),
        )
    }

    @Test
    fun detectorAcceptsPureJvmImports() {
        val clean =
            """
            package app.duck.notes.domain.usecase

            import kotlinx.coroutines.flow.Flow
            import javax.inject.Inject
            import java.util.UUID
            """.trimIndent()

        assertEquals(emptyList<String>(), offendingImports(clean))
    }

    @Test
    fun domainHasNoAndroidImports() {
        val offenders = sources().flatMap { file ->
            offendingImports(file.readText()).map { "${file.name}: $it" }
        }

        assertEquals("domain/ must not depend on the Android platform", emptyList<String>(), offenders)
    }

    private companion object {
        val DOMAIN_DIR = File("src/main/java/app/duck/notes/domain")

        /** Models, repositories, use cases and the Clock port. Well under the real count. */
        const val MIN_SOURCES = 20

        val ANDROID_IMPORT = Regex("^import\\s+(?:android|androidx)\\.[^\\s]*", RegexOption.MULTILINE)

        fun sources(): List<File> =
            DOMAIN_DIR.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        fun offendingImports(source: String): List<String> =
            ANDROID_IMPORT.findAll(source).map { it.value.trim() }.toList()
    }
}
