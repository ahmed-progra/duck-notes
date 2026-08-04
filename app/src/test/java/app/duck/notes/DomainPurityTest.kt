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

    /**
     * Every sub-package of `domain/` reaches the scan — **not** a count floor.
     *
     * It was `scanned >= 20` against a real 40, so an exclusion could drop half of `domain/` and stay
     * green; `TokenSourceTest` had the same shape and an audit proved it there by planting one.
     * The expected set is read off the filesystem, so a new sub-package is covered the day it exists
     * and a failure names what disappeared.
     */
    @Test
    fun everyDomainSubPackageReachesTheScan() {
        val packages = DOMAIN_DIR.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()
        assertTrue("no sub-packages under ${DOMAIN_DIR.absolutePath}", packages.isNotEmpty())

        val seen = sources()
            .map { it.relativeTo(DOMAIN_DIR).invariantSeparatorsPath.substringBefore('/') }
            .toSet()
        assertEquals(
            "a sub-package of domain/ scanned to nothing — everything in it is silently exempt " +
                "from the Android-import ban",
            emptySet<String>(),
            packages - seen,
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

        val ANDROID_IMPORT = Regex("^import\\s+(?:android|androidx)\\.[^\\s]*", RegexOption.MULTILINE)

        fun sources(): List<File> =
            DOMAIN_DIR.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        fun offendingImports(source: String): List<String> =
            ANDROID_IMPORT.findAll(source).map { it.value.trim() }.toList()
    }
}
