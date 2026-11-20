package dev.basedpython.pycharm.run.target

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.target.TargetEnvironment
import com.intellij.execution.target.TargetedCommandLine
import com.intellij.openapi.progress.EmptyProgressIndicator
import dev.basedpython.pycharm.lsp.ByCommandFlags
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.util.concurrent.TimeUnit

/**
 * What the `by` on a run target takes on its command line, asked of that `by` itself.
 *
 * The project's language server answers for the `by` it is ([ByCommandFlags.of]), which is this
 * machine's and need not be the target's: a container or a remote machine has a `by` of its own,
 * newer or older. So the target's is run once the target exists, as `by command-line-flags`
 * ([ByCommandFlags.PRINT_COMMAND]), which prints the same table its server would answer `initialize`
 * with. A `by` from before the command refuses it, and is one that says nothing ([ByCommandFlags.NONE]),
 * which is what a server that answers without the table says too.
 */
internal object ByTargetFlags {

    /**
     * How long the target's `by` has to answer. Starting a process on a target can itself take a
     * while — a container's `exec`, an SSH channel — so this bounds a `by` that never answers rather
     * than timing one that does.
     */
    private const val TIMEOUT_SECONDS = 60L

    /**
     * Runs [commandLine], `by command-line-flags` in the target's terms, in [environment] and answers
     * what it printed.
     *
     * @throws ExecutionException when the process cannot be started there, or does not end in time:
     *   either leaves the question open, and a run started on a guess may be refused by its `by`.
     */
    fun ask(environment: TargetEnvironment, commandLine: TargetedCommandLine): ByCommandFlags {
        val process = environment.createProcess(commandLine, EmptyProgressIndicator())
        val presentation = commandLine.getCommandPresentation(environment)
        val output = CapturingProcessHandler(process, commandLine.charset, presentation)
            .runProcess(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS).toInt(), true)
        if (output.isTimeout) {
            throw ExecutionException(BasedPythonBundle.message("target.error.flagsTimeout", presentation, TIMEOUT_SECONDS))
        }
        return read(output)
    }

    /**
     * What `by command-line-flags` answered: its table when it ran, and nothing from a `by` that refused
     * the command.
     */
    fun read(output: ProcessOutput): ByCommandFlags =
        if (output.exitCode == 0 && !output.isTimeout && !output.isCancelled) ByCommandFlags.ofPrinted(output.stdout)
        else ByCommandFlags.NONE
}
