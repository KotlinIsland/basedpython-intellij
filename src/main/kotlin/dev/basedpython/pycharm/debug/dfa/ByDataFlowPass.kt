package dev.basedpython.pycharm.debug.dfa

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * Draws what the stopped program's own state settles about the code below it.
 *
 * A highlighting pass rather than an inlay hints provider, for the same reason the string margin is
 * one: the daemon owns when this runs, its cancellation and its per-editor lifetime, and
 * [ByDataFlowSession] restarts it when a stop changes what is known. An inlay provider would run
 * when the daemon felt like it, which is not when the program stopped.
 *
 * ## What is drawn, and what deliberately is not
 *
 * A decided condition gets an inline `= true` / `= false` beside it, and code that will not run is
 * greyed. Nothing else — in particular an *undecided* condition gets nothing at all. Ambiguous is
 * what an unseeded reading says about nearly every condition, and a hint on each of them would
 * be a screen full of marks that say nothing.
 */
class ByDataFlowPassFactory : TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar {

    override fun registerHighlightingPassFactory(
        registrar: TextEditorHighlightingPassRegistrar,
        project: Project,
    ) {
        registrar.registerTextEditorHighlightingPass(this, null, null, false, -1)
    }

    override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
        if (file.language != BasedPythonLanguage) return null
        if (!BasedPythonSettings.getInstance(file.project).debuggerDataFlow) return null
        return ByDataFlowPass(file.project, editor, file)
    }
}

/** What this pass drew last time, so it can take it down before drawing again. */
private val DRAWN: Key<List<RangeHighlighter>> = Key.create("basedpython.dataflow.drawn")

private class ByDataFlowPass(
    project: Project,
    private val editor: Editor,
    private val file: PsiFile,
) : TextEditorHighlightingPass(project, editor.document, false) {

    private var found: List<ByDataFlowFinding> = emptyList()

    override fun doCollectInformation(progress: ProgressIndicator) {
        val session = ByDataFlowSession.getInstance(myProject)
        // The ordinary case by a wide margin: nothing is being debugged, so there is nothing to
        // draw and nothing to walk to find that out
        if (session.isEmpty()) {
            found = emptyList()
            return
        }
        val virtualFile = file.originalFile.virtualFile ?: return
        found = session.findingsFor(virtualFile)
    }

    override fun doApplyInformationToEditor() {
        val markup = editor.markupModel
        editor.getUserData(DRAWN)?.forEach(markup::removeHighlighter)

        if (found.isEmpty()) {
            editor.putUserData(DRAWN, null)
            return
        }

        val drawn = found.mapNotNull { finding ->
            val range = finding.range.toTextRange(editor) ?: return@mapNotNull null
            when (finding.kind) {
                "unreachable" -> markup.addRangeHighlighter(
                    ByDataFlowColors.WILL_NOT_RUN,
                    range.first,
                    range.second,
                    HighlighterLayer.ADDITIONAL_SYNTAX,
                    HighlighterTargetArea.EXACT_RANGE,
                )

                "condition" -> markup.addRangeHighlighter(
                    ByDataFlowColors.DECIDED_CONDITION,
                    range.first,
                    range.second,
                    HighlighterLayer.ADDITIONAL_SYNTAX,
                    HighlighterTargetArea.EXACT_RANGE,
                ).also { highlighter ->
                    // The verdict itself, drawn after the condition rather than as a tooltip: the
                    // point of the feature is that it is readable without hovering
                    highlighter.customRenderer = ByDataFlowVerdictRenderer(finding.label)
                }

                // A kind this build does not know is one a newer server grew. Drawing it as
                // something else would be inventing a meaning for it
                else -> null
            }
        }
        editor.putUserData(DRAWN, drawn)
    }
}

/**
 * An LSP range as offsets in this editor's document, or `null` when it no longer fits.
 *
 * A range that does not fit is one the reply raced an edit to. The daemon is about to run again
 * against the new text, so dropping it is right — and drawing it at a clamped position would be
 * marking code the finding was never about.
 */
private fun org.eclipse.lsp4j.Range.toTextRange(editor: Editor): Pair<Int, Int>? {
    val document = editor.document
    fun offset(position: org.eclipse.lsp4j.Position): Int? {
        if (position.line < 0 || position.line >= document.lineCount) return null
        val lineStart = document.getLineStartOffset(position.line)
        val lineEnd = document.getLineEndOffset(position.line)
        val offset = lineStart + position.character
        return if (offset in lineStart..lineEnd) offset else null
    }
    val start = offset(start) ?: return null
    val end = offset(end) ?: return null
    return if (start <= end) start to end else null
}
