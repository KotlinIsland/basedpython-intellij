package dev.basedpython.pycharm.run.test.node

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfigurationType
import dev.basedpython.pycharm.run.test.tree.ByTestSources

/**
 * What the node view can do with a node: run it, and open the code behind it.
 *
 * Both take a pytest target as the tree stores it — naming the file in the tree `by run` stages, or
 * the `.py` plain pytest collected, exactly as pytest reported it.
 */
internal object ByTestNodeActions {

    /**
     * Runs [target] (null meaning the whole project) under [executor].
     *
     * The configuration is created as a temporary one, the same thing the gutter icons and
     * right-click Run produce: it shows up in the run combo box, can be edited or saved from
     * there, and is evicted once enough others accumulate.
     */
    /**
     * Runs everything, which takes one launch per kind of test the tree holds.
     *
     * The two halves are two different commands — `by run pytest` cannot see a `.py` test and plain
     * pytest is not given the transpiled tree — so "run all" in a project with both is genuinely two
     * runs, and pretending otherwise would silently skip half the suite. A project with one kind
     * gets one run, which is every project until it isn't.
     */
    fun runAll(project: Project, executor: Executor, sources: Set<ByTestSource>) {
        val kinds = sources.ifEmpty { setOf(ByTestSource.TRANSPILED) }
        for (source in kinds.sortedBy { it.ordinal }) run(project, null, executor, source)
    }

    fun run(
        project: Project,
        target: String?,
        executor: Executor,
        source: ByTestSource = ByTestSource.TRANSPILED,
    ) {
        // Either kind of target is already what its pytest run takes: a transpiled one names the file
        // where `by run` stages it, and one plain pytest collected names the file in the project.
        val plain = source == ByTestSource.PYTHON
        val paths = target.orEmpty()
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration(
            when {
                paths.isNotBlank() -> "pytest $paths"
                plain -> "pytest (.py tests)"
                else -> "pytest"
            },
            ByTestConfigurationType.getInstance().testFactory,
        )
        val configuration = settings.configuration as ByTestConfiguration
        configuration.options.paths = paths
        configuration.options.plainPytest = plain
        if (configuration.options.workingDir.isBlank()) {
            project.basePath?.let { configuration.options.workingDir = it }
        }
        runManager.setTemporaryConfiguration(settings)
        // Show the scope as running before the process has said anything; see [markRunning].
        ByTestNodeService.getInstance(project).markRunning(target, source)
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }

    /**
     * Opens the declaration [target] was collected from — in the `.by` `by run` staged it from, or the
     * `.py` itself — and reports whether it could.
     *
     * The same resolution the test tree of a *run* navigates with ([ByTestSources.locate]), so both
     * views land on the same name for the same node id.
     */
    fun navigate(
        project: Project,
        target: String?,
        source: ByTestSource = ByTestSource.TRANSPILED,
    ): Boolean {
        val place = target?.let { ByTestSources.locate(project, it, transpiled = source == ByTestSource.TRANSPILED) }
            ?: return false
        OpenFileDescriptor(project, place.file, place.offset).navigate(true)
        return true
    }
}
