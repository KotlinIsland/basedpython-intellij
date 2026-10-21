package dev.basedpython.pycharm.lsp.vision

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.codeVisionEntryMouseEventKey
import com.intellij.codeInsight.codeVision.settings.PlatformCodeVisionIds
import com.intellij.codeInsight.codeVision.ui.model.TextCodeVisionEntry
import com.intellij.codeInsight.daemon.impl.analysis.HighlightingLevelManager
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.platform.lsp.util.navigateOrShowPopup
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByDeclarationUsages
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.jetbrains.annotations.Nls

/**
 * The code vision above each declaration of a `.by` file — *3 usages*, *2 overrides* — as Java,
 * Kotlin and Python draw it, counted by `by` ([ByUsageCounts]).
 *
 * A daemon-bound provider, so the platform runs it in a highlighting pass whenever any PSI in the
 * project changes, not only this file's: a usage added in another file is counted when it is
 * typed. The platform's own LSP code lens is asked again only when its document changes, which is
 * why these are not `textDocument/codeLens` lenses.
 *
 * Grouped with the other languages' counts, so the *Usages* and *Inheritors* switches under
 * *Settings | Editor | Inlay Hints | Code vision* turn these off too, and a group that is off is
 * never asked for.
 *
 * ## What an entry keeps
 *
 * The platform keeps every entry, per editor, after this plugin is unloaded, so an entry is the
 * platform's own [TextCodeVisionEntry] and holds only platform objects: the declaration's name, and a
 * [RangeMarker] on it that follows the edits made before the next pass. A click asks `by` from where
 * the name is then.
 */
internal sealed class ByCountsCodeVisionProvider(private val count: ByUsageCounts.Count) : DaemonBoundCodeVisionProvider {

    override val defaultAnchor: CodeVisionAnchorKind get() = CodeVisionAnchorKind.Default

    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        if (file !is BasedPythonFile) return emptyList()
        val project = file.project
        if (project.isDefault) return emptyList()
        // as for every language's counts: none in a file highlighted below *All Problems*
        if (!HighlightingLevelManager.getInstance(project).shouldInspect(file)) return emptyList()
        val virtualFile = file.originalFile.virtualFile ?: return emptyList()
        // a stub or a library file opened from a navigation: its usages are not the project's
        if (!ProjectFileIndex.getInstance(project).isInContent(virtualFile)) return emptyList()

        val declarations = ByUsageCounts.getInstance(project).forFile(file, count) ?: return emptyList()
        return entries(editor.document, declarations)
    }

    /**
     * An entry over each of [declarations] that has something to say, `by`'s answer about
     * [document] as it is now, spanning the declaration from its first decorator.
     */
    internal fun entries(document: Document, declarations: List<ByDeclarationUsages>): List<Pair<TextRange, CodeVisionEntry>> =
        declarations.mapNotNull { declaration ->
            val text = text(declaration) ?: return@mapNotNull null
            val name = declaration.name ?: return@mapNotNull null
            val range = declaration.range?.let { getRangeInDocument(document, it) } ?: return@mapNotNull null
            val nameRange = declaration.selectionRange?.let { getRangeInDocument(document, it) } ?: return@mapNotNull null
            val entry = TextCodeVisionEntry(text, id)
            entry.putUserData(DECLARATION_NAME, name)
            entry.putUserData(DECLARATION_KIND, declaration.kind)
            entry.putUserData(NAME_MARKER, document.createRangeMarker(nameRange))
            range to entry
        }

    /** What the entry over [declaration] says, or `null` for none. */
    protected abstract fun text(declaration: ByDeclarationUsages): @Nls String?

    /**
     * The places a click on an entry offers: what [server] answers for the declaration whose name
     * starts at [position] in [file], or `null` when it did not answer.
     */
    protected abstract suspend fun places(
        server: LspClient,
        file: VirtualFile,
        document: TextDocumentIdentifier,
        position: Position,
    ): List<Location>?

    /** The title of the chooser among several of those places. */
    protected abstract fun chooserTitle(name: String, kind: String?): @Nls String

    override fun handleClick(editor: Editor, textRange: TextRange, entry: CodeVisionEntry) {
        val project = editor.project ?: return
        val marker = entry.getUserData(NAME_MARKER)?.takeIf { it.isValid } ?: return
        val name = entry.getUserData(DECLARATION_NAME) ?: return
        val kind = entry.getUserData(DECLARATION_KIND)
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val server = byServerFor(project, file) ?: return
        val mouseEvent = entry.getUserData(codeVisionEntryMouseEventKey)
        val title = chooserTitle(name, kind)
        project.service<ByCodeVisionScope>().scope.launch {
            val places = withBackgroundProgress(project, BasedPythonBundle.message("code.vision.progress", name)) {
                val (identifier, position) = readAction {
                    if (!marker.isValid) return@readAction null
                    server.getDocumentIdentifier(file) to getLsp4jPosition(marker.document, marker.startOffset)
                } ?: return@withBackgroundProgress null
                places(server, file, identifier, position)
            }
            withContext(Dispatchers.EDT) {
                if (editor.isDisposed) return@withContext
                when {
                    places == null -> hint(editor, BasedPythonBundle.message("code.vision.noAnswer"))
                    // counted before an edit elsewhere took the last of them away
                    places.isEmpty() -> hint(editor, nowhere(name))
                    else -> navigateOrShowPopup(server, places, title, mouseEvent)
                }
            }
        }
    }

    /** What a click says when there is nowhere to go from [name]. */
    protected abstract fun nowhere(name: String): @Nls String

    private fun hint(editor: Editor, text: @Nls String) {
        HintManager.getInstance().showInformationHint(editor, text)
    }

    companion object {
        private val DECLARATION_NAME = Key.create<String>("basedpython.codeVision.declarationName")
        private val DECLARATION_KIND = Key.create<String>("basedpython.codeVision.declarationKind")

        /** Where the declaration's name is, as edits move it. */
        internal val NAME_MARKER = Key.create<RangeMarker>("basedpython.codeVision.nameMarker")
    }
}

/**
 * *no usages*, *1 usage*, *3 usages* above each declaration, as Python words them. A click offers
 * the usages, as `textDocument/references` finds them without the declaration — the search the count
 * is.
 */
internal class ByUsagesCodeVisionProvider : ByCountsCodeVisionProvider(ByUsageCounts.Count.REFERENCES) {

    override val id: String get() = ID
    override val groupId: String get() = PlatformCodeVisionIds.USAGES.key
    override val name: String get() = BasedPythonBundle.message("code.vision.usages.name")
    override val relativeOrderings: List<CodeVisionRelativeOrdering>
        get() = listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingBefore(ByInheritorsCodeVisionProvider.ID))

    override fun text(declaration: ByDeclarationUsages): String? =
        declaration.references?.let { BasedPythonBundle.message("code.vision.usages", it) }

    override suspend fun places(
        server: LspClient,
        file: VirtualFile,
        document: TextDocumentIdentifier,
        position: Position,
    ): List<Location>? {
        val params = ReferenceParams(document, position, ReferenceContext(false))
        return when (val answer = server.awaitBy("textDocument/references") { it.textDocumentService.references(params) }) {
            is ByAnswer.Answer -> answer.value.filterNotNull()
            ByAnswer.None -> emptyList()
            ByAnswer.Failed -> null
        }
    }

    override fun chooserTitle(name: String, kind: String?): String =
        BasedPythonBundle.message("code.vision.usages.chooser", name)

    override fun nowhere(name: String): String = BasedPythonBundle.message("code.vision.usages.none", name)

    companion object {
        const val ID: String = "basedpython.references"
    }
}

/**
 * *2 inheritors* above a class, and *3 overrides* — *3 implementations*, for an abstract one — above
 * a method, as Java words them; nothing above one that has none. A click offers them, as
 * `textDocument/implementation` finds them less the declaration itself — the search the count is.
 */
internal class ByInheritorsCodeVisionProvider : ByCountsCodeVisionProvider(ByUsageCounts.Count.IMPLEMENTATIONS) {

    override val id: String get() = ID
    override val groupId: String get() = PlatformCodeVisionIds.INHERITORS.key
    override val name: String get() = BasedPythonBundle.message("code.vision.inheritors.name")
    override val relativeOrderings: List<CodeVisionRelativeOrdering> get() = emptyList()

    override fun text(declaration: ByDeclarationUsages): String? {
        val count = declaration.implementations?.takeIf { it > 0 } ?: return null
        val key = when {
            declaration.kind == CLASS -> "code.vision.inheritors"
            declaration.abstract -> "code.vision.implementations"
            else -> "code.vision.overrides"
        }
        return BasedPythonBundle.message(key, count)
    }

    override suspend fun places(
        server: LspClient,
        file: VirtualFile,
        document: TextDocumentIdentifier,
        position: Position,
    ): List<Location>? {
        val params = ImplementationParams(document, position)
        val found = when (
            val answer = server.awaitBy("textDocument/implementation") { it.textDocumentService.implementation(params) }
        ) {
            is ByAnswer.Answer -> answer.value.map(
                { locations -> locations.filterNotNull() },
                { links -> links.filterNotNull().map { Location(it.targetUri, it.targetSelectionRange) } },
            )
            ByAnswer.None -> emptyList()
            ByAnswer.Failed -> return null
        }
        // the declaration is its own first implementation, and is not counted
        return found.filterNot { it.range.start == position && server.descriptor.findFileByUri(it.uri) == file }
    }

    override fun chooserTitle(name: String, kind: String?): String =
        BasedPythonBundle.message(if (kind == CLASS) "code.vision.inheritors.chooser" else "code.vision.overrides.chooser", name)

    override fun nowhere(name: String): String = BasedPythonBundle.message("code.vision.inheritors.none", name)

    companion object {
        const val ID: String = "basedpython.inheritors"

        /** [ByDeclarationUsages.kind] of a class. */
        private const val CLASS = "class"
    }
}

/** Where a click's asking runs: cancelled with the project, and with the plugin when it unloads. */
@Service(Service.Level.PROJECT)
internal class ByCodeVisionScope(val scope: CoroutineScope)
