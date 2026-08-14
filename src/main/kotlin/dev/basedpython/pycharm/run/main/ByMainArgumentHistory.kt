package dev.basedpython.pycharm.run.main

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/**
 * The arguments a module was last run with.
 *
 * This is what keeps the prompt to once per program rather than once per run: a context
 * configuration created by the gutter is seeded from here (see
 * [dev.basedpython.pycharm.run.ByRunFromFileProducer]), so the second click on the run icon carries
 * the arguments the first one asked for, and plain Run keeps working the way plain Run should.
 *
 * Stored per project in [PropertiesComponent], the same place watch mode keeps its flag: a run
 * configuration's own arguments live in the configuration, and this is only the memory that fills a
 * new one in.
 */
internal object ByMainArgumentHistory {

    /** Most recent first. */
    fun recent(project: Project, module: String): List<String> =
        PropertiesComponent.getInstance(project).getList(key(module)).orEmpty()

    fun last(project: Project, module: String): String? = recent(project, module).firstOrNull()

    /** Records [arguments] as this module's most recent, keeping [LIMIT] distinct entries. */
    fun remember(project: Project, module: String, arguments: String) {
        val trimmed = arguments.trim()
        if (trimmed.isEmpty()) return
        val kept = (listOf(trimmed) + recent(project, module).filter { it != trimmed }).take(LIMIT)
        PropertiesComponent.getInstance(project).setList(key(module), kept)
    }

    private fun key(module: String) = "$PREFIX$module"

    private const val PREFIX = "dev.basedpython.pycharm.mainArguments."
    private const val LIMIT = 5
}
