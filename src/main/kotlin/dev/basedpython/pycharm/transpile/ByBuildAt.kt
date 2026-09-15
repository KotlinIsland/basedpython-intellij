package dev.basedpython.pycharm.transpile

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.run.ByBuildOptions
import dev.basedpython.pycharm.run.ByBuildService
import dev.basedpython.pycharm.run.byArguments
import dev.basedpython.pycharm.run.byCommandLine
import java.nio.file.Path

/**
 * Runs `by build` in [root] for an action that needs one file of the output, and waits for it.
 *
 * [root] is the project root `by/buildOutput` named for the file the action is about, so the build
 * is of that project and writes where `by/buildOutput` said it would. Through [ByBuildService],
 * so the build waits its turn behind a watch-mode build of the same tree rather than writing over
 * it.
 *
 * `null` when nothing was built: no `by` to run (already reported) or the build was cancelled.
 * Background threads only.
 */
internal fun buildAt(project: Project, root: Path, title: String): ProcessOutput? {
    val cmd = try {
        byCommandLine(
            project,
            ByBuildOptions().apply { workingDir = root.toString() },
            byArguments("build", "--min-version", "", emptyList(), ""),
        )
    } catch (_: ExecutionException) {
        ByCli.notifyBinaryMissing(project, "by")
        return null
    }
    return ByBuildService.getInstance(project).buildBlocking(cmd, title)
}
