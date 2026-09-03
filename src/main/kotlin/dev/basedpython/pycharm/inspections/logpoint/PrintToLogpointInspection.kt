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
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import dev.basedpython.pycharm.debug.logpoint.ByLogpointUndo
import dev.basedpython.pycharm.debug.logpoint.ByLogpoints
import dev.basedpython.pycharm.debug.logpoint.PlatformLogpointInfo
import dev.basedpython.pycharm.lang.BasedPythonFile

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
        val text = file.text
        return PrintToLogpoint.candidates(text).mapNotNull { candidate ->
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
        // can be acted on long after the highlight that produced it.
        val candidate = PrintToLogpoint.at(document.charsSequence, element.textRange.startOffset) ?: return

        val type = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java) ?: return
        val breakpoints = XDebuggerManager.getInstance(project).breakpointManager
        // Asked before the deletion, so in the line numbering the document still has. A breakpoint
        // already sitting there is somebody's, with its own condition and log settings; taking the
        // line would overwrite them, and a second breakpoint on one line is not what was asked for.
        if (breakpoints.findBreakpointAtLine(type, virtualFile, candidate.followerLine) != null) return

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

        // Re-stated with a language attached, so the expression edits as basedpython in the
        // breakpoint dialog rather than as plain text — which is what the info loses on 262, where
        // it carries the expression as a `String`. Unconditional, because the log point and the
        // deleted `print` are the same edit: one that ended up without its expression would be a
        // silent deletion, which is the one outcome this fix must not produce.
        breakpoint.logExpressionObject = expression

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
        val document = element.containingFile?.viewProvider?.document ?: return IntentionPreviewInfo.EMPTY
        val candidate = PrintToLogpoint.at(document.charsSequence, element.textRange.startOffset)
            ?: return IntentionPreviewInfo.EMPTY
        document.deleteString(candidate.lineStart, candidate.lineEndWithSeparator)
        return IntentionPreviewInfo.DIFF
    }
}
