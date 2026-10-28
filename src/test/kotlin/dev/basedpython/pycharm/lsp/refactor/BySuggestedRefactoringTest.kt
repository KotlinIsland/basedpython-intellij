package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.suggested.SuggestedChangeSignatureData
import com.intellij.refactoring.suggested.SuggestedRefactoringState
import com.intellij.refactoring.suggested.SuggestedRefactoringSupport
import com.intellij.refactoring.suggested.SuggestedRenameData
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ext.ByAnnotationEdit
import dev.basedpython.pycharm.lsp.ext.ByChangedParameter
import dev.basedpython.pycharm.lsp.ext.BySignature
import dev.basedpython.pycharm.lsp.ext.BySignatureParameter
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Suggested refactoring in a `.by` file: which tokens are declarations, and which are in a `def`'s
 * header, is `by`'s outline; a rename is `by`'s rename and a signature change `by/changeSignature`.
 * What is left here is reading the outline and the header's tokens right, telling the edits apart,
 * and asking for the right change.
 */
@TestFixtures
class BySuggestedRefactoringTest {

    private val fixture by codeInsightFixture()

    private val source = "def area(width, height):\n    return width * height\n\narea(1, 2)\n"

    private val support: SuggestedRefactoringSupport
        get() = checkNotNull(SuggestedRefactoringSupport.forLanguage(BasedPythonLanguage))

    private fun configure(outline: Boolean = true) {
        fixture.configureByText("a.by", source)
        if (outline) {
            OutlineSpec.remember(fixture.project, fixture.editor.document) {
                def("def area(width, height):", parameters = listOf("width", "height")) {
                    simple("return width * height")
                }
                call("area(1, 2)")
            }
        }
    }

    /** The token at the [occurrence]th [text] in the file. */
    private fun token(text: String, occurrence: Int = 0): PsiElement {
        var offset = -1
        repeat(occurrence + 1) { offset = source.indexOf(text, offset + 1) }
        return checkNotNull(fixture.file.findElementAt(offset))
    }

    @Test
    fun `the support is registered for basedpython`() = onEdt {
        assertInstanceOf(BySuggestedRefactoringSupport::class.java, support)
    }

    @Test
    fun `a definition's name and its parameters are declarations`() = onEdt {
        configure()
        assertTrue(support.isAnchor(token("area")))
        assertTrue(support.isAnchor(token("width")))
        assertTrue(support.isAnchor(token("height")))
    }

    /** Editing a use changes what it refers to, which is not a rename of anything. */
    @Test
    fun `a use of a name is not a declaration`() = onEdt {
        configure()
        assertFalse(support.isAnchor(token("width", occurrence = 1)))
        assertFalse(support.isAnchor(token("area", occurrence = 1)))
        assertFalse(support.isAnchor(fixture.file))
    }

    /** Before `by` has outlined the text on screen nothing is a declaration, rather than a guess. */
    @Test
    fun `without an outline nothing is a declaration`() = onEdt {
        configure(outline = false)
        assertFalse(support.isAnchor(token("area")))
    }

    @Test
    fun `every token of a def's header is in its signature, from the name to the end of the return annotation`() = onEdt {
        configure()
        assertTrue(support.isAnchor(token("(")))
        assertTrue(support.isAnchor(token(",")))
        assertEquals(TextRange(4, 23), support.signatureRange(token(",")))
        assertEquals(TextRange(4, 8), support.nameRange(token(",")))
        assertFalse(support.isAnchor(token("return")))
    }

    @Test
    fun `a def's signature is its name and its parameters as written`() = onEdt {
        configure()
        val signature = checkNotNull(support.stateChanges.signature(token("area"), null))
        assertEquals("area", signature.name)
        assertEquals(listOf("width", "height"), signature.parameters.map { it.name })
        assertEquals(listOf(ByParameterShape("", 0, false), ByParameterShape("", 0, false)), signature.parameters.map { it.additionalData })
    }

    @Test
    fun `a header is read from its tokens`() {
        val text = "def scale[T](a: list[T], /, b=(1, 2), *args: int, c: dict[str, int] = {}, **kw) -> tuple[T, int]:\n    pass\n"
        val header = checkNotNull(ByDefHeader.read(text, TextRange(4, 9)))
        assertEquals(
            listOf(
                Triple("a", ": list[T]", ByParameterShape("", 0, false)),
                Triple("b", "=(1, 2)", ByParameterShape("", 1, true)),
                Triple("args", ": int", ByParameterShape("*", 1, false)),
                Triple("c", ": dict[str, int] = {}", ByParameterShape("", 1, true)),
                Triple("kw", "", ByParameterShape("**", 1, false)),
            ),
            header.parameters.map { Triple(it.name.substring(text), it.rest, ByParameterShape(it.stars, it.section, it.hasDefault)) },
        )
        assertEquals("tuple[T, int]", header.returns)
        assertEquals(text.indexOf(":\n"), header.range.endOffset)
        assertEquals(listOf(",", ",", ",", ",", ")"), header.parameters.map { it.after.substring(text) })
        assertEquals(
            listOf(ByParameterShape("", 0, false), ByParameterShape("", 1, false)),
            checkNotNull(ByDefHeader.read("def f(a, *, b):", TextRange(4, 5))).parameters.map { ByParameterShape(it.stars, it.section, it.hasDefault) },
        )
        assertEquals(null, ByDefHeader.read("def f(a, b", TextRange(4, 5)), "a header still being typed")
        assertEquals(null, ByDefHeader.read("def f(a) -> :", TextRange(4, 5)))
    }

    /** Calls follow a parameter added; they pass `by` the new list, each parameter with the one it was. */
    @Test
    fun `a parameter added is a change of signature`() = onEdt {
        configure()
        val state = checkNotNull(support.stateChanges.createInitialState(token("area")))
        val changed = edited(state, "(width" to "(depth, width", "height)" to "height=2)")
        val data = support.availability.detectAvailableRefactoring(changed)
        assertInstanceOf(SuggestedChangeSignatureData::class.java, data)
        data as SuggestedChangeSignatureData
        assertEquals(listOf("depth"), BySignatureUI.valuesAsked(data).map { it.name }, "a new parameter with no default needs a value")
    }

    @Test
    fun `one parameter renamed and nothing else is a rename of it`() = onEdt {
        configure()
        val state = checkNotNull(support.stateChanges.createInitialState(token("area")))
        val changed = edited(state, "height)" to "tall)")
        val data = support.availability.detectAvailableRefactoring(changed) as SuggestedRenameData
        assertEquals("height", data.oldName)
        assertEquals("tall", data.newName)
        assertEquals("tall", data.declaration.text)
    }

    /** No call follows an annotation or a default's value, so there is nothing to offer. */
    @Test
    fun `an annotation or a default added alone offers nothing`() = onEdt {
        configure()
        val state = checkNotNull(support.stateChanges.createInitialState(token("area")))
        val changed = edited(state, "width," to "width: float,", "height)" to "height=1)", "):" to ") -> float:")
        assertEquals(null, support.availability.detectAvailableRefactoring(changed))
    }

    @Test
    fun `a default removed is a change of signature`() = onEdt {
        fixture.configureByText("b.by", "def area(width, height=1):\n    return width * height\n")
        val state = checkNotNull(support.stateChanges.createInitialState(checkNotNull(fixture.file.findElementAt(4))))
        val changed = edited(state, "height=1)" to "height)")
        assertInstanceOf(SuggestedChangeSignatureData::class.java, support.availability.detectAvailableRefactoring(changed))
    }

    /**
     * The request names each new parameter with the parameter it was — followed through a rename by
     * the platform, not matched by name — sends what `by` read of each, an annotation only where it
     * changed, and a new parameter's value from the popup.
     */
    @Test
    fun `by is asked to take the old list to the new, each parameter with the one it was`() = onEdt {
        configure()
        val state = checkNotNull(support.stateChanges.createInitialState(token("area")))
        val changed = edited(state, "(width" to "(depth, width", "width," to "w: float,")
        val data = support.availability.detectAvailableRefactoring(changed) as SuggestedChangeSignatureData
        val old = BySignature("area", parameters = listOf(BySignatureParameter("width", "positionalOrKeyword"), BySignatureParameter("height", "positionalOrKeyword")))
        val new = BySignature(
            "area",
            parameters = listOf(
                BySignatureParameter("depth", "positionalOrKeyword"),
                BySignatureParameter("w", "positionalOrKeyword", annotation = "float"),
                BySignatureParameter("height", "positionalOrKeyword"),
            ),
        )
        val site = ByRefactoringSite(
            AnsweringClient(fixture.project) { error("nothing is asked") },
            fixture.project,
            fixture.file.virtualFile,
            fixture.editor.document,
            TextDocumentIdentifier("file:///a.by"),
            Position(0, 4),
        )
        val params = ByChangeSignatureExecution.changeSignatureParams(site, data, old, new, mapOf("depth" to "3"))
        assertEquals(
            listOf(
                ByChangedParameter("depth", null, "positionalOrKeyword", value = "3"),
                ByChangedParameter("w", "width", "positionalOrKeyword", annotation = ByAnnotationEdit.Set("float")),
                ByChangedParameter("height", "height", "positionalOrKeyword"),
            ),
            params.parameters,
        )
        assertEquals(null, params.name)
        assertEquals(null, params.returns)
    }

    /**
     * The platform puts the old name back and renames the declaration it is handed; that declaration
     * follows the name through edits, and putting the name back rewrites the name and nothing else.
     */
    @Test
    fun `the rename is offered on a declaration that puts the old name back`() = onEdt {
        configure()
        val anchor = token("area")
        val state = checkNotNull(support.stateChanges.createInitialState(anchor))
        val renamed = withNewName(state, "surface")
        val data = support.availability.detectAvailableRefactoring(renamed) as SuggestedRenameData
        assertEquals("area", data.oldName)
        assertEquals("surface", data.newName)

        WriteCommandAction.runWriteCommandAction(fixture.project) { data.declaration.setName("area") }
        assertEquals(source, fixture.editor.document.text)
        assertEquals(TextRange(4, 8), data.declaration.textRange)
    }

    /** The rename's usages are `by`'s edits, the declaration among them, and renaming makes them. */
    @Test
    fun `the rename makes by's edits`() = onEdt {
        configure()
        val declaration = ByRenamedName(fixture.file, fixture.editor.document, TextRange(4, 8))
        val processor = RenamePsiElementProcessor.forElement(declaration)
        assertInstanceOf(ByRenamedNameProcessor::class.java, processor)

        val edit = WorkspaceEdit(
            mapOf(
                "file:///a.by" to listOf(
                    TextEdit(Range(Position(0, 4), Position(0, 8)), "surface"),
                    TextEdit(Range(Position(3, 0), Position(3, 4)), "surface"),
                ),
            ),
        )
        val usages = ByImportEdit.of(fixture.project, edit, declaration) { fixture.file.virtualFile }
        WriteCommandAction.runWriteCommandAction(fixture.project) {
            processor.renameElement(declaration, "surface", usages.toTypedArray(), null)
        }
        assertEquals("def surface(width, height):\n    return width * height\n\nsurface(1, 2)\n", fixture.editor.document.text)
    }

    /**
     * [state] as it is once each of [edits] — the first occurrence of one text replaced by another —
     * has been typed, one after the other, as the platform follows them.
     */
    private fun edited(state: SuggestedRefactoringState, vararg edits: Pair<String, String>): SuggestedRefactoringState {
        val document = fixture.editor.document
        var current = state
        for ((before, after) in edits) {
            val start = document.text.indexOf(before)
            WriteCommandAction.runWriteCommandAction(fixture.project) { document.replaceString(start, start + before.length, after) }
            PsiDocumentManager.getInstance(fixture.project).commitDocument(document)
            current = support.stateChanges.updateState(current, checkNotNull(fixture.file.findElementAt(4)))
        }
        return current
    }

    /** [state] as it is once the declaration's name reads [name]. */
    private fun withNewName(state: SuggestedRefactoringState, name: String): SuggestedRefactoringState {
        val document = fixture.editor.document
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.replaceString(4, 8, name) }
        PsiDocumentManager.getInstance(fixture.project).commitDocument(document)
        val anchor = checkNotNull(fixture.file.findElementAt(4))
        return support.stateChanges.updateState(state, anchor)
    }
}
