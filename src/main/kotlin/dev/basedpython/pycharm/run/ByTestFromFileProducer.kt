package dev.basedpython.pycharm.run

import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfigurationType
import dev.basedpython.pycharm.run.test.ByTestDeclarations
import dev.basedpython.pycharm.run.test.node.ByTestLookup
import dev.basedpython.pycharm.run.test.tree.ByTestSources
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Right-click a `.by` test file (or click the "Run test" gutter icon) → produce a configuration
 * that runs `by run pytest -v <path>[::Class][::test_name]`.
 *
 * The target names the `.by` source the user is looking at; rewriting it onto the transpiled `.py`
 * that pytest actually collects happens when the command line is built, in
 * [dev.basedpython.pycharm.run.test.ByPytest].
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
 * Builds the pytest target for [context], or null when the context is not a `.by` test
 * declaration. Returns `<relpath>`, `<relpath>::test_name`, or `<relpath>::Class::test_name`,
 * with the path still naming the `.by` source.
 *
 * Which declarations count as tests is [ByTestLookup]'s verdict, the same one the gutter icons are
 * drawn from — see there for why the two cannot be allowed to disagree.
 */
private fun testTargetFor(context: ConfigurationContext): String? {
    val element = context.psiLocation ?: return null
    val file = context.location?.virtualFile
        ?: element.containingFile?.virtualFile
        ?: return null
    if (file.extension != "by") return null

    // The whole file is the target when the context is not inside a declaration — right-clicking
    // the file in the project view, or a context with no document behind it.
    val relPath = ByTestSources.relativePath(context.project, file) ?: file.path

    val document = PsiDocumentManager.getInstance(context.project)
        .getDocument(element.containingFile ?: return relPath)
        ?: return relPath
    val offset = element.textRange.startOffset
    if (offset >= document.textLength) return relPath

    val declaration = ByTestDeclarations.declarationAt(
        lineText = { line -> lineText(document, line) },
        lineCount = document.lineCount,
        line = document.getLineNumber(offset),
    ) ?: return null
    if (ByTestLookup.verdict(context.project, file, declaration) is ByTestLookup.Verdict.NotATest) {
        return null
    }
    return relPath + "::" + declaration.symbols.joinToString("::")
}

private fun lineText(document: Document, line: Int): String {
    val start = document.getLineStartOffset(line)
    val end = document.getLineEndOffset(line)
    return document.getText(TextRange(start, end))
}
