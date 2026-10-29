package dev.basedpython.pycharm.debug

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import dev.basedpython.pycharm.console.ByConsoleDocument
import dev.basedpython.pycharm.lsp.ByServerStart
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.awaitByServer
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent

/**
 * Asks for the class a new exception breakpoint is for, completing it from `by`.
 *
 * What is offered is `by`'s own completion of the typed text as a module's whole source, kept to the
 * classes: the builtins by their names, and a class `by` would import by the name the import says —
 * `AppError (import errs)` is `errs.AppError`, which is how bpd finds a class, by the module it is in.
 * `by` does not say which classes are exceptions, so a class that is not one is offered too, and bpd
 * says so on the breakpoint when the session starts.
 */
internal class ByExceptionClassDialog(private val project: Project) : DialogWrapper(project) {

    private val document = ByConsoleDocument("untitled:basedpython-exception-class-${COUNTER.incrementAndGet()}.by")

    private val field = TextFieldWithAutoCompletion(project, Classes(project, document), false, "")

    init {
        title = BasedPythonBundle.message("debug.breakpoint.exception.class.title")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(BasedPythonBundle.message("debug.breakpoint.exception.class.label")) {
            cell(field).align(AlignX.FILL).focused()
        }
    }

    override fun doValidate(): ValidationInfo? =
        if (name().isEmpty()) ValidationInfo(BasedPythonBundle.message("debug.breakpoint.exception.class.empty"), field) else null

    private fun name(): String = field.text.trim()

    /** The class named, or null when the dialog was cancelled. */
    fun showAndGetName(): String? = try {
        if (showAndGet()) name() else null
    } finally {
        document.close()
    }

    private class Classes(private val project: Project, private val document: ByConsoleDocument) :
        TextFieldWithAutoCompletionListProvider<String>(emptyList()) {

        override fun getLookupString(item: String): String = item

        override fun getItems(prefix: String?, cached: Boolean, parameters: CompletionParameters?): Collection<String> {
            val typed = prefix.orEmpty()
            val by = runBlockingCancellable { awaitByServer(project, waitForNewStart = false) } as? ByServerStart.Running
                ?: return emptyList()
            document.sync(by.client, typed)
            val params = CompletionParams(TextDocumentIdentifier(document.uri), Position(0, typed.length))
            val answer = by.client.askBy<Either<List<CompletionItem>, CompletionList>>("textDocument/completion") {
                it.textDocumentService.completion(params)
            }.value ?: return emptyList()
            return (if (answer.isLeft) answer.left else answer.right.items).mapNotNull(::className).distinct()
        }
    }

    companion object {
        private val COUNTER = AtomicInteger()

        private val IMPORT = Regex("""^from\s+(\S+)\s+import\s+(\S+)\s*$""")

        /**
         * The name bpd would find [item]'s class by: the module its import edit names and the class,
         * or the label of one that needs no import. Null for an item that is not a class.
         */
        fun className(item: CompletionItem): String? {
            if (item.kind != CompletionItemKind.Class) return null
            val imported = item.additionalTextEdits.orEmpty().firstNotNullOfOrNull { IMPORT.matchEntire(it.newText.trim()) }
            if (imported != null) return "${imported.groupValues[1]}.${imported.groupValues[2]}"
            return item.label.takeIf { label -> label.all { it.isLetterOrDigit() || it == '_' || it == '.' } }
        }
    }
}
