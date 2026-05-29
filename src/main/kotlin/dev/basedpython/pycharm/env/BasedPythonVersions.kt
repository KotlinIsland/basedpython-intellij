package dev.basedpython.pycharm.env

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import java.nio.file.Path

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
        version(BasedPythonBinaries.resolveBy(project))

    fun buffVersion(project: Project): String? =
        version(BasedPythonBinaries.resolveBuff(project))

    private fun version(bin: Path?): String? {
        if (bin == null) return null
        return try {
            val cmd = GeneralCommandLine()
                .withExePath(bin.toString())
                .withParameters("version")
                .withCharset(Charsets.UTF_8)
            val output = ExecUtil.execAndGetOutput(cmd, TIMEOUT_MS)
            if (output.isTimeout || output.exitCode != 0) return null
            output.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            LOG.warn("Failed to read version from $bin", e)
            null
        }
    }
}
