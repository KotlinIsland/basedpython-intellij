package dev.basedpython.pycharm.lsp.imports

import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.ReferenceImporter
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.ext.ByNamedCodeActionParams
import dev.basedpython.pycharm.lsp.ext.ByNamedDocumentDiagnosticParams
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentDiagnosticReport
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * Which import is unambiguous is `by`'s call: it marks the quick fix. What is left here is reading
 * the mark, applying only what it marks and only to the text it was worked out for, and never the
 * import for a name the user is still typing.
 */
@TestFixtures
class ByAutoImportsTest {

    private val fixture by codeInsightFixture()

    private val uri = "file:///project/main.by"

    private val importEdit = TextEdit(Range(Position(0, 0), Position(0, 0)), "from collections import defaultdict\n")

    /** A quick fix as `by` writes it, with [data] in its `data` as JSON, as lsp4j reads it. */
    private fun action(
        title: String,
        data: String? = null,
        edit: WorkspaceEdit? = WorkspaceEdit(mapOf(uri to listOf(importEdit))),
    ): Either<Command, CodeAction> = Either.forRight(
        CodeAction(title).apply {
            kind = "quickfix"
            this.edit = edit
            this.data = data?.let(JsonParser::parseString)
        },
    )

    private val marked = """{"unambiguousImport": true}"""

    private fun unambiguous(vararg actions: Either<Command, CodeAction>) =
        ByAutoImports.unambiguousImport(actions.toList()) { it == uri }

    @Test
    fun `the action by marks is the import`() {
        val import = unambiguous(
            action("import collections.defaultdict", marked),
            action("Ignore 'unresolved-reference' for this line"),
        )
        assertEquals(ByAutoImport("import collections.defaultdict", listOf(importEdit)), import)
    }

    @Test
    fun `no mark, as from a by before the mark, is nothing to add`() {
        assertNull(unambiguous(action("import pathlib.Path"), action("import zipfile.Path")))
    }

    @Test
    fun `a mark that is not true is no mark`() {
        assertNull(unambiguous(action("import a.x", """{"unambiguousImport": false}""")))
        assertNull(unambiguous(action("import a.x", """{"unambiguousImport": "yes"}""")))
        assertNull(unambiguous(action("import a.x", """{"refactor": "extract"}""")))
    }

    @Test
    fun `two marked actions are two meanings, and neither is added`() {
        assertNull(unambiguous(action("import a.x", marked), action("import b.x", marked)))
    }

    @Test
    fun `an action that does more than edit this document is not added unasked`() {
        val elsewhere = WorkspaceEdit(mapOf("file:///project/other.by" to listOf(importEdit)))
        assertNull(unambiguous(action("import a.x", marked, elsewhere)))
        val twoFiles = WorkspaceEdit(mapOf(uri to listOf(importEdit), "file:///project/other.by" to listOf(importEdit)))
        assertNull(unambiguous(action("import a.x", marked, twoFiles)))
        val asDocumentChanges = WorkspaceEdit(listOf(Either.forLeft(TextDocumentEdit(VersionedTextDocumentIdentifier(uri, 1), listOf(Either.forLeft(importEdit))))))
        assertNull(unambiguous(action("import a.x", marked, asDocumentChanges)))
        assertNull(unambiguous(action("import a.x", marked, edit = null)))
        val withCommand = action("import a.x", marked).apply { right.command = Command("Install", "by.addDependency") }
        assertNull(unambiguous(withCommand))
    }

    @Test
    fun `the unresolved reference holding the offset, by its rule`() = onEdt {
        val document = fixture.configureByText("u.by", "x = defaultdict(list)\ny = Path()\n").viewProvider.document
        val unresolved = { line: Int, start: Int, end: Int ->
            Diagnostic(Range(Position(line, start), Position(line, end)), "unresolved").apply {
                code = Either.forLeft(ByAutoImports.UNRESOLVED_REFERENCE)
            }
        }
        val other = Diagnostic(Range(Position(1, 4), Position(1, 8)), "other").apply { code = Either.forLeft("invalid-argument-type") }
        val diagnostics = listOf(other, unresolved(0, 4, 15), unresolved(1, 4, 8))

        assertEquals(diagnostics[1], ByAutoImports.unresolvedAt(diagnostics, document, 4)?.first)
        assertEquals(diagnostics[2], ByAutoImports.unresolvedAt(diagnostics, document, 26)?.first)
        assertNull(ByAutoImports.unresolvedAt(diagnostics, document, 0))
    }

    @Test
    fun `the import is added as one step to undo, and only to the text it was worked out for`() = onEdt {
        val file = fixture.configureByText("apply.by", "x = defaultdict(list)\n")
        val document = file.viewProvider.document
        val import = ByAutoImport("import collections.defaultdict", listOf(importEdit))

        assertFalse(ByAutoImports.apply(fixture.project, document, document.modificationStamp - 1, import))
        assertEquals("x = defaultdict(list)\n", document.text)

        assertTrue(ByAutoImports.apply(fixture.project, document, document.modificationStamp, import))
        assertEquals("from collections import defaultdict\nx = defaultdict(list)\n", document.text)

        val editor = (FileEditorManager.getInstance(fixture.project).getSelectedEditor(file.virtualFile) as TextEditor)
        UndoManager.getInstance(fixture.project).undo(editor)
        assertEquals("x = defaultdict(list)\n", document.text)
    }

    /**
     * From the problem the platform asks about to the import: `by`'s diagnostics, then the quick fixes
     * of the unresolved reference at the offset, both about the text the document has, and the marked
     * one. And nothing for a name the caret is still in.
     */
    @Test
    fun `the importer asks by about the text it reads, and leaves a name the caret is in`() = onEdt {
        val psiFile = fixture.configureByText("ask.by", "x = defaultdict(list)\n")
        val document = psiFile.viewProvider.document
        val name = Range(Position(0, 4), Position(0, 15))
        val client = AnsweringClient(fixture.project) { method ->
            when (method) {
                "textDocument/diagnostic" -> answered(
                    DocumentDiagnosticReport(
                        RelatedFullDocumentDiagnosticReport(
                            listOf(Diagnostic(name, "Name `defaultdict` used when not defined").apply {
                                code = Either.forLeft(ByAutoImports.UNRESOLVED_REFERENCE)
                                source = "ty"
                            }),
                        ),
                    ),
                )
                "textDocument/codeAction" -> answered(
                    listOf(
                        action("import collections.defaultdict", marked, WorkspaceEdit(mapOf(uriOf(psiFile.virtualFile) to listOf(importEdit)))),
                    ),
                )
                else -> CompletableFuture.failedFuture<Any>(IllegalStateException(method))
            }
        }
        val import = withContext(Dispatchers.Default) {
            readAction { ByAutoImports.find(client, psiFile.virtualFile, document, 4, caret = 0) }
        }
        assertNotNull(import)
        assertEquals("import collections.defaultdict", import!!.title)
        val diagnosticParams = client.sentParams.filterIsInstance<ByNamedDocumentDiagnosticParams>().single()
        val codeActionParams = client.sentParams.filterIsInstance<ByNamedCodeActionParams>().single()
        val textHash = ByTextHash.of(document.immutableCharSequence)
        assertEquals(textHash, diagnosticParams.textHash)
        assertEquals(textHash, codeActionParams.textHash)
        assertEquals(listOf(ByAutoImports.UNRESOLVED_REFERENCE), codeActionParams.context.diagnostics.map { it.code.left })

        val typing = withContext(Dispatchers.Default) {
            readAction { ByAutoImports.find(client, psiFile.virtualFile, document, 4, caret = 15) }
        }
        assertNull(typing)
    }

    @Test
    fun `the importer is registered`() {
        assertTrue(ReferenceImporter.EP_NAME.extensionList.any { it is ByReferenceImporter })
    }

    private fun uriOf(file: com.intellij.openapi.vfs.VirtualFile): String =
        AnsweringClient(fixture.project) { answered(null) }.getDocumentIdentifier(file).uri
}
