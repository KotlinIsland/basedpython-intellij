package dev.basedpython.pycharm.inspections.logpoint

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import dev.basedpython.pycharm.debug.ByBreakpointProperties
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import dev.basedpython.pycharm.debug.logpoint.ByLogpointUndo
import dev.basedpython.pycharm.debug.logpoint.ByLogpoints
import dev.basedpython.pycharm.debug.logpoint.PlatformLogpointInfo
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * Offers to swap a debug `print(...)` for a log point, the way Kotlin offers it for `println`.
 *
 * The IDE has no feature to borrow here. The whole logpoints implementation — the inter-line gutter
 * affordance, the inline editor, Ctrl+Alt+F8, and the JVM languages' own versions of this
 * inspection — ships in modules bundled with IntelliJ IDEA's Java plugin, and PyCharm carries none
 * of them, so anything built on it would exist in half the IDEs this plugin runs in. What the
 * *platform* has is the part that matters: a line breakpoint carries a log expression, and the DAP
 * client sends it on as `logMessage` (see [PrintToLogpoint] for what happens to it after that).
 * So the log point made here is an ordinary `.by` breakpoint with a log expression and no suspend,
 * which reads and behaves the same in both IDEs.
 *
 * WEAK WARNING rather than an information-level hint: a suggestion nobody can see is a suggestion
 * nobody uses, and this is exactly the noise level Kotlin's `println` inspection ships at.
 */
class PrintToLogpointInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "basedpython"
    override fun getDisplayName(): String = "print() call can be replaced with a log point"
    override fun getShortName(): String = "BasedPythonPrintToLogpoint"

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor> {
        if (file !is BasedPythonFile) return ProblemDescriptor.EMPTY_ARRAY
        val document = file.viewProvider.document ?: return ProblemDescriptor.EMPTY_ARRAY
        val outline = ByOutlines.getInstance(file.project).forFile(file) ?: return ProblemDescriptor.EMPTY_ARRAY
        return PrintToLogpoint.candidates(outline, document).mapNotNull { candidate ->
            val element = file.findElementAt(candidate.callOffset) ?: return@mapNotNull null
            manager.createProblemDescriptor(
                element,
                "Call to print can be replaced with a log point",
                ReplaceWithLogpointFix(),
                ProblemHighlightType.WEAK_WARNING,
                isOnTheFly,
            )
        }.toTypedArray()
    }
}

/**
 * Deletes the `print` statement and leaves a log point in the gap it occupied.
 */
private class ReplaceWithLogpointFix : LocalQuickFix {

    override fun getFamilyName(): String = "Replace print with a log point"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val file = element.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        val documentManager = PsiDocumentManager.getInstance(project)
        val document = documentManager.getDocument(file) ?: return

        // Re-derived from the document rather than trusted from the descriptor: an inspection result
        // can be acted on long after the highlight that produced it. From the outline of the text as
        // it is now, which is on hand whenever the inspection that offered this ran on this text; a
        // fix pressed before `by` has caught up with an edit does nothing rather than guess.
        val outline = ByOutlines.getInstance(project).current(document) ?: return
        val candidate = PrintToLogpoint.at(outline, document, element.textRange.startOffset) ?: return

        val type = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java) ?: return
        val breakpoints = XDebuggerManager.getInstance(project).breakpointManager
        // Asked before the deletion, so in the line numbering the document still has. A breakpoint
        // already sitting there is somebody's, with its own condition and log settings; taking the
        // line would overwrite them, and a second breakpoint on one line is not what was asked for.
        val taken = breakpoints.findBreakpointsAtLine<XLineBreakpoint<ByBreakpointProperties>, ByBreakpointProperties>(
            type, virtualFile, candidate.followerLine,
        )
        if (taken.isNotEmpty()) return

        document.deleteString(candidate.lineStart, candidate.lineEndWithSeparator)
        documentManager.commitDocument(document)

        // The log point goes on the follower line, which is what makes this read as a swap rather
        // than a move: its field is drawn above that line, where the deleted call was — the same
        // place Kotlin's leaves one. The follower is also the line it binds to, which is why the
        // follower has to be in the same block for this to be offered at all (see PrintToLogpoint).
        val expression = ByLogpoints.expressionOf(candidate.expression)
        val info = PlatformLogpointInfo.of(SuspendPolicy.NONE, expression)
        val breakpoint = breakpoints.addLineBreakpoint(
            type,
            virtualFile.url,
            candidate.logpointLine,
            ByLogpoints.logpointProperties(),
            info,
        )

        // Undo has to take both halves or neither. Without this the deleted line came back and the
        // log point stayed, so the value was logged twice — the one outcome nobody asked for.
        ByLogpointUndo.record(project, document, breakpoint)
    }

    /**
     * The preview shows the deletion only. Its file is a throwaway copy, and a breakpoint added
     * against that copy's URL would be a real entry in the user's breakpoint list pointing at a file
     * that stops existing when the popup closes.
     */
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
        val element = previewDescriptor.psiElement ?: return IntentionPreviewInfo.EMPTY
        val copy = element.containingFile ?: return IntentionPreviewInfo.EMPTY
        val document = copy.viewProvider.document ?: return IntentionPreviewInfo.EMPTY
        // The copy is the original's text under another name, and `by` has only been asked about
        // the original — whose outline therefore places the call in the copy too.
        val original = copy.originalFile
        val originalDocument = original.viewProvider.document ?: return IntentionPreviewInfo.EMPTY
        val outline = ByOutlines.getInstance(project).forFile(original) ?: return IntentionPreviewInfo.EMPTY
        val candidate = PrintToLogpoint.at(outline, originalDocument, element.textRange.startOffset)
            ?: return IntentionPreviewInfo.EMPTY
        document.deleteString(candidate.lineStart, candidate.lineEndWithSeparator)
        return IntentionPreviewInfo.DIFF
    }
}
