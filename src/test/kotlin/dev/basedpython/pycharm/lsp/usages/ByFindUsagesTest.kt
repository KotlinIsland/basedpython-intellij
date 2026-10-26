package dev.basedpython.pycharm.lsp.usages

import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.Usage
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.lsp.ext.ByDeclaration
import dev.basedpython.pycharm.lsp.ext.ByDeclarationParams
import com.intellij.platform.lsp.api.customization.LspSymbolKindCustomizer
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
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
import org.junit.jupiter.api.Assertions.assertTrue
import com.intellij.usages.impl.rules.UsageType
import dev.basedpython.pycharm.lsp.ext.ByReference
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

    /** A client answering [answer] for each method, its files mapped back from [URI]. */
    private fun client(file: VirtualFile, answer: (String) -> java.util.concurrent.CompletableFuture<*>): AnsweringClient {
        // the light fixture's files are in memory, at a URI the default descriptor does not map back
        val descriptor = object : ProjectWideLspClientDescriptor(fixture.project, "answering") {
            override fun isSupportedFile(file: VirtualFile): Boolean = true
            override fun findFileByUri(fileUri: String): VirtualFile? = file.takeIf { fileUri == URI }
            override fun getFileUri(file: VirtualFile): String = URI
        }
        return AnsweringClient(fixture.project, descriptor = descriptor, answer = answer)
    }

    private val usesSource = "class Circle:\n    pass\n\nfrom shapes import Circle\nc: Circle = Circle()\n"

    @Test
    fun `each usage is of the type by says it is, and the declaration by marks is none`() = onEdt {
        val psi = fixture.configureByText("shapes.by", usesSource) as BasedPythonFile
        val file = psi.virtualFile
        val target = BySearchTarget.at(psi, file, usesSource.indexOf("Circle"))!!
        val client = client(file) {
            answered(
                listOf(
                    ByReference(URI, Range(Position(0, 6), Position(0, 12)), "declaration"),
                    ByReference(URI, Range(Position(3, 19), Position(3, 25)), "import"),
                    ByReference(URI, Range(Position(4, 3), Position(4, 9)), "typeHint"),
                    ByReference(URI, Range(Position(4, 12), Position(4, 18)), "read"),
                ),
            )
        }
        val found = mutableListOf<Usage>()
        withContext(Dispatchers.Default) { ByUsageSearcher().usages(client, target) { found += it; true } }

        val params = client.sentParams.single() as ReferenceParams
        assertTrue(params.context.isIncludeDeclaration, "without it, by leaves out every place that binds the name, the import among them")
        assertEquals(Position(0, 6), params.position)
        assertEquals(
            listOf(
                "Circle@" + usesSource.indexOf("Circle\nc") to ByUsageTypes.IMPORT,
                "Circle@" + usesSource.indexOf("Circle =") to ByUsageTypes.TYPE_HINT,
                "Circle@" + usesSource.indexOf("Circle()") to UsageType.READ,
            ),
            found.map { it as PsiUsage }.map { "${it.range.substring(usesSource)}@${it.range.startOffset}" to it.usageType },
        )
        assertEquals(UsageType.READ, (found.last() as PsiUsage).createPointer().dereference()!!.usageType, "a pointer keeps the type")
    }

    @Test
    fun `a by without by-references is asked for the references without the declaration, untyped`() = onEdt {
        val psi = fixture.configureByText("shapes.by", source) as BasedPythonFile
        val file = psi.virtualFile
        val target = BySearchTarget.at(psi, file, source.indexOf("Circle"))!!
        val client = client(file) { method ->
            if (method == "by/references") failed(ResponseErrorCode.MethodNotFound)
            else answered(listOf(Location(URI, Range(Position(3, 4), Position(3, 10)))))
        }
        val found = mutableListOf<Usage>()
        withContext(Dispatchers.Default) { ByUsageSearcher().usages(client, target) { found += it; true } }

        val params = client.sentParams.last() as ReferenceParams
        assertFalse(params.context.isIncludeDeclaration, "the declaration is not one of its own usages")
        assertEquals(listOf("Circle@" + source.indexOf("Circle()") to null), found.map { it as PsiUsage }.map { "${it.range.substring(source)}@${it.range.startOffset}" to it.usageType })
    }

    @Test
    fun `the target is drawn as what by says it declares, in what declares it`() = onEdt {
        val psi = fixture.configureByText("shapes.by", source) as BasedPythonFile
        val target = BySearchTarget.at(psi, psi.virtualFile, source.indexOf("Circle()"))!!
        val client = AnsweringClient(fixture.project) {
            answered(ByDeclaration(kind = "class", name = "Circle", qualifiedName = "shapes.Circle"))
        }

        val declared = withContext(Dispatchers.Default) { target.declared(client, psi.fileDocument) }

        val presentation = declared.presentation()
        assertEquals("Circle", presentation.presentableText)
        assertEquals("shapes", presentation.locationText)
        assertEquals("shapes", presentation.containerText)
        assertEquals(LspSymbolKindCustomizer().getIcon(SymbolKind.Class), presentation.icon)
        assertEquals(target, declared, "the same search whatever by said")
        assertEquals(Position(3, 4), (client.sentParams.single() as ByDeclarationParams).position)
    }

    @Test
    fun `a by without by-declaration leaves the target named by its file`() = onEdt {
        val psi = fixture.configureByText("shapes.by", source) as BasedPythonFile
        val target = BySearchTarget.at(psi, psi.virtualFile, source.indexOf("Circle()"))!!
        val client = AnsweringClient(fixture.project) { failed(ResponseErrorCode.MethodNotFound) }

        val declared = withContext(Dispatchers.Default) { target.declared(client, psi.fileDocument) }

        assertEquals("shapes.by", declared.presentation().locationText)
        assertNull(declared.presentation().containerText)
    }
}
