package dev.basedpython.pycharm.lsp.refactor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.platform.lsp.util.getLsp4jRange
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * The code actions `by` offers at a caret or a selection — its refactorings, each under its own code
 * action kind — by the kind each is offered under, as the intention that shows it in Alt+Enter and
 * lists it under *Settings | Editor | Intentions | basedpython*, where it can be switched off.
 */
enum class ByCodeActionKind(val kind: String, private val familyKey: String) {
    InlineVariable("refactor.inline.variable", "intention.by.inlineVariable"),
    ExtractVariable("refactor.extract.variable", "intention.by.extractVariable"),
    IntroduceConstant("refactor.extract.constant", "intention.by.introduceConstant"),
    IntroduceParameter("refactor.extract.parameter", "intention.by.introduceParameter"),
    IntroduceField("refactor.extract.field", "intention.by.introduceField"),
    ExtractFunction("refactor.extract.function", "intention.by.extractFunction"),
    ReturnAnnotation("refactor.rewrite.returnAnnotation", "intention.by.returnAnnotation"),
    ToDataClass("refactor.rewrite.toDataClass", "intention.by.toDataClass"),
    FromDataClass("refactor.rewrite.fromDataClass", "intention.by.fromDataClass"),
    SplitList("refactor.rewrite.splitList", "intention.by.splitList"),
    JoinList("refactor.rewrite.joinList", "intention.by.joinList"),
    InvertIf("refactor.rewrite.invertIf", "intention.by.invertIf"),
    DeMorgan("refactor.rewrite.deMorgan", "intention.by.deMorgan"),
    FlipQuotes("refactor.rewrite.flipQuotes", "intention.by.flipQuotes"),
    LoopToComprehension("refactor.rewrite.loopToComprehension", "intention.by.loopToComprehension"),
    ;

    val familyName: String get() = BasedPythonBundle.message(familyKey)

    companion object {
        fun of(kind: String?): ByCodeActionKind? = entries.firstOrNull { it.kind == kind }
    }
}

/** What `by` offered at one caret or selection: the first action of each kind it names, and the rest in its order. */
internal class ByOfferedActions(
    val named: Map<ByCodeActionKind, LspIntentionAction>,
    val others: List<LspIntentionAction>,
) {
    companion object {
        val NONE = ByOfferedActions(emptyMap(), emptyList())

        /**
         * [actions], `by`'s reply, as the intentions that show them: quick fixes left out — they are
         * the platform's, attached to the diagnostic they fix — and so are actions `by` offers
         * disabled. [intention] makes each one's intention.
         */
        fun of(actions: List<CodeAction>, intention: (CodeAction) -> LspIntentionAction): ByOfferedActions {
            val named = LinkedHashMap<ByCodeActionKind, LspIntentionAction>()
            val others = mutableListOf<LspIntentionAction>()
            for (action in actions) {
                if (action.disabled != null || action.kind?.startsWith(CodeActionKind.QuickFix) == true) continue
                val kind = ByCodeActionKind.of(action.kind)
                if (kind != null && kind !in named) named[kind] = intention(action) else others += intention(action)
            }
            return ByOfferedActions(named, others)
        }
    }
}

/**
 * `by`'s code actions at an editor's caret or selection, asked once for every intention that shows
 * one of them.
 *
 * The platform's own LSP intentions do the same for any server, but as fifteen numbered slots under
 * one *LSP* entry of the settings, whatever each action is; `by`'s are asked here instead, and the
 * platform is told not to ask (`ByLspServerDescriptor`), so each is asked for once.
 *
 * One answer is kept, for the text and the caret it was asked about: Alt+Enter and the light bulb
 * ask every intention in turn, about the same caret. A project service, so it goes with the project
 * or with the plugin, and the actions it holds with it.
 */
@Service(Service.Level.PROJECT)
internal class ByCodeActionIntentions(private val project: Project) {

    private data class Asked(val file: VirtualFile, val stamp: Long, val start: Int, val end: Int)

    @Volatile
    private var last: Pair<Asked, ByOfferedActions>? = null

    /**
     * `by`'s actions at [editor]'s caret or selection. Asked off the EDT only, in the read action the
     * platform asks intentions in; on the EDT, only what was asked already.
     */
    fun at(editor: Editor): ByOfferedActions {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return ByOfferedActions.NONE
        val caret = editor.caretModel.primaryCaret
        val asked = Asked(file, document.modificationStamp, caret.selectionStart, caret.selectionEnd)
        last?.let { (key, offered) -> if (key == asked) return offered }
        if (ApplicationManager.getApplication().isDispatchThread) return ByOfferedActions.NONE

        val server = byServerFor(project, file) ?: return ByOfferedActions.NONE
        ByServerDocuments.ensureOpen(server, project, file)
        val params = CodeActionParams(
            server.getDocumentIdentifier(file),
            getLsp4jRange(document, asked.start, asked.end - asked.start),
            CodeActionContext(emptyList()).apply { triggerKind = CodeActionTriggerKind.Automatic },
        )
        val reply = server.askBy("textDocument/codeAction") { it.textDocumentService.codeAction(params) }.value.orEmpty()
        val offered = ByOfferedActions.of(reply.map(::codeAction)) { LspIntentionAction(server, it) }
        last = asked to offered
        return offered
    }

    companion object {
        fun getInstance(project: Project): ByCodeActionIntentions = project.service()

        /** A command is an action that only runs it, as the platform reads one. */
        private fun codeAction(reply: Either<Command, CodeAction>): CodeAction =
            if (reply.isRight) {
                reply.right
            } else {
                CodeAction(reply.left.title).apply {
                    command = reply.left
                    edit = WorkspaceEdit()
                }
            }
    }
}

/**
 * One of `by`'s code actions as an intention, in Alt+Enter and under *Settings | Editor | Intentions
 * | basedpython*. The action itself — whether it applies, its title, its edit and its preview — is
 * `by`'s, made by the platform's [LspIntentionAction].
 *
 * The platform keeps one instance of an intention for every editor, and asks [isAvailable] before
 * [getText], [invoke] and [generatePreview], as its own LSP intentions rely on: the action found
 * last is the one they describe.
 */
abstract class ByCodeActionIntention : IntentionAction, DumbAware {

    @Volatile
    private var current: LspIntentionAction? = null

    internal abstract fun pick(offered: ByOfferedActions): LspIntentionAction?

    override fun getText(): String = current?.text ?: familyName

    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor, psiFile: PsiFile): Boolean {
        if (!psiFile.language.isKindOf(BasedPythonLanguage)) return false
        val action = pick(ByCodeActionIntentions.getInstance(project).at(editor))
        current = action
        return action?.isAvailable(project, editor, psiFile) == true
    }

    override fun invoke(project: Project, editor: Editor, psiFile: PsiFile) {
        current?.invoke(project, editor, psiFile)
    }

    override fun generatePreview(project: Project, editor: Editor, psiFile: PsiFile): IntentionPreviewInfo =
        current?.generatePreview(project, editor, psiFile) ?: IntentionPreviewInfo.EMPTY
}

/** The intention for the code actions `by` offers under [kind]. */
abstract class ByNamedCodeActionIntention(private val kind: ByCodeActionKind) : ByCodeActionIntention() {
    override fun getFamilyName(): String = kind.familyName
    override fun pick(offered: ByOfferedActions): LspIntentionAction? = offered.named[kind]
}

/**
 * An action `by` offers under a kind no intention above names — one a newer `by` has, say — or a
 * second of a kind: the [index]th of them, so that every action `by` offers is in Alt+Enter.
 */
abstract class ByOtherCodeActionIntention(private val index: Int) : ByCodeActionIntention() {
    override fun getFamilyName(): String = BasedPythonBundle.message("intention.by.other")
    override fun pick(offered: ByOfferedActions): LspIntentionAction? = offered.others.getOrNull(index)
}

class ByInlineVariableIntention : ByNamedCodeActionIntention(ByCodeActionKind.InlineVariable)
class ByExtractVariableIntention : ByNamedCodeActionIntention(ByCodeActionKind.ExtractVariable)
class ByIntroduceConstantIntention : ByNamedCodeActionIntention(ByCodeActionKind.IntroduceConstant)
class ByIntroduceParameterIntention : ByNamedCodeActionIntention(ByCodeActionKind.IntroduceParameter)
class ByIntroduceFieldIntention : ByNamedCodeActionIntention(ByCodeActionKind.IntroduceField)
class ByExtractFunctionIntention : ByNamedCodeActionIntention(ByCodeActionKind.ExtractFunction)
class ByReturnAnnotationIntention : ByNamedCodeActionIntention(ByCodeActionKind.ReturnAnnotation)
class ByToDataClassIntention : ByNamedCodeActionIntention(ByCodeActionKind.ToDataClass)
class ByFromDataClassIntention : ByNamedCodeActionIntention(ByCodeActionKind.FromDataClass)
class BySplitListIntention : ByNamedCodeActionIntention(ByCodeActionKind.SplitList)
class ByJoinListIntention : ByNamedCodeActionIntention(ByCodeActionKind.JoinList)
class ByInvertIfIntention : ByNamedCodeActionIntention(ByCodeActionKind.InvertIf)
class ByDeMorganIntention : ByNamedCodeActionIntention(ByCodeActionKind.DeMorgan)
class ByFlipQuotesIntention : ByNamedCodeActionIntention(ByCodeActionKind.FlipQuotes)
class ByLoopToComprehensionIntention : ByNamedCodeActionIntention(ByCodeActionKind.LoopToComprehension)
class ByOtherCodeAction0Intention : ByOtherCodeActionIntention(0)
class ByOtherCodeAction1Intention : ByOtherCodeActionIntention(1)
class ByOtherCodeAction2Intention : ByOtherCodeActionIntention(2)
