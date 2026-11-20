package dev.basedpython.pycharm.run

import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.run.model.innermostAt
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfigurationType
import dev.basedpython.pycharm.run.test.ByTestRunner
import dev.basedpython.pycharm.run.test.ByTestRunners
import dev.basedpython.pycharm.run.test.ByTestTargets
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources
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
 * Right-click a test file `by` serves (or click the "Run test" gutter icon) → produce a configuration
 * that runs `by run --in-place pytest -v <path>[::Class][::test_name]`; right-click a directory →
 * one that runs every test file under it.
 *
 * The target is the node id pytest gives the test in a run in place: the file by its path from the
 * project ([ByTestTargets]), then the names `by/testItems` gives the test. A run that is not in place
 * writes a `.by` where `by` stages it when it starts ([dev.basedpython.pycharm.run.test.ByPytest]). A
 * `.py` that `by` serves — a basedpython project's, or any when `.py` is treated as basedpython — is
 * not staged by the `by run` this plugin bundles, so its tests run on the project's interpreter
 * ([ByTestOptions.plainPytest]), named by the same path. Without this, such a file had no
 * way to run a test in IntelliJ IDEA, where no Python plugin's pytest configuration is there to.
 *
 * What runs them is [ByTestRunners]' choice: pytest, or django's `manage.py test` for a `unittest`
 * test in a project that runs its tests that way.
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
        configuration.options.plainPytest = target.plain
        configuration.options.runner = target.runner
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
        val options = configuration.options
        return options.paths == target.paths && options.plainPytest == target.plain && options.runner == target.runner
    }
}

/** What a context runs: pytest's targets, who starts it and with which runner, and what the configuration is called. */
private class TestTarget(val paths: String, val plain: Boolean, val runner: ByTestRunner, scope: String = paths) {
    val name: String = (if (runner == ByTestRunner.DJANGO) "Django tests " else "pytest ") + scope
}

/** A file's target: its node id, and whether it runs on the project's interpreter rather than through `by run`. */
private class FileTarget(val path: String, val plain: Boolean, val runner: ByTestRunner)

/**
 * The pytest targets for [context], or null when the context holds no test: a directory's test files,
 * or [fileTargetFor] a file's or a test's.
 *
 * A directory in the project view runs every test file under it, as `.py`'s *Run 'Python tests in
 * tests'* does: each by its own path, so that a run that is not in place can name each where `by`
 * stages it, which follows the module tree rather than the directories. Which files hold tests is the
 * project-wide `by/testItems` answer; null while it is not known yet.
 */
private fun testTargetFor(context: ConfigurationContext): TestTarget? {
    val directory = (context.psiLocation as? PsiDirectory)?.virtualFile
        ?: return fileTargetFor(context)?.let { TestTarget(it.path, it.plain, it.runner) }
    val project = context.project
    val model = ByProgramModel.getInstance(project)
    val tests = model.projectTests()?.byFile.orEmpty()
        .filter { (file, tests) -> tests.isNotEmpty() && VfsUtilCore.isAncestor(directory, file, true) }
    if (tests.isEmpty()) return null
    val files = tests.keys.sortedBy { it.path }
    // `.by` and `.py` are two commands; a directory holding both is not one run of either
    val plain = files.none { it.extension == BY_EXTENSION }
    if (!plain && files.any { it.extension != BY_EXTENSION }) return null
    val paths = files.map { file -> ByTestTargets.of(project, file) ?: return null }
    val runners = files.map { file -> ByTestRunners.forFile(project, file, unittest = tests.getValue(file).any { it.holdsUnittest }) }.toSet()
    val runner = runners.singleOrNull() ?: ByTestRunner.PYTEST
    return TestTarget(ParametersListUtil.join(paths), plain, runner, "in ${directory.name}")
}

/**
 * Builds the pytest target for [context], or null when the context holds no test. Returns `<file>`,
 * `<file>::test_name`, or `<file>::Class::test_name`, where `<file>` is the file's path from the
 * project ([ByTestTargets.of]).
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
private fun fileTargetFor(context: ConfigurationContext): FileTarget? {
    val element = context.psiLocation ?: return null
    val file = context.location?.virtualFile
        ?: element.containingFile?.virtualFile
        ?: return null
    if (!BasedPythonSources.isOwnedSource(file)) return null
    val project = context.project
    val model = ByProgramModel.getInstance(project)
    val plain = file.extension != BY_EXTENSION
    val path = ByTestTargets.of(project, file) ?: return null
    fun target(path: String, unittest: Boolean) = FileTarget(path, plain, ByTestRunners.forFile(project, file, unittest))

    val psiFile = element.containingFile
    val document = psiFile?.let { PsiDocumentManager.getInstance(context.project).getDocument(it) }
    val offset = element.textRange.startOffset
    if (element !is PsiFile && document != null && offset < document.textLength) {
        val items = model.testItems(file) ?: return null
        if (items.isEmpty()) return null
        val line = document.getLineNumber(offset)
        val item = items.innermostAt(line, offset - document.getLineStartOffset(line))
            ?: return target(path, items.any { it.holdsUnittest })
        return target(path + "::" + item.symbols.joinToString("::"), item.holdsUnittest)
    }

    val items = model.testItems(file) ?: model.projectTests()?.let { it.byFile[file].orEmpty() } ?: return null
    return target(path, items.any { it.holdsUnittest }).takeIf { items.isNotEmpty() }
}

private const val BY_EXTENSION = "by"
