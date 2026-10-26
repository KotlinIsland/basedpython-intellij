package dev.basedpython.pycharm.lsp.usages

import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.Usage
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Find Usages and Show Usages on a `.by` name: what is searched for, named by the name, and `by`'s
 * references to it without the declaration — which the platform's own LSP search asked for and
 * listed as a usage of itself.
 */
@TestFixtures
class ByFindUsagesTest {

    private val fixture by codeInsightFixture()

    private val source = "class Circle:\n    pass\n\nc = Circle()\n"

    private companion object {
        const val URI = "file:///shapes.by"
    }

    @Test
    fun `the target is the name at the caret, and named by it`() = onEdt {
        val psi = fixture.configureByText("shapes.by", source) as BasedPythonFile
        val file = psi.virtualFile
        val target = BySearchTarget.at(psi, file, source.indexOf("Circle()") + 3)!!
        assertEquals("Circle", target.presentation().presentableText)
        assertEquals(source.indexOf("Circle()"), target.offset)
        assertEquals(target, BySearchTarget.at(psi, file, source.indexOf("Circle()") + "Circle".length), "just after the name is on it")
        assertNull(BySearchTarget.at(psi, file, source.indexOf("class")), "a keyword is not a name")
    }

    @Test
    fun `by is asked for the references without the declaration, and each is a usage`() = onEdt {
        val psi = fixture.configureByText("shapes.by", source) as BasedPythonFile
        val file = psi.virtualFile
        val target = BySearchTarget.at(psi, file, source.indexOf("Circle"))!!
        // the light fixture's files are in memory, at a URI the default descriptor does not map back
        val descriptor = object : ProjectWideLspClientDescriptor(fixture.project, "answering") {
            override fun isSupportedFile(file: VirtualFile): Boolean = true
            override fun findFileByUri(fileUri: String): VirtualFile? = file.takeIf { fileUri == URI }
            override fun getFileUri(file: VirtualFile): String = URI
        }
        val client = AnsweringClient(fixture.project, descriptor = descriptor) {
            answered(listOf(Location(URI, Range(Position(3, 4), Position(3, 10)))))
        }
        val found = mutableListOf<Usage>()
        withContext(Dispatchers.Default) { ByUsageSearcher().usages(client, target) { found += it; true } }

        val params = client.sentParams.single() as ReferenceParams
        assertFalse(params.context.isIncludeDeclaration, "the declaration is not one of its own usages")
        assertEquals(Position(0, 6), params.position)
        assertEquals(listOf("Circle@" + source.indexOf("Circle()")), found.map { it as PsiUsage }.map { "${it.range.substring(source)}@${it.range.startOffset}" })
    }
}
