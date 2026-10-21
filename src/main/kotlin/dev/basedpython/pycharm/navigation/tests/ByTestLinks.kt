package dev.basedpython.pycharm.navigation.tests

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.BySubjectTests
import dev.basedpython.pycharm.lsp.ext.ByTestLink
import dev.basedpython.pycharm.lsp.ext.ByTestLinksParams
import dev.basedpython.pycharm.lsp.isMethodNotFound
import dev.basedpython.pycharm.navigation.ByDeclarationElement
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** What asking `by` which tests exercise which code came to. */
internal sealed interface ByTestLinksAnswer<out T> {

    /** What `by` said. */
    data class Answered<T>(val value: T) : ByTestLinksAnswer<T>

    /** `by` answered `null`: for `by/testSubjects` no test is collected from the document. */
    data object Nothing : ByTestLinksAnswer<kotlin.Nothing>

    /** A `by` from before the request. */
    data object Unsupported : ByTestLinksAnswer<kotlin.Nothing>

    /** Not answered: refused, timed out, or nobody running. */
    data object Failed : ByTestLinksAnswer<kotlin.Nothing>
}

/**
 * `by/testSubjects` and `by/subjectTests`, the two directions of Go to Test, asked about a place in a
 * `.by` document.
 *
 * Everything that says which test exercises which code is `by`'s: what a test calls, which
 * declarations are tests, where a new test goes and what it imports. This picks the position to ask
 * at and turns the items `by` names into elements the platform can show and go to.
 */
internal object ByTestLinks {

    /**
     * How long to wait. `by/subjectTests` looks for calls across the project, and Go to Test waits
     * under a progress the user can cancel, so this bounds a server that hangs rather than one that
     * is slow on a large project.
     */
    private const val TIMEOUT_MS = 30_000

    /** The code the test, class of tests or test module at [element] exercises. Background, in a read action. */
    fun testSubjects(client: LspClient, file: VirtualFile, document: Document, element: PsiElement): ByTestLinksAnswer<List<ByTestLink>> =
        ask(client, file, position(document, element), "by/testSubjects") { server, params -> server.testSubjects(params) }

    /** The tests that exercise the code at [element], and where a new one goes. Background, in a read action. */
    fun subjectTests(client: LspClient, file: VirtualFile, document: Document, element: PsiElement): ByTestLinksAnswer<BySubjectTests> =
        subjectTests(client, file, position(document, element))

    /** [subjectTests] at [position]. */
    fun subjectTests(client: LspClient, file: VirtualFile, position: Position): ByTestLinksAnswer<BySubjectTests> =
        ask(client, file, position, "by/subjectTests") { server, params -> server.subjectTests(params) }

    private fun <R : Any> ask(
        client: LspClient,
        file: VirtualFile,
        position: Position,
        method: String,
        request: (ByServerExtensions, ByTestLinksParams) -> CompletableFuture<R?>,
    ): ByTestLinksAnswer<R> {
        ByServerDocuments.ensureOpen(client, client.project, file)
        val params = ByTestLinksParams(TextDocumentIdentifier(client.descriptor.getFileUri(file)), position)
        val unsupported = AtomicBoolean()
        val answer = client.askBy(method, TIMEOUT_MS) { server ->
            request(server as ByServerExtensions, params).whenComplete { _, error ->
                if (error != null && isMethodNotFound(error)) unsupported.set(true)
            }
        }
        return when (answer) {
            is ByAnswer.Answer -> ByTestLinksAnswer.Answered(answer.value)
            ByAnswer.None -> ByTestLinksAnswer.Nothing
            ByAnswer.Failed -> if (unsupported.get()) ByTestLinksAnswer.Unsupported else ByTestLinksAnswer.Failed
        }
    }

    /**
     * Where to ask about [element], the token at the caret: its start, except in whitespace, whose
     * start is the end of whatever comes before it — a blank line after a function would ask about
     * the function — so there the start of what comes after.
     */
    fun position(document: Document, element: PsiElement): Position {
        val range = element.textRange
        val offset = if (element is PsiWhiteSpace) range.endOffset else range.startOffset
        return getLsp4jPosition(document, offset.coerceIn(0, document.textLength))
    }

    /** [link] as an element the platform can show and go to, or null when its file is not there. */
    fun target(client: LspClient, link: ByTestLink): ByDeclarationElement? {
        val uri = link.uri ?: return null
        val name = link.name ?: return null
        val position = link.selectionRange?.start ?: return null
        return ByDeclarationElement.at(client, uri, position, name, link.module, link.kind)
    }
}
