package dev.basedpython.pycharm.tasks

/**
 * The `[tool.pyprojectx.aliases]` of a `pyproject.toml`, as tasks.
 *
 * Aliases only, deliberately. `[tool.pyprojectx]` also holds tool and context definitions —
 * `uv = "uv==0.5"`, `main = ["ruff", "pytest"]` — and `./pw uv --version` does run one of those, but
 * a tool on its own is a program with no arguments and no job to do, while an alias is exactly the
 * "here is a thing this project does" that this view is for. The same reason `npm` lists `scripts`
 * and not `dependencies`.
 *
 * Two spellings reach the same place: a key in the aliases table, and a table of its own for an
 * alias that needs more than a command line
 * (`[tool.pyprojectx.aliases.test]` with `cmd`, `cwd`, `ctx`).
 */
internal object PyprojectxTasks {

    /** The file's aliases, or null when it is not a pyprojectx project or declares none. */
    fun parse(text: String, path: String): ByTaskNode? {
        val sections = ByToml.parse(text)
        if (!ByToml.hasTable(sections, TOOL, PYPROJECTX)) return null

        val inline = ByToml.table(sections, TOOL, PYPROJECTX, ALIASES).map { entry ->
            node(entry.key, command(entry), entry.line, path)
        }
        // `[tool.pyprojectx.aliases.<name>]` — one table per alias, for the ones with options.
        val tabled = sections
            .filter { it.path.size == 4 && it.path.take(3) == listOf(TOOL, PYPROJECTX, ALIASES) }
            .map { section ->
                val cmd = section.entries.firstOrNull { it.key == CMD }
                node(section.path.last(), cmd?.let(::command), section.line, path)
            }

        val aliases = (inline + tabled).distinctBy { it.name }.sortedBy { it.name }
        if (aliases.isEmpty()) return null
        return ByTaskNode(
            name = path.substringAfterLast('/'),
            kind = ByTaskKind.FILE,
            runner = ByTaskRunner.PYPROJECTX,
            path = path,
            children = aliases,
        )
    }

    private fun node(name: String, detail: String?, line: Int, path: String) = ByTaskNode(
        name = name,
        kind = ByTaskKind.ALIAS,
        runner = ByTaskRunner.PYPROJECTX,
        path = path,
        id = name,
        line = line,
        detail = detail,
    )

    /**
     * What an alias runs, as one line of grey text.
     *
     * An alias is a string, a list of commands run in order, or a table with a `cmd`. The list form
     * is joined with the `&&` it behaves like, so a row reads the way the same thing would be typed
     * into a shell.
     */
    private fun command(entry: ByTomlEntry): String? {
        entry.inline(CMD)?.let { return it.oneLine() }
        entry.string()?.let { return it.oneLine() }
        return entry.strings().takeIf { it.isNotEmpty() }?.joinToString(" && ")?.oneLine()
    }

    /** The first non-blank line: a `cmd` written as a triple-quoted block is a script, not a label. */
    private fun String.oneLine(): String? =
        lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() }

    private const val TOOL = "tool"
    private const val PYPROJECTX = "pyprojectx"
    private const val ALIASES = "aliases"
    private const val CMD = "cmd"
}
