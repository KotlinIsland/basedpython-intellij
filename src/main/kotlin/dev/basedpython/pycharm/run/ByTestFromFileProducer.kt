package dev.basedpython.pycharm.run

import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.run.model.innermostAt
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfigurationType
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.execution.ParametersListUtil

/**
 * Right-click a `.by` test file (or click the "Run test" gutter icon) → produce a configuration
 * that runs `by run --in-build pytest -v <path>[::Class][::test_name]`; right-click a directory →
 * one that runs every test file under it.
 *
 * The target is the node id pytest gives the test in the tree `by run` stages: the file as `by`
 * stages it ([ByProgramModel.stagedPath]), then the names `by/testItems` gives the test.
 *
 * Without this producer the test gutter icons contributed by
 * [dev.basedpython.pycharm.run.testmarker.ByTestRunLineMarkerContributor] have no configuration
 * to resolve to.
 *
 * A file of tests is this producer's alone: the `by run` and `by check` producers make nothing in a
 * `.by` file that holds tests or that `by` has not yet said holds none
 * ([ByProgramModel.mayHoldTests]). So nothing here outranks them; they are never offered beside it.
 * Ranking them instead was not enough: a ranking only orders the producers that answered, and until
 * `by` had said where the tests are this one could not, so the first Ctrl+Shift+R in a test of a
 * freshly opened file, or straight after an edit to one, ran `by run` on the whole module (measured
 * in PyCharm 263.7528, three runs out of three after an edit).
 */
class ByTestFromFileProducer : LazyRunConfigurationProducer<ByTestConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ByTestConfigurationType.getInstance().testFactory

    override fun setupConfigurationFromContext(
        configuration: ByTestConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val target = testTargetFor(context) ?: return false
        configuration.options.paths = target.paths
        configuration.name = target.name
        val base = context.project.basePath
        if (!base.isNullOrBlank() && configuration.options.workingDir.isBlank()) {
            configuration.options.workingDir = base
        }
        return true
    }

    override fun isConfigurationFromContext(
        configuration: ByTestConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val target = testTargetFor(context) ?: return false
        return configuration.options.paths == target.paths
    }
}

/** What a context runs: pytest's targets, and what the configuration is called. */
private class TestTarget(val paths: String, val name: String)

/**
 * The pytest targets for [context], or null when the context holds no test: a directory's test files,
 * or [fileTargetFor] a file's or a test's.
 *
 * A directory in the project view runs every test file under it, as `.py`'s *Run 'Python tests in
 * tests'* does: each by the path `by run` stages it at, which follows the module tree rather than
 * the directories, so the directory is never a path pytest is given itself. Which files hold tests
 * is the project-wide `by/testItems` answer; null while it, or any of those files' staged path, is
 * not known yet.
 */
private fun testTargetFor(context: ConfigurationContext): TestTarget? {
    val directory = (context.psiLocation as? PsiDirectory)?.virtualFile
        ?: return fileTargetFor(context)?.let { TestTarget(it, "pytest $it") }
    val model = ByProgramModel.getInstance(context.project)
    val files = model.projectTests()?.byFile.orEmpty()
        .filter { (file, tests) -> tests.isNotEmpty() && VfsUtilCore.isAncestor(directory, file, true) }
        .keys.sortedBy { it.path }
    if (files.isEmpty()) return null
    val staged = files.map { model.stagedPath(it) ?: return null }
    return TestTarget(ParametersListUtil.join(staged), "pytest in ${directory.name}")
}

/**
 * Builds the pytest target for [context], or null when the context holds no test. Returns
 * `<staged>`, `<staged>::test_name`, or `<staged>::Class::test_name`, where `<staged>` is the path
 * `by run` stages the file at — null too while `by` has not said where that is.
 *
 * Which declarations are tests is `by/testItems`' answer ([ByProgramModel]), the same one the gutter
 * icons are drawn from: an icon whose producer declined would leave a green arrow that runs nothing.
 *
 * A context inside a test — its `def` line, which is where the gutter icon puts it, or anywhere in
 * its body — targets the innermost test or class around it. Any other context in a file that holds
 * tests — the file in the project view, a line between tests — targets the whole file.
 *
 * A position inside the file is answered from `by`'s answer for the document's current revision,
 * waited for off the EDT as [ByProgramModel.testItems] does, and the file as a whole from that or,
 * on the EDT, the project-wide answer, which holds files nobody has opened.
 */
private fun fileTargetFor(context: ConfigurationContext): String? {
    val element = context.psiLocation ?: return null
    val file = context.location?.virtualFile
        ?: element.containingFile?.virtualFile
        ?: return null
    if (file.extension != "by") return null
    val model = ByProgramModel.getInstance(context.project)
    val staged = model.stagedPath(file) ?: return null

    val psiFile = element.containingFile
    val document = psiFile?.let { PsiDocumentManager.getInstance(context.project).getDocument(it) }
    val offset = element.textRange.startOffset
    if (element !is PsiFile && document != null && offset < document.textLength) {
        val items = model.testItems(file) ?: return null
        if (items.isEmpty()) return null
        val line = document.getLineNumber(offset)
        val item = items.innermostAt(line, offset - document.getLineStartOffset(line))
            ?: return staged
        return staged + "::" + item.symbols.joinToString("::")
    }

    val holdsTests = model.testItems(file)?.isNotEmpty()
        ?: model.projectTests()?.let { it.byFile[file]?.isNotEmpty() ?: false }
        ?: return null
    return staged.takeIf { holdsTests }
}
