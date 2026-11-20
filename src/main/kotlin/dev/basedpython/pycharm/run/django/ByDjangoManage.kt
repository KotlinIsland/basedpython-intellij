package dev.basedpython.pycharm.run.django

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import dev.basedpython.pycharm.lsp.ext.ByDjangoProject
import dev.basedpython.pycharm.lsp.refactor.ByWorkspaceEditFiles
import dev.basedpython.pycharm.navigation.django.ByDjangoStructureService
import dev.basedpython.pycharm.run.ByRunConfiguration
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `manage.py` a django project's commands run through, as a `by run` of it: `by run manage
 * <command> <args>` in the directory `manage.py` is in, which is how `by` runs a script beside the
 * project's modules, its `.by` ones transpiled.
 *
 * Which `manage.py` is `by`'s to say (`by/djangoStructure`'s [ByDjangoProject.manage]); until `by`
 * says, the one at the project's root, where `startproject` writes it.
 */
internal data class ByDjangoManage(val directory: Path, val module: String) {

    /** The program arguments of `manage.py`, as `by run` forwards them. */
    fun configure(configuration: ByRunConfiguration, arguments: List<String>) {
        val options = configuration.options
        options.module = module
        options.programArgs = ParametersListUtil.join(arguments)
        options.workingDir = directory.toString()
    }

    companion object {
        private const val MANAGE = "manage.py"

        /** The project's `manage.py`, or null when neither `by` nor the project's root has one. */
        fun find(project: Project, structure: ByDjangoProject? = null): ByDjangoManage? {
            val said = (structure ?: ByDjangoStructureService.getInstance(project).current()?.projects?.firstOrNull())
                ?.manage?.uri?.let(ByWorkspaceEditFiles::fileOf)?.toNioPath()
            val file = said ?: project.basePath?.let { Path.of(it, MANAGE) }?.takeIf(Files::isRegularFile) ?: return null
            return ByDjangoManage(file.parent, file.fileName.toString().removeSuffix(".py"))
        }

        /** A configuration of [factory] running `manage.py` with [arguments], named [name]; not added to the project. */
        fun configuration(project: Project, manage: ByDjangoManage, factory: ConfigurationFactory, name: String, arguments: List<String>): RunnerAndConfigurationSettings =
            RunManager.getInstance(project).createConfiguration(name, factory).also { settings ->
                manage.configure(settings.configuration as ByRunConfiguration, arguments)
            }
    }
}
