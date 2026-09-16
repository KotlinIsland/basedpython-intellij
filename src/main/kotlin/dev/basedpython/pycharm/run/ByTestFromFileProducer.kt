package dev.basedpython.pycharm.run

import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.run.model.innermostAt
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfigurationType
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Right-click a `.by` test file (or click the "Run test" gutter icon) → produce a configuration
 * that runs `by run --in-build pytest -v <path>[::Class][::test_name]`.
 *
 * The target is the node id pytest gives the test in the tree `by run` stages: the file as `by`
 * stages it ([ByProgramModel.stagedPath]), then the names `by/testItems` gives the test.
 *
 * Without this producer the test gutter icons contributed by
 * [dev.basedpython.pycharm.run.testmarker.ByTestRunLineMarkerContributor] have no configuration
 * to resolve to and silently fall through to `by run <module>`, running the whole module instead
 * of the test.
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
        configuration.options.paths = target
        configuration.name = "pytest $target"
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
        return configuration.options.paths == target
    }

    /**
     * Running one test is more specific than running the whole module, so prefer this over
     * [ByRunConfiguration] when both producers match the same context (e.g. the gutter icon on a
     * `def test_…` line).
     */
    override fun isPreferredConfiguration(self: ConfigurationFromContext?, other: ConfigurationFromContext?): Boolean =
        other?.configuration is ByRunConfiguration

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean =
        other.configuration is ByRunConfiguration
}

/**
 * Builds the pytest target for [context], or null when the context holds no test. Returns
 * `<staged>`, `<staged>::test_name`, or `<staged>::Class::test_name`, where `<staged>` is the path
 * `by run` stages the file at — null too while `by` has not said where that is.
 *
 * Which declarations are tests is `by/testItems`' answer ([ByProgramModel]), the same one the gutter
 * icons are drawn from: an icon whose producer declined would leave a green arrow that runs the
 * whole module through the plain `by run` producer instead.
 *
 * A context inside a test — its `def` line, which is where the gutter icon puts it, or anywhere in
 * its body — targets the innermost test or class around it. Any other context in a file that holds
 * tests — the file in the project view, a line between tests — targets the whole file.
 *
 * Never waits for the server, since a producer runs inside a read action while a menu is built: a
 * position inside the file is answered from the answer held for the document's current revision,
 * and the file as a whole from the project-wide answer, which holds files nobody has opened.
 */
private fun testTargetFor(context: ConfigurationContext): String? {
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
        val items = model.cachedTestItems(file) ?: return null
        if (items.isEmpty()) return null
        val line = document.getLineNumber(offset)
        val item = items.innermostAt(line, offset - document.getLineStartOffset(line))
            ?: return staged
        return staged + "::" + item.symbols.joinToString("::")
    }

    val holdsTests = model.cachedTestItems(file)?.isNotEmpty()
        ?: model.projectTests()?.let { it.byFile[file]?.isNotEmpty() ?: false }
        ?: return null
    return staged.takeIf { holdsTests }
}
