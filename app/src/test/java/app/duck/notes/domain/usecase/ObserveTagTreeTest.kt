package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeTagRepository
import app.duck.notes.domain.model.Tag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The `/` separator is the only thing that makes a tag nested — the tree is derived, never stored. */
class ObserveTagTreeTest {

    private fun tree(vararg tags: Tag) = ObserveTagTree(FakeTagRepository(tags.toList()))

    @Test
    fun flatTagsBecomeRoots() = runTest {
        val roots = tree(Tag("1", "work"), Tag("2", "home"))().first()

        assertEquals(listOf("work", "home"), roots.map { it.segment })
        assertEquals(listOf("work", "home"), roots.map { it.path })
    }

    @Test
    fun slashesBecomeDepth() = runTest {
        val roots = tree(
            Tag("1", "projects"),
            Tag("2", "projects/duck"),
            Tag("3", "projects/duck/ui"),
        )().first()

        val projects = roots.single()
        assertEquals("projects", projects.segment)
        val duck = projects.children.single()
        assertEquals("projects/duck", duck.path)
        val ui = duck.children.single()
        assertEquals("projects/duck/ui", ui.path)
        assertEquals("ui", ui.segment)
        assertEquals("3", ui.tag?.id)
    }

    @Test
    fun intermediateLevelNobodyTaggedHasNoTag() = runTest {
        val roots = tree(Tag("1", "a/b"))().first()

        val a = roots.single()
        assertNull("there is no `a` row to pin or rename", a.tag)
        assertEquals("a", a.path)
        assertNotNull(a.children.single().tag)
    }

    @Test
    fun siblingsShareAParent() = runTest {
        val roots = tree(Tag("1", "a/b"), Tag("2", "a/c"))().first()

        assertEquals(listOf("b", "c"), roots.single().children.map { it.segment })
    }

    @Test
    fun emptyRepositoryYieldsNoRoots() = runTest {
        assertEquals(emptyList<String>(), tree()().first().map { it.segment })
    }
}
