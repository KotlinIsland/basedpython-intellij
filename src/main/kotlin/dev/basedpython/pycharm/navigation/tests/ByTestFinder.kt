package dev.basedpython.pycharm.navigation.tests

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.testIntegration.TestFinder
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Go to Test and Go to Test Subject (Ctrl+Shift+T) in a `.by` file: from code to the tests that call
 * it, and from a test to the code it calls.
 *
 * Without this the action is not there at all in a `.by` file — the platform shows it only where a
 * finder claims the caret. Both directions are `by`'s ([ByTestLinks]): a test exercises what it
 * calls, and which declarations are tests is pytest's collection, so `test_circle.by` goes to
 * whatever it calls, wherever that is, and a class's tests are found whatever their files are named.
 *
 * ## Which direction
 *
 * The platform asks [isTest] first, on the EDT, and goes to test subjects when it is true. A `.by`
 * file is a test module when `by/testItems` collects a test from it, and that answer is the one the
 * gutter's run icons are drawn from ([ByProgramModel.testItems]): asked and waited for when the
 * action's state is updated in the background, and on the EDT served from what is already known,
 * without asking.
 *
 * ## Threading
 *
 * [findTestsForClass] and [findClassesForTest] run on a background thread inside a read action,
 * under a modal progress the user can cancel, and wait on `by` there. The requests name no text:
 * the `didOpen` such a request would wait for is sent by the platform only outside a modal (see
 * [dev.basedpython.pycharm.lsp.ext.ByTestLinksParams]).
 *
 * A `by` from before the requests, or one that does not answer, is said in a notification: the
 * platform's own message for no targets is "No tests found", which would be a wrong answer.
 */
class ByTestFinder : TestFinder {

    /** The `.by` file the caret is in, when there is a `by` to ask about it. */
    override fun findSourceElement(from: PsiElement): PsiElement? {
        val file = from.containingFile as? BasedPythonFile ?: return null
        val virtualFile = file.originalFile.virtualFile ?: return null
        return file.takeIf { byServerFor(file.project, virtualFile) != null }
    }

    override fun isTest(element: PsiElement): Boolean {
        val file = element.containingFile as? BasedPythonFile ?: return false
        val virtualFile = file.originalFile.virtualFile ?: return false
        return !ByProgramModel.getInstance(file.project).testItems(virtualFile).isNullOrEmpty()
    }

    override fun findTestsForClass(element: PsiElement): Collection<PsiElement> {
        val (client, file, document) = context(element) ?: return emptyList()
        return when (val answer = ByTestLinks.subjectTests(client, file, document, element)) {
            is ByTestLinksAnswer.Answered -> answer.value.tests.mapNotNull { ByTestLinks.target(client, it) }
            ByTestLinksAnswer.Nothing -> emptyList()
            ByTestLinksAnswer.Unsupported -> unanswered(element.project, "goto.test.unsupported", "by/subjectTests")
            ByTestLinksAnswer.Failed -> unanswered(element.project, "goto.test.noAnswer", "by/subjectTests")
        }
    }

    override fun findClassesForTest(element: PsiElement): Collection<PsiElement> {
        val (client, file, document) = context(element) ?: return emptyList()
        return when (val answer = ByTestLinks.testSubjects(client, file, document, element)) {
            is ByTestLinksAnswer.Answered -> answer.value.mapNotNull { ByTestLinks.target(client, it) }
            ByTestLinksAnswer.Nothing -> emptyList()
            ByTestLinksAnswer.Unsupported -> unanswered(element.project, "goto.test.unsupported", "by/testSubjects")
            ByTestLinksAnswer.Failed -> unanswered(element.project, "goto.test.noAnswer", "by/testSubjects")
        }
    }

    override fun getSearchingForTestsForClassProgressTitle(element: PsiElement): String? =
        if (element.containingFile is BasedPythonFile) BasedPythonBundle.message("goto.test.progress.tests") else null

    override fun getSearchingForClassesForTestProgressTitle(element: PsiElement): String? =
        if (element.containingFile is BasedPythonFile) BasedPythonBundle.message("goto.test.progress.subjects") else null

    private fun context(element: PsiElement) = run {
        val file = element.containingFile as? BasedPythonFile ?: return@run null
        val virtualFile = file.originalFile.virtualFile ?: return@run null
        val client = byServerFor(file.project, virtualFile) ?: return@run null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@run null
        Triple(client, virtualFile, document)
    }

    /** Says why there is nothing to go to, and goes nowhere. */
    private fun unanswered(project: Project, key: String, method: String): List<PsiElement> {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("basedpython")
            .createNotification(BasedPythonBundle.message(key, method), NotificationType.WARNING)
            .notify(project)
        return emptyList()
    }
}
