package dev.basedpython.pycharm.navigation.tests

import com.intellij.codeInsight.hint.HintManager
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testIntegration.TestCreator
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByNewTest
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.net.URI
import java.nio.file.Path
import org.jetbrains.annotations.VisibleForTesting
import java.util.Properties

/**
 * Create New Test, in Go to Test's popup in a `.by` file: a test of the code at the caret, in the
 * test module `by` says it belongs in, importing it as `by` says to.
 *
 * What is decided about the code is `by`'s — `by/subjectTests`' `newTest`: the module's dotted name,
 * the name to import, a name for the test, which test module, and whether it exists. What the test
 * looks like is the user's: the `basedpython Test` file template for a new test module, `basedpython
 * Test Function` for a test added to one that exists, both editable under Settings | Editor | File and
 * Code Templates. To a test module that exists the test is added at the end, with the import `by`
 * gives for it, none when the module imports the code already.
 *
 * The asking runs under a modal progress, as the platform's own Create Test does its work; the
 * request names no text, so it is not held for a `didOpen` the platform sends only outside a modal
 * (see [dev.basedpython.pycharm.lsp.ext.ByTestLinksParams]). The writing is one command, undone as
 * one.
 */
class ByTestCreator : TestCreator, DumbAware {

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file !is BasedPythonFile) return false
        val virtualFile = file.originalFile.virtualFile ?: return false
        return byServerFor(project, virtualFile) != null
    }

    override fun createTest(project: Project, editor: Editor, file: PsiFile) {
        val virtualFile = file.originalFile.virtualFile ?: return
        val client = byServerFor(project, virtualFile)
            ?: return error(editor, BasedPythonBundle.message("create.test.noServer"))
        val document = editor.document
        val offset = editor.caretModel.offset

        val answer = runWithModalProgressBlocking(project, BasedPythonBundle.message("create.test.progress")) {
            readAction {
                ByServerDocuments.ensureOpen(client, project, virtualFile)
                ByTestLinks.subjectTests(client, virtualFile, getLsp4jPosition(document, offset))
            }
        }
        val newTest = when (answer) {
            is ByTestLinksAnswer.Answered -> answer.value.newTest
                ?: return error(editor, BasedPythonBundle.message("create.test.nowhere"))
            ByTestLinksAnswer.Nothing -> return error(editor, BasedPythonBundle.message("create.test.nowhere"))
            ByTestLinksAnswer.Unsupported ->
                return error(editor, BasedPythonBundle.message("goto.test.unsupported", "by/subjectTests"))
            ByTestLinksAnswer.Failed -> return error(editor, BasedPythonBundle.message("goto.test.noAnswer", "by/subjectTests"))
        }
        when (val written = write(project, newTest, client.descriptor::findFileByUri)) {
            is Written.Test -> OpenFileDescriptor(project, written.file, written.offset).navigate(true)
            is Written.Nothing -> error(editor, written.message)
        }
    }

    /** What writing the test came to. */
    internal sealed interface Written {
        /** The test module the test is in, and the start of the test's name in it. */
        data class Test(val file: VirtualFile, val offset: Int) : Written

        /** Nothing written, and why. */
        data class Nothing(val message: String) : Written
    }

    /**
     * Creates the test module [newTest] names with the test in it, or adds the test to it when it
     * exists, finding that module by [find]. One command.
     *
     * A module `by` says is not there, and that is there by the time this writes, is left alone:
     * whatever is in it is not what `by` answered about, and the import it would need is not known.
     */
    @VisibleForTesting
    internal fun write(project: Project, newTest: ByNewTest, find: (String) -> VirtualFile?): Written {
        val nowhere = Written.Nothing(BasedPythonBundle.message("create.test.nowhere"))
        val uri = newTest.uri ?: return nowhere
        val module = newTest.module ?: return nowhere
        val testName = newTest.testName ?: return nowhere
        val properties = Properties(FileTemplateManager.getInstance(project).defaultProperties).apply {
            setProperty("MODULE", module)
            setProperty("IMPORT", newTest.import.orEmpty())
            setProperty("TEST_NAME", testName)
        }
        val templates = FileTemplateManager.getInstance(project)
        return WriteCommandAction.writeCommandAction(project)
            .withName(BasedPythonBundle.message("create.test.command", testName))
            .compute<Written, Exception> {
                if (!newTest.exists) {
                    val path = runCatching { Path.of(URI(uri)) }.getOrNull() ?: return@compute nowhere
                    val name = path.fileName.toString()
                    val directory = VfsUtil.createDirectoryIfMissing(path.parent.toString()) ?: return@compute nowhere
                    if (directory.findChild(name) != null) {
                        return@compute Written.Nothing(BasedPythonBundle.message("create.test.appeared", name))
                    }
                    val created = directory.createChildData(this, name)
                    val document = FileDocumentManager.getInstance().getDocument(created) ?: return@compute nowhere
                    val text = templates.getInternalTemplate(NEW_MODULE_TEMPLATE).getText(properties)
                    document.setText(StringUtil.convertLineSeparators(text))
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                    Written.Test(created, nameOffset(document, testName, 0))
                } else {
                    val existing = find(uri) ?: return@compute nowhere
                    val document = FileDocumentManager.getInstance().getDocument(existing) ?: return@compute nowhere
                    val text = templates.getInternalTemplate(FUNCTION_TEMPLATE).getText(properties)
                    // the import first: its ranges are `by`'s, in the text as it is, and the test goes
                    // in below everything
                    importInto(document, newTest)
                    val start = append(document, StringUtil.convertLineSeparators(text))
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                    Written.Test(existing, nameOffset(document, testName, start))
                }
            }
    }

    /** Puts [text] at the end of [document], two blank lines below what is there. Returns where it starts. */
    private fun append(document: Document, text: String): Int {
        val existing = document.charsSequence
        val trimmed = existing.length - existing.reversed().takeWhile { it == '\n' || it == ' ' }.length
        val separator = if (trimmed == 0) "" else "\n\n\n"
        document.replaceString(trimmed, existing.length, separator + text)
        return trimmed + separator.length
    }

    /** Applies the edits `by` gave to import the code, from the bottom up so each range still holds. */
    private fun importInto(document: Document, newTest: ByNewTest) {
        val edits = newTest.importEdits.mapNotNull { edit ->
            val start = getOffsetInDocument(document, edit.range.start) ?: return@mapNotNull null
            val end = getOffsetInDocument(document, edit.range.end) ?: return@mapNotNull null
            Triple(start, end, edit.newText.orEmpty())
        }
        for ((start, end, text) in edits.sortedByDescending { it.first }) {
            document.replaceString(start, end, StringUtil.convertLineSeparators(text))
        }
    }

    /** Where [testName] is defined in [document], at or after [from]; [from] when the template does not define it. */
    private fun nameOffset(document: Document, testName: String, from: Int): Int {
        val found = document.charsSequence.indexOf("def $testName", from)
        return if (found < 0) from else found + "def ".length
    }

    private fun error(editor: Editor, message: String) {
        HintManager.getInstance().showErrorHint(editor, message)
    }

    private companion object {
        const val NEW_MODULE_TEMPLATE = "basedpython Test"
        const val FUNCTION_TEMPLATE = "basedpython Test Function"
    }
}
