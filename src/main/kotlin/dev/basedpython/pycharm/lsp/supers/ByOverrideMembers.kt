package dev.basedpython.pycharm.lsp.supers

import com.intellij.codeInsight.generation.ClassMember
import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.codeInsight.generation.MemberChooserObjectBase
import com.intellij.codeInsight.hint.HintManager
import com.intellij.icons.AllIcons
import com.intellij.ide.util.MemberChooser
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.applyTextEdits
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiFile
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByOverridableMember
import dev.basedpython.pycharm.lsp.ext.ByOverridableMembers
import dev.basedpython.pycharm.lsp.ext.ByOverridableMembersParams
import dev.basedpython.pycharm.lsp.ext.ByOverrideMembersParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.jetbrains.annotations.VisibleForTesting
import java.util.concurrent.CompletableFuture
import javax.swing.JTree
import dev.basedpython.pycharm.lsp.ext.ByOverrideMembers as ByOverrideEdit

/** Which of the two actions is asking: they differ only in what they offer and how. */
internal enum class ByMembersAction(private val titleKey: String, private val chooserKey: String, private val noneKey: String) {
    /** Override Methods (Ctrl+O): every method the class inherits, none picked to begin with. */
    Override("members.override.title", "members.override.chooser", "members.override.none"),

    /** Implement Methods (Ctrl+I): the abstract ones, all picked to begin with, as the class has to implement each. */
    Implement("members.implement.title", "members.implement.chooser", "members.implement.none"),
    ;

    val title: String get() = BasedPythonBundle.message(titleKey)

    fun chooserTitle(className: String): String = BasedPythonBundle.message(chooserKey, className)

    fun none(className: String): String = BasedPythonBundle.message(noneKey, className)

    /** Of what `by` listed for the class, what this action offers. */
    fun offered(members: List<ByOverridableMember>): List<ByOverridableMember> =
        when (this) {
            Override -> members
            Implement -> members.filter { it.abstract }
        }
}

/** The text a question was asked about, so that the second question is asked about the same text. */
internal data class ByMembersPlace(val document: TextDocumentIdentifier, val textHash: String, val position: Position)

/** What `by` said about the class at the caret, or why there is nothing to offer. */
internal sealed interface ByMembersAnswer {

    data class Members(val place: ByMembersPlace, val className: String, val members: List<ByOverridableMember>) : ByMembersAnswer

    data class Nothing(val message: String) : ByMembersAnswer
}

/** The declarations `by` wrote, or why it wrote none. */
internal sealed interface ByWrittenMembers {

    data class Edit(val edit: ByOverrideEdit) : ByWrittenMembers

    data class Nothing(val message: String) : ByWrittenMembers
}

/**
 * Override Methods (Ctrl+O) and Implement Methods (Ctrl+I) in a `.by` file, asked of `by`.
 *
 * Everything that decides what to offer and what to write is `by`'s. `by/overridableMembers` lists
 * the methods the class at the caret inherits — in MRO order of the superclass each comes from, the
 * abstract ones marked — and `by/overrideMembers` writes the declarations of the ones picked, the
 * way basedpython writes an override, with the imports they need and where the caret goes next.
 * Nothing here writes a line of the declarations: the platform's member chooser is shown over the
 * first answer, and the second is applied as `by` gave it.
 *
 * Both requests name the text the caret was read in ([ByTextHash]), so `by` answers about that text
 * and waits for a `didOpen` the platform has not sent yet.
 */
internal object ByOverrideMembers {

    /** What `by` lists for the class at [offset] in [file]. Suspends on the server; reads under its own read action. */
    suspend fun find(server: LspClient, file: VirtualFile, document: Document, offset: Int): ByMembersAnswer {
        val place = readAction {
            ByServerDocuments.ensureOpen(server, server.project, file)
            ByMembersPlace(
                server.getDocumentIdentifier(file),
                ByTextHash.of(document.immutableCharSequence),
                getLsp4jPosition(document, offset),
            )
        }
        return find(server, place)
    }

    /** What `by` lists for the class at [place]. */
    suspend fun find(server: LspClient, place: ByMembersPlace): ByMembersAnswer {
        val params = ByOverridableMembersParams(place.document, place.position, place.textHash)
        val (answer, unknown) = ask<ByOverridableMembers>(server, "by/overridableMembers") {
            (it as ByServerExtensions).overridableMembers(params)
        }
        return when (answer) {
            is ByAnswer.Answer -> ByMembersAnswer.Members(place, answer.value.className.orEmpty(), answer.value.members)
            ByAnswer.None -> nothing("members.noClass")
            ByAnswer.Failed -> failed(unknown, "by/overridableMembers")
        }
    }

    /** The declarations of [names], members `by` listed for the class at [place]. */
    suspend fun write(server: LspClient, place: ByMembersPlace, names: List<String>): ByWrittenMembers {
        val params = ByOverrideMembersParams(place.document, place.position, names, place.textHash)
        val (answer, unknown) = ask<ByOverrideEdit>(server, "by/overrideMembers") {
            (it as ByServerExtensions).overrideMembers(params)
        }
        return when (answer) {
            is ByAnswer.Answer -> ByWrittenMembers.Edit(answer.value)
            ByAnswer.None -> ByWrittenMembers.Nothing(BasedPythonBundle.message("members.notWritten"))
            ByAnswer.Failed -> ByWrittenMembers.Nothing(failed(unknown, "by/overrideMembers").message)
        }
    }

    /**
     * Writes [written] into [editor]'s document as one undoable command, and selects the first
     * declaration's body, which is what the user replaces first. EDT only.
     */
    fun apply(project: Project, editor: Editor, title: String, written: ByOverrideEdit) {
        val document = editor.document
        WriteCommandAction.writeCommandAction(project)
            .withName(title)
            .run<RuntimeException> { applyTextEdits(document, written.edits) }
        val selection = written.selection?.let { getRangeInDocument(document, it) } ?: return
        editor.caretModel.moveToOffset(selection.startOffset)
        editor.selectionModel.setSelection(selection.startOffset, selection.endOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    /** The members [action] offers out of [members], as the chooser shows them: under their superclass. */
    fun choices(action: ByMembersAction, members: List<ByOverridableMember>): List<ByMemberChoice> {
        val containers = HashMap<Pair<String, String?>, ByMemberContainer>()
        return action.offered(members).mapNotNull { member ->
            val name = member.name ?: return@mapNotNull null
            val containerName = member.containerName.orEmpty()
            val container = containers.getOrPut(containerName to member.containerModule) {
                ByMemberContainer(containerName, member.containerModule)
            }
            ByMemberChoice(name, member, container)
        }
    }

    /** Sends one request, telling a `by` that does not know it apart from one that failed. */
    private suspend fun <R : Any> ask(
        server: LspClient,
        what: String,
        request: (org.eclipse.lsp4j.services.LanguageServer) -> CompletableFuture<R?>,
    ): Pair<ByAnswer<R>, Boolean> {
        var unknown = false
        val answer = awaitingAgain<R>(what, LspClient.DEFAULT_REQUEST_TIMEOUT_MS.toLong()) { sent ->
            try {
                server.sendRequest { request(it).also(sent) }
            } catch (e: Exception) {
                if (isMethodNotFound(e)) unknown = true
                throw e
            }
        }
        return answer to unknown
    }

    private fun failed(unknownRequest: Boolean, what: String): ByMembersAnswer.Nothing =
        if (unknownRequest) nothing("members.unsupported", what) else nothing("members.noAnswer", what)

    private fun nothing(key: String, vararg params: Any): ByMembersAnswer.Nothing =
        ByMembersAnswer.Nothing(BasedPythonBundle.message(key, *params))
}

/** A superclass, as the chooser groups members under it: its name, and its module in grey. */
internal class ByMemberContainer(val name: String, val module: String?) : MemberChooserObjectBase(name, AllIcons.Nodes.Class) {

    override fun renderTreeNode(component: SimpleColoredComponent, tree: JTree) {
        component.icon = AllIcons.Nodes.Class
        component.append(name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        module?.let { component.append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
    }

    override fun equals(other: Any?): Boolean = other is ByMemberContainer && other.name == name && other.module == module

    override fun hashCode(): Int = 31 * name.hashCode() + module.hashCode()
}

/** A method in the chooser: the declaration `by` would write for it, from its name on. */
internal class ByMemberChoice(val name: String, val member: ByOverridableMember, private val container: ByMemberContainer) :
    MemberChooserObjectBase(member.signature ?: name, if (member.abstract) AllIcons.Nodes.AbstractMethod else AllIcons.Nodes.Method),
    ClassMember {

    override fun getParentNodeDelegate(): MemberChooserObject = container

    override fun equals(other: Any?): Boolean = other is ByMemberChoice && other.name == name && other.container == container

    override fun hashCode(): Int = 31 * name.hashCode() + container.hashCode()
}

/**
 * The handler behind Override Methods (Ctrl+O) or Implement Methods (Ctrl+I) for basedpython; see
 * [ByOverrideMembers].
 *
 * Without one the actions are not even enabled in a `.by` file. Whether the caret is in a class is
 * `by`'s to say. [isValidFor], which the platform calls on the EDT to update the action, cannot ask,
 * so it reads `by`'s outline of the text on screen ([ByOutlines]), which is kept current in the
 * background: the actions are enabled with the caret in a class — its header or its body, a method
 * of it included — and disabled elsewhere, as Python's are. Before `by` has outlined the text on
 * screen they stay enabled, and outside a class `by` answers that there is none, which is said in a
 * hint and nothing is written.
 *
 * `by` is asked under a background progress rather than a modal one, for the reason Go to Super is
 * (see [ByGotoSuperHandler]): the platform's `didOpen`, which `by` may be waiting for, goes out from
 * the EDT in the non-modal state. The answer is shown only if the caret and the text are still where
 * they were; the declarations are written only if the text is still the one they were asked about.
 */
internal abstract class ByMembersHandler(private val action: ByMembersAction) : LanguageCodeInsightActionHandler {

    override fun isValidFor(editor: Editor, psiFile: PsiFile): Boolean {
        if (!psiFile.language.isKindOf(BasedPythonLanguage)) return false
        val outline = ByOutlines.getInstance(psiFile.project).current(editor.document) ?: return true
        return outline.isInClass(editor.caretModel.offset, editor.document.immutableCharSequence)
    }

    /** Nothing is written until `by` has answered twice; the platform must not hold a write lock meanwhile. */
    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, psiFile: PsiFile) {
        val file = psiFile.originalFile.virtualFile ?: return
        val server = byServerFor(project, file) ?: return hint(editor, BasedPythonBundle.message("members.noServer"))
        ask(project, editor, BasedPythonBundle.message("members.progress"), { document, offset ->
            ByOverrideMembers.find(server, file, document, offset)
        }) { answer -> show(project, editor, server, answer) }
    }

    private fun show(project: Project, editor: Editor, server: LspClient, answer: ByMembersAnswer) {
        val members = when (answer) {
            is ByMembersAnswer.Nothing -> return hint(editor, answer.message)
            is ByMembersAnswer.Members -> answer
        }
        val choices = ByOverrideMembers.choices(action, members.members)
        if (choices.isEmpty()) return hint(editor, action.none(members.className))

        val chooser = MemberChooser(choices.toTypedArray(), false, true, project)
        chooser.title = action.chooserTitle(members.className)
        chooser.setCopyJavadocVisible(false)
        if (action == ByMembersAction.Implement) chooser.selectElements(choices.toTypedArray<ClassMember>())
        if (!chooser.showAndGet()) return
        val names = chooser.selectedElements.orEmpty().map { it.name }
        if (names.isEmpty()) return

        ask(project, editor, BasedPythonBundle.message("members.write.progress"), { _, _ ->
            ByOverrideMembers.write(server, members.place, names)
        }) { written ->
            when (written) {
                is ByWrittenMembers.Nothing -> hint(editor, written.message)
                is ByWrittenMembers.Edit -> ByOverrideMembers.apply(project, editor, action.title, written.edit)
            }
        }
    }

    /**
     * Asks [find] about [editor]'s caret off the EDT under a background progress titled [title], and
     * hands the answer to [show] on the EDT — unless by then the editor is gone, its caret has moved
     * or its text has changed.
     */
    @VisibleForTesting
    internal fun <T> ask(
        project: Project,
        editor: Editor,
        title: String,
        find: suspend (Document, Int) -> T,
        show: (T) -> Unit,
    ): Job {
        val document = editor.document
        val offset = editor.caretModel.offset
        val stamp = document.modificationStamp
        return project.service<ByMembersScope>().scope.launch {
            val answer = withBackgroundProgress(project, title) { find(document, offset) }
            withContext(Dispatchers.EDT) {
                val stillThere = !editor.isDisposed &&
                    document.modificationStamp == stamp &&
                    editor.caretModel.offset == offset
                if (stillThere) show(answer)
            }
        }
    }

    private fun hint(editor: Editor, text: String) {
        LOG.debug("${action.title}: $text")
        HintManager.getInstance().showInformationHint(editor, text)
    }

    private companion object {
        val LOG = logger<ByMembersHandler>()
    }
}

/** Override Methods (Ctrl+O) in a `.by` file. */
internal class ByOverrideMethodsHandler : ByMembersHandler(ByMembersAction.Override)

/** Implement Methods (Ctrl+I) in a `.by` file. */
internal class ByImplementMethodsHandler : ByMembersHandler(ByMembersAction.Implement)

/** Where the two actions' asking runs: cancelled with the project, and with the plugin when it unloads. */
@Service(Service.Level.PROJECT)
internal class ByMembersScope(val scope: CoroutineScope)
