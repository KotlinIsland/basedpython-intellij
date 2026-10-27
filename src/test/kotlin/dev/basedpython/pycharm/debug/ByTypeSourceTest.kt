package dev.basedpython.pycharm.debug

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.dap.DapSessionContext
import com.intellij.platform.dap.DapSessionExecutor
import com.intellij.platform.dap.DapVariable
import com.intellij.platform.dap.ValueKind
import com.intellij.platform.dap.xdebugger.DefaultDapXDebuggerPresentationFactory
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XNavigatable
import com.jetbrains.dap.protocol.VariableAttribute
import dev.basedpython.pycharm.lsp.ext.ByDeclaration
import dev.basedpython.pycharm.lsp.ext.ByTypeDeclarationParams
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * *Jump to Type Source* on a bpd session's variable goes where `by/typeDeclaration` says the class
 * its `type` names is declared.
 */
@TestFixtures
class ByTypeSourceTest {

    private val fixture by codeInsightFixture()

    private val source = "class Box:\n    class Meta:\n        pass\n"

    @Test
    fun `bpd names a builtin's type bare and everything else's by its module`() {
        assertEquals("builtins.int", ByTypeSource.typeName("int"))
        assertEquals("pkgx.model.Box.Meta", ByTypeSource.typeName("pkgx.model.Box.Meta"))
        assertNull(ByTypeSource.typeName(""))
        assertNull(ByTypeSource.typeName(null))
    }

    private fun client(file: VirtualFile, answer: (String) -> CompletableFuture<*>): AnsweringClient {
        // the light fixture's files are in memory, at a URI the default descriptor does not map back
        val descriptor = object : ProjectWideLspClientDescriptor(fixture.project, "answering") {
            override fun isSupportedFile(file: VirtualFile): Boolean = true
            override fun findFileByUri(fileUri: String): VirtualFile? = file.takeIf { fileUri == URI }
            override fun getFileUri(file: VirtualFile): String = URI
        }
        return AnsweringClient(fixture.project, descriptor = descriptor, answer = answer)
    }

    private fun meta(uri: String = URI) = ByDeclaration(
        kind = "class",
        name = "Meta",
        qualifiedName = "pkgx.model.Box.Meta",
        uri = uri,
        range = Range(Position(1, 4), Position(2, 12)),
        selectionRange = Range(Position(1, 10), Position(1, 14)),
    )

    @Test
    fun `the class by answers is where the type is, at its name`() = onEdt {
        val file = fixture.configureByText("model.by", source).virtualFile
        val client = client(file) { method ->
            assertEquals("by/typeDeclaration", method)
            answered(meta())
        }
        val position = ByTypeSource(fixture.project) { client }.of("pkgx.model.Box.Meta")!!
        assertEquals(file, position.file)
        assertEquals(source.indexOf("Meta"), position.offset)
        assertEquals(listOf(ByTypeDeclarationParams("pkgx.model.Box.Meta")), client.sentParams)
    }

    @Test
    fun `a name that reaches no class goes nowhere`() = onEdt {
        val file = fixture.configureByText("model.by", source).virtualFile
        assertNull(ByTypeSource(fixture.project) { client(file) { answered(null) } }.of("make.<locals>.Local"))
    }

    @Test
    fun `a by from before the request goes nowhere`() = onEdt {
        val file = fixture.configureByText("model.by", source).virtualFile
        val typeSource = ByTypeSource(fixture.project) { client(file) { failed(ResponseErrorCode.MethodNotFound) } }
        assertNull(typeSource.of("pkgx.model.Box"))
    }

    @Test
    fun `a file the project cannot open goes nowhere`() = onEdt {
        val file = fixture.configureByText("model.by", source).virtualFile
        assertNull(ByTypeSource(fixture.project) { client(file) { answered(meta(uri = "file:///gone.by")) } }.of("gone.Meta"))
    }

    private fun variable(type: String) = object : DapVariable {
        override val name: String = "m"
        override val type: String = type
        override val evaluateName: String? = "m"
        override val kind: ValueKind? = null
        override val attributes: List<VariableAttribute> = emptyList()
        override val value: String = "<pkgx.model.Box.Meta object>"
    }

    private val executor = object : DapSessionExecutor {
        override fun post(block: suspend DapSessionContext.() -> Unit) = error("nothing is sent to the adapter")

        override fun <T> postAsync(block: suspend DapSessionContext.() -> T): Deferred<T> = error("nothing is sent to the adapter")
    }

    @Test
    fun `a bpd session's variable jumps to its type's class, any other's offers nothing`() = onEdt {
        val file = fixture.configureByText("model.by", source).virtualFile
        val factory = DefaultDapXDebuggerPresentationFactory(null)
        val typeSource = ByTypeSource(fixture.project) { client(file) { answered(meta()) } }

        val bpd = ByDapXValue(factory, executor, variable("pkgx.model.Box.Meta"), null, typeSource)
        assertTrue(bpd.canNavigateToTypeSource())
        val told = CompletableDeferred<XSourcePosition?>()
        bpd.computeTypeSourcePosition(XNavigatable { told.complete(it) })
        assertEquals(source.indexOf("Meta"), withTimeout(10_000) { told.await() }?.offset)

        assertFalse(ByDapXValue(factory, executor, variable("Meta"), null, null).canNavigateToTypeSource())
    }

    private companion object {
        const val URI = "file:///project/pkgx/model.by"
    }
}
