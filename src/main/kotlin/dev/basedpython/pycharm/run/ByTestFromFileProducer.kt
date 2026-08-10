package dev.basedpython.pycharm.run

import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfigurationType
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
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
 */
private fun testTargetFor(context: ConfigurationContext): String? {
    val element = context.psiLocation ?: return null
    val file = context.location?.virtualFile
        ?: element.containingFile?.virtualFile
        ?: return null
    if (file.extension != "by") return null

    val relPath = relativePathFor(context, file)

    val document = PsiDocumentManager.getInstance(context.project)
        .getDocument(element.containingFile ?: return relPath)
        ?: return relPath
    val offset = element.textRange.startOffset
    if (offset >= document.textLength) return relPath

    val nodeId = testNodeId(document, document.getLineNumber(offset)) ?: return null
    return "$relPath::$nodeId"
}

/** Project-relative path of [file] (system-independent `/`), falling back to the absolute path. */
private fun relativePathFor(context: ConfigurationContext, file: VirtualFile): String {
    val base = context.project.basePath
    if (base.isNullOrBlank()) return file.path
    val baseDir = LocalFileSystem.getInstance().findFileByPath(base) ?: return file.path
    return VfsUtilCore.getRelativePath(file, baseDir, '/') ?: file.path
}

private val TEST_DEF = Regex("""^(\s*)(?:async\s+)?def\s+(test_\w*)\s*\(.*$""")
private val TEST_CLASS = Regex("""^\s*class\s+(Test\w*)\s*[(:].*$""")
private val ANY_CLASS = Regex("""^(\s*)class\s+(\w+)\s*[(:].*$""")

/**
 * The pytest-style node id for the declaration on [line], or null when [line] is not a test
 * declaration. A `def test_…` method nested in a `class …` becomes `Class::test_name`.
 */
private fun testNodeId(document: Document, line: Int): String? {
    val text = lineText(document, line)
    TEST_CLASS.matchEntire(text)?.let { return it.groupValues[1] }
    val defMatch = TEST_DEF.matchEntire(text) ?: return null
    val indent = defMatch.groupValues[1].length
    val method = defMatch.groupValues[2]
    if (indent == 0) return method
    // Walk upward for the nearest less-indented enclosing class.
    for (l in line - 1 downTo 0) {
        val m = ANY_CLASS.matchEntire(lineText(document, l)) ?: continue
        if (m.groupValues[1].length < indent) return "${m.groupValues[2]}::$method"
    }
    return method
}

private fun lineText(document: Document, line: Int): String {
    val start = document.getLineStartOffset(line)
    val end = document.getLineEndOffset(line)
    return document.getText(TextRange(start, end))
}
