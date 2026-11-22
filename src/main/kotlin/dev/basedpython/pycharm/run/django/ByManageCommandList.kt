package dev.basedpython.pycharm.run.django

import com.intellij.openapi.util.NlsSafe
import dev.basedpython.pycharm.lsp.ext.ByDjangoProject
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.jetbrains.annotations.Nls

/** The rows of the Commands view's list: lines run lately, then commands, the project's own before django's. */
internal object ByManageCommandList {

    sealed interface Item {
        /** The group it is listed in. */
        val group: @Nls String

        data class Recent(@param:NlsSafe val line: String, override val group: @Nls String) : Item
        data class Command(
            @param:NlsSafe val name: String,
            @param:NlsSafe val app: String,
            @param:NlsSafe val help: String,
            val starred: Boolean,
            override val group: @Nls String,
        ) : Item
    }

    /** What to list when [typed] is the command line: everything while it is empty. */
    fun items(projects: List<ByDjangoProject>, lines: List<String>, starred: Set<String>, typed: String): List<Item> {
        val local = projects.flatMap { it.apps }.filterNot { it.external }.mapNotNull { it.label }.toSet()
        val word = typed.trimStart().substringBefore(' ')
        val whole = typed.trim()
        // once the name is typed whole, its arguments are what is being written
        val naming = ' ' !in typed.trimStart()
        val recent = lines.filter { if (naming) it.contains(word, ignoreCase = true) else it.startsWith(whole) && it != whole }
            .take(RECENT).map { Item.Recent(it, BasedPythonBundle.message("manage.commands.recent")) }
        val commands = projects.flatMap { it.commands }.filter { it.name != null }.distinctBy { it.name }
            .filter { if (naming) it.name!!.contains(word, ignoreCase = true) else it.name == word }
            .map { command ->
                val name = command.name!!
                val group = when {
                    name in starred -> "manage.commands.starred"
                    command.app in local -> "manage.commands.project"
                    command.app == DJANGO -> "manage.commands.django"
                    else -> "manage.commands.installed"
                }
                group to Item.Command(name, command.app.orEmpty(), command.help.orEmpty().lineSequence().first().trim(), name in starred, BasedPythonBundle.message(group))
            }
            .sortedWith(compareBy({ ORDER.indexOf(it.first) }, { !it.second.name.startsWith(word, ignoreCase = true) }, { it.second.name }))
            .map { it.second }
        return recent + commands
    }

    private const val DJANGO = "django"
    private const val RECENT = 5
    private val ORDER = listOf("manage.commands.starred", "manage.commands.project", "manage.commands.installed", "manage.commands.django")
}
