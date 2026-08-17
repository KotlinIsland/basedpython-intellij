package dev.basedpython.pycharm.run

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources
import dev.basedpython.pycharm.run.main.ByMainArgumentHistory

/** Right-click a basedpython source file → produce a `by run <module>` configuration. */
class ByRunFromFileProducer : LazyRunConfigurationProducer<ByRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        BasedPythonRunConfigurationType.getInstance().runFactory

    /**
     * Only look at `by run` configurations — see the same override in [ByCheckFromFileProducer].
     * The base implementation filters by configuration *type*, which `by run`, `by build` and
     * `by check` share, so without this a saved `by check` would be cast to [ByRunConfiguration].
     */
    override fun getConfigurationSettingsList(runManager: RunManager): List<RunnerAndConfigurationSettings> =
        super.getConfigurationSettingsList(runManager).filter { it.configuration is ByRunConfiguration }

    override fun setupConfigurationFromContext(
        configuration: ByRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.location?.virtualFile ?: contextFile(context) ?: return false
        if (!BasedPythonSources.isOwnedSource(file)) return false
        val module = moduleNameFor(context, file) ?: return false
        configuration.options.module = module
        // Named for what is being run, not for the command that runs it — the configuration
        // type and its icon already say it is basedpython.
        configuration.name = module
        // A `main` with parameters has to be asked about once; after that the answer belongs to
        // every later run of the same program, including the plain green one in the gutter.
        if (configuration.options.programArgs.isBlank()) {
            ByMainArgumentHistory.last(context.project, module)?.let {
                configuration.options.programArgs = it
            }
        }
        val base = context.project.basePath
        if (!base.isNullOrBlank() && configuration.options.workingDir.isBlank()) {
            configuration.options.workingDir = base
        }
        return true
    }

    override fun isConfigurationFromContext(
        configuration: ByRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val file = context.location?.virtualFile ?: contextFile(context) ?: return false
        if (!BasedPythonSources.isOwnedSource(file)) return false
        val module = moduleNameFor(context, file) ?: return false
        return configuration.options.module == module
    }

    /**
     * Running a file is the natural context action; checking it is not. Both producers match every
     * `.by` file, and with nothing arbitrating between them the platform offers a chooser on every
     * context run — one that is unreadable here, because it labels entries by configuration *type*
     * and `by run` / `by check` are two factories of the same type, so both render as "basedpython".
     *
     * Precedence is therefore pytest > `by run` > `by check`; see [ByTestFromFileProducer],
     * which claims the same relationship over this producer.
     */
    override fun isPreferredConfiguration(self: ConfigurationFromContext?, other: ConfigurationFromContext?): Boolean =
        other?.configuration is ByCheckConfiguration

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean =
        other.configuration is ByCheckConfiguration

    private fun contextFile(context: ConfigurationContext): VirtualFile? =
        context.psiLocation?.containingFile?.virtualFile
}

internal fun moduleNameFor(context: ConfigurationContext, file: VirtualFile): String? =
    moduleNameFor(context.project, file)

/**
 * The module name `by run` would be given for [file], or null when it sits under no root — or when
 * a sibling would win the name.
 *
 * Both `.by` and `.py` are modules `by run` can start, and `main.by` beside `main.py` is one module
 * name for two files: `by run` transpiles the `.by` into its temp directory and makes that directory
 * `sys.path[0]`, so the generated module shadows the plain one. Offering to run the shadowed file
 * would be offering to run the other one, so the `.py` is declined and the `.by` keeps the name.
 */
internal fun moduleNameFor(project: Project, file: VirtualFile): String? {
    if (isShadowedByGeneratedModule(file)) return null
    val index = ProjectFileIndex.getInstance(project)
    val root = index.getSourceRootForFile(file)
        ?: index.getContentRootForFile(file)
        ?: ModuleUtilCore.findModuleForFile(file, project)?.let { m ->
            // fallback: module root via ModuleRootManager
            com.intellij.openapi.roots.ModuleRootManager.getInstance(m).contentRoots.firstOrNull()
        }
        ?: project.basePath?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it) }
        ?: return null
    val rel = VfsUtilCore.getRelativePath(file, root, '/') ?: return null
    val noExt = BasedPythonSources.withoutModuleExtension(rel) ?: return null
    if (noExt.isBlank()) return null
    return noExt.replace('/', '.')
}

/** True when [file] is a `.py` with a `.by` of the same module name beside it. */
private fun isShadowedByGeneratedModule(file: VirtualFile): Boolean =
    file.extension.equals(BasedPythonSources.PY, ignoreCase = true) &&
        file.parent?.findChild("${file.nameWithoutExtension}.${BasedPythonSources.BY}") != null
