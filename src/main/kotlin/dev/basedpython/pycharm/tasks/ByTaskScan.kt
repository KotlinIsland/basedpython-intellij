package dev.basedpython.pycharm.tasks

/**
 * Which files hold tasks, and what they turn into.
 *
 * The whole discovery step is this: a fixed list of names at the project root, read and parsed. No
 * process is started to find out what a project can run — unlike the test view, which has to ask
 * pytest — so a scan costs a handful of file reads and can happen whenever one of those files is
 * saved.
 *
 * Deliberately the project root only. pre-commit, lefthook and pyprojectx are all repository-level
 * tools whose config sits beside the `.git` directory; prek supports nested projects in a monorepo,
 * which this does not follow, and a recursive walk for a fixed list of names is a price paid by
 * every project so that few can benefit.
 */
internal object ByTaskScan {

    /**
     * One known configuration file: its name, and what reads it.
     *
     * Order is the order the tool window lists them in — the hook managers first, since that is
     * what a repository's checks are, and `pyproject.toml` last because its aliases are a
     * convenience rather than something git enforces.
     */
    private val SOURCES: List<Pair<String, (String, String) -> ByTaskNode?>> = listOf(
        ".pre-commit-config.yaml" to PreCommitTasks::parse,
        // pre-commit itself only reads the `.yaml` spelling, but a `.yml` is written often enough
        // (and read by prek) that finding it and listing its hooks beats showing an empty window.
        ".pre-commit-config.yml" to PreCommitTasks::parse,
        "lefthook.yml" to LefthookTasks::parse,
        "lefthook.yaml" to LefthookTasks::parse,
        ".lefthook.yml" to LefthookTasks::parse,
        ".lefthook.yaml" to LefthookTasks::parse,
        // Personal overrides, git-ignored by convention. A file of its own in the view, because it
        // is a file of its own on disk and its commands are the ones that differ from the team's.
        "lefthook-local.yml" to LefthookTasks::parse,
        "lefthook-local.yaml" to LefthookTasks::parse,
        "pyproject.toml" to PyprojectxTasks::parse,
    )

    /** The file names looked for, in the order their tasks are shown. */
    val FILES: List<String> = SOURCES.map { it.first }

    /** True when a file of this name is one a scan would read. */
    fun isConfigFile(name: String): Boolean = name in FILES

    /**
     * The task tree for a project, given a way to read a file at the project root.
     *
     * [read] returns null for a file that is absent or unreadable, which are the same thing here: a
     * configuration that cannot be read has no tasks to offer, and the alternative — an error row
     * per missing file — would make an empty window out of every project that uses none of these
     * tools.
     */
    fun scan(read: (String) -> String?): List<ByTaskNode> =
        SOURCES.mapNotNull { (name, parse) -> read(name)?.let { parse(it, name) } }
}
