package dev.basedpython.pycharm.console

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.util.execution.ParametersListUtil
import dev.basedpython.pycharm.env.ByLaunch
import java.nio.file.Path

/**
 * Pure (process-free) construction of the [GeneralCommandLine] used to launch an
 * interactive basedpython REPL.
 *
 * The `by` CLI may or may not ship a dedicated `repl` subcommand. To stay robust
 * across CLI versions the subcommand is configurable (default [DEFAULT_SUBCOMMAND]
 * = `"repl"`); a caller can fall back to [FALLBACK_SUBCOMMAND] (`"run"`) — or to no
 * subcommand at all — without touching any process API.
 *
 * Everything here is deterministic and side-effect-free so it can be unit-tested
 * without ever spawning a process.
 */
internal object ByReplCommandLine {

    /** Preferred subcommand: a dedicated interactive REPL. */
    const val DEFAULT_SUBCOMMAND: String = "repl"

    /** Graceful fallback when `repl` is unavailable: `by run` still gives a session. */
    const val FALLBACK_SUBCOMMAND: String = "run"

    /**
     * Build the command line.
     *
     * @param launch     the resolved `by` launch — executable, argument prefix, and environment.
     * @param subcommand the subcommand to run; blank means "no subcommand" (bare `by`).
     * @param extraArgs  raw extra-args string (e.g. [BasedPythonSettings.byExtraArgs]);
     *                   split honoring quotes via [ParametersListUtil].
     * @param workDir    working directory for the process, or null to inherit.
     */
    fun build(
        launch: ByLaunch,
        subcommand: String = DEFAULT_SUBCOMMAND,
        extraArgs: String = "",
        workDir: Path? = null,
    ): GeneralCommandLine {
        val cmd = GeneralCommandLine()
            .withExePath(launch.exe.toString())
            .withCharset(Charsets.UTF_8)
            .withEnvironment(launch.env)
        // The launch prefix must precede the subcommand: for a uv launch the executable is `uv` and
        // the prefix is what names `by`, so `repl` ahead of it would run a uv subcommand instead.
        cmd.addParameters(launch.prependArgs)
        cmd.addParameters(parameters(subcommand, extraArgs))
        if (workDir != null) cmd.withWorkDirectory(workDir.toFile())
        return cmd
    }

    /**
     * Compute the ordered parameter list (subcommand first, then split extra args).
     * Exposed separately so tests can assert on the exact argument vector.
     */
    fun parameters(subcommand: String, extraArgs: String): List<String> {
        val args = ArrayList<String>()
        if (subcommand.isNotBlank()) args.add(subcommand.trim())
        if (extraArgs.isNotBlank()) args.addAll(ParametersListUtil.parse(extraArgs))
        return args
    }

    /**
     * A short human-readable command preview (e.g. `by repl --verbose`) for printing
     * to the console banner. Does not quote — display only.
     */
    fun preview(subcommand: String, extraArgs: String): String =
        (listOf("by") + parameters(subcommand, extraArgs)).joinToString(" ")
}
