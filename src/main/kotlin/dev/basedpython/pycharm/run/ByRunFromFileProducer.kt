package dev.basedpython.pycharm.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

/** Right-click a `.by` file → produce a `by run <module>` configuration. */
class ByRunFromFileProducer : LazyRunConfigurationProducer<ByRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        BasedPythonRunConfigurationType.getInstance().runFactory

    override fun setupConfigurationFromContext(
        configuration: ByRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.location?.virtualFile ?: contextFile(context) ?: return false
        if (file.extension != "by") return false
        val module = moduleNameFor(context, file) ?: return false
        configuration.options.module = module
        configuration.name = "by run $module"
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
        if (file.extension != "by") return false
        val module = moduleNameFor(context, file) ?: return false
        return configuration.options.module == module
    }

    private fun contextFile(context: ConfigurationContext): VirtualFile? =
        context.psiLocation?.containingFile?.virtualFile
}

internal fun moduleNameFor(context: ConfigurationContext, file: VirtualFile): String? {
    val project = context.project
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
    val noExt = rel.removeSuffix(".by")
    if (noExt.isBlank()) return null
    return noExt.replace('/', '.')
}
