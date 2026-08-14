package dev.basedpython.pycharm.run.test.node

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfigurationType
import dev.basedpython.pycharm.run.test.tree.ByTestLocations
import dev.basedpython.pycharm.run.test.tree.ByTestSources

/**
 * What the node view can do with a node: run it, and open the code behind it.
 *
 * Both take a pytest target as the tree stores it — naming the transpiled `.py`, exactly as pytest
 * reported it — and translate it back to the `.by` source themselves, so a caller never has to
 * remember which of the two worlds a string is in.
 */
internal object ByTestNodeActions {

    /**
     * Runs [target] (null meaning the whole project) under [executor].
     *
     * The configuration is created as a temporary one, the same thing the gutter icons and
     * right-click Run produce: it shows up in the run combo box, can be edited or saved from
     * there, and is evicted once enough others accumulate.
     */
    fun run(
        project: Project,
        target: String?,
        executor: Executor,
        source: ByTestSource = ByTestSource.TRANSPILED,
    ) {
        // A transpiled target names a `.py` that stands for a `.by`, and the configuration rewrites
        // it back on the way out; a target plain pytest collected already names the file to run.
        val plain = source == ByTestSource.PYTHON
        val paths = target?.let { if (plain) it else ByTestNodes.sourceTarget(it) }.orEmpty()
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration(
            if (paths.isBlank()) "pytest" else "pytest $paths",
            ByTestConfigurationType.getInstance().testFactory,
        )
        val configuration = settings.configuration as ByTestConfiguration
        configuration.options.paths = paths
        configuration.options.plainPytest = plain
        if (configuration.options.workingDir.isBlank()) {
            project.basePath?.let { configuration.options.workingDir = it }
        }
        runManager.setTemporaryConfiguration(settings)
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }

    /**
     * Opens the declaration [target] was collected from — the `.by` it was transpiled from, or the
     * `.py` itself — and reports whether it could.
     *
     * Deliberately the same textual resolution the test tree of a *run* navigates with
     * ([ByTestLocations]): the PSI for `.by` is flat, so there are no declarations to walk, and
     * both views should land on the same line for the same node id.
     */
    fun navigate(
        project: Project,
        target: String?,
        source: ByTestSource = ByTestSource.TRANSPILED,
    ): Boolean {
        val location = target?.let(ByTestLocations::parse) ?: return false
        // For a test plain pytest collected, the `.py` in the node id *is* the source file; the
        // `.by` [ByTestLocations] maps to does not exist.
        val path = if (source == ByTestSource.PYTHON) target.substringBefore("::") else location.file
        val file = ByTestSources.findSourceFile(project, path) ?: return false
        val text = FileDocumentManager.getInstance().getDocument(file)?.charsSequence?.toString()
        val offset = text
            ?.takeIf { location.symbols.isNotEmpty() }
            ?.let { ByTestLocations.declarationOffset(it, location.symbols) }
            ?: 0
        OpenFileDescriptor(project, file, offset).navigate(true)
        return true
    }
}
