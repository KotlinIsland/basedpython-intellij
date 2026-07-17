package dev.basedpython.pycharm.run

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

/** Right-click a `.by` file → produce a `by check <path>` configuration. */
class ByCheckFromFileProducer : LazyRunConfigurationProducer<ByCheckConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        BasedPythonRunConfigurationType.getInstance().checkFactory

    /**
     * Only look at `by check` configurations.
     *
     * The base implementation scans `runManager.getConfigurationSettingsList(factory.type)` — that
     * is, it filters by configuration *type*, not by factory. `by run`, `by build` and `by check`
     * share one type, so it hands this producer a [ByRunConfiguration] or [ByBuildConfiguration],
     * and the compiler-generated bridge for [isConfigurationFromContext] casts it to
     * [ByCheckConfiguration] — a ClassCastException out of findExistingConfiguration on any context
     * run, as soon as one `by run` configuration has been saved.
     */
    override fun getConfigurationSettingsList(runManager: RunManager): List<RunnerAndConfigurationSettings> =
        super.getConfigurationSettingsList(runManager).filter { it.configuration is ByCheckConfiguration }

    override fun setupConfigurationFromContext(
        configuration: ByCheckConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.location?.virtualFile ?: contextFile(context) ?: return false
        if (file.extension != "by") return false
        val base = context.project.basePath
        val rel = if (!base.isNullOrBlank()) {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(base)
                ?.let { com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, it, '/') }
                ?: file.path
        } else file.path
        configuration.options.paths = rel
        configuration.name = "by check $rel"
        if (!base.isNullOrBlank() && configuration.options.workingDir.isBlank()) {
            configuration.options.workingDir = base
        }
        return true
    }

    override fun isConfigurationFromContext(
        configuration: ByCheckConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val file = context.location?.virtualFile ?: contextFile(context) ?: return false
        if (file.extension != "by") return false
        val base = context.project.basePath
        val rel = if (!base.isNullOrBlank()) {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(base)
                ?.let { com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, it, '/') }
                ?: file.path
        } else file.path
        return configuration.options.paths == rel
    }

    private fun contextFile(context: ConfigurationContext): VirtualFile? =
        context.psiLocation?.containingFile?.virtualFile
}
