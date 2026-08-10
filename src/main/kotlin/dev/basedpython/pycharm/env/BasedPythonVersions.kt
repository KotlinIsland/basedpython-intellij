package dev.basedpython.pycharm.env

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.lsp.BasedPythonBinaries

/**
 * Reusable helper that reports the versions of the resolved `by` / `buff` binaries.
 *
 * Each function resolves the binary via [BasedPythonBinaries], runs `<binary> version`
 * with a 5s timeout, and returns the trimmed first line of stdout (or `null` on any
 * failure: binary not found, non-zero exit, timeout, empty output).
 *
 * Callers should invoke these off the EDT (they block on a subprocess).
 */
object BasedPythonVersions {
    private val LOG = Logger.getInstance(BasedPythonVersions::class.java)
    private const val TIMEOUT_MS = 5_000

    fun byVersion(project: Project): String? =
        version(BasedPythonBinaries.launchBy(project))

    fun buffVersion(project: Project): String? =
        version(BasedPythonBinaries.launchBuff(project))

    private fun version(launch: ByLaunch?): String? {
        if (launch == null) return null
        return try {
            val cmd = GeneralCommandLine()
                .withExePath(launch.exe.toString())
                .withParameters(launch.prependArgs)
                .withParameters("version")
                .withCharset(Charsets.UTF_8)
                .withEnvironment(launch.env)
            val output = ExecUtil.execAndGetOutput(cmd, TIMEOUT_MS)
            if (output.isTimeout || output.exitCode != 0) return null
            output.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            LOG.warn("Failed to read version from ${launch.describe()}", e)
            null
        }
    }
}
