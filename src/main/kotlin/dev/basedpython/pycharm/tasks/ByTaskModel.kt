package dev.basedpython.pycharm.tasks

/**
 * A task runner whose configuration this plugin reads.
 *
 * [id] is persisted in run configurations and must not change. [binary] is the executable name
 * [ByTaskLaunch] resolves — the same name the tool is installed under, which is what makes a
 * project's `.venv` the first place any of them is looked for.
 */
internal enum class ByTaskRunner(val id: String, val display: String, val binary: String) {
    /** The original, in Python: `pre-commit run <hook>`. */
    PRE_COMMIT("pre-commit", "pre-commit", "pre-commit"),

    /**
     * A drop-in reimplementation of pre-commit in Rust, reading the very same
     * `.pre-commit-config.yaml`.
     *
     * A separate runner rather than a flag because the two disagree on one thing that matters here:
     * pre-commit spells the stage filter `--hook-stage`, prek spells it `--stage`. Which of them a
     * config file is offered under is decided by which is installed — see [ByTaskRunners.preferred].
     */
    PREK("prek", "prek", "prek"),

    /** `lefthook run <hook> --commands <name>`. */
    LEFTHOOK("lefthook", "lefthook", "lefthook"),

    /**
     * pyprojectx, whose aliases are run through the `pw` wrapper checked into the project.
     *
     * [binary] is that wrapper's name rather than a tool on `PATH`: the whole point of pyprojectx is
     * that a clone needs nothing installed, so `./pw lint` is the invocation and the wrapper
     * bootstraps the rest.
     */
    PYPROJECTX("pyprojectx", "pyprojectx", "pw"),
    ;

    companion object {
        /** Unknown and blank ids degrade to [PRE_COMMIT], the way a stale run configuration would. */
        fun fromId(id: String?): ByTaskRunner = entries.firstOrNull { it.id == id } ?: PRE_COMMIT
    }
}

/** What a node of the task tree stands for, which decides its icon and how it is run. */
internal enum class ByTaskKind {
    /** A configuration file. Runnable when its runner can run everything the file declares. */
    FILE,

    /**
     * A grouping row: a repo in `.pre-commit-config.yaml`, a git hook in `lefthook.yml`, the
     * aliases table of a `pyproject.toml`. Runnable only where the runner has a name for it — a
     * lefthook stage is `lefthook run pre-commit`, while a pre-commit repo is not addressable at all.
     */
    SECTION,

    /** One `- id:` under a pre-commit repo. */
    HOOK,

    /** One key under a lefthook `commands:`. */
    COMMAND,

    /** One key under a lefthook `scripts:`. */
    SCRIPT,

    /** One entry of a lefthook `jobs:` list, including one nested in a group. */
    JOB,

    /** One key of `[tool.pyprojectx.aliases]`. */
    ALIAS,
    ;

    /** True for the kinds that stand for a single task rather than for a file or a grouping. */
    val isTask: Boolean get() = this != FILE && this != SECTION
}

/**
 * What the last run said about a task.
 *
 * Ordered worst-first deliberately: [worst] folds a group's children by taking the minimum, so one
 * failing hook is what its file shows. [NOT_RUN] sits ahead of [PASSED] for the same reason it does
 * in the test view — green on a collapsed row has to mean everything under it passed, and one hook
 * out of eight says nothing about the other seven.
 */
internal enum class ByTaskState {
    /** The run ended with a non-zero exit code. */
    FAILED,

    /** Started and has not finished yet. */
    RUNNING,

    /** Never run, or run before this IDE session — the state every task starts in. */
    NOT_RUN,

    /** The run ended with exit code 0. */
    PASSED,
    ;

    companion object {
        /** The state a group shows for [children]: the worst any of them is in. */
        fun worst(children: Iterable<ByTaskState>): ByTaskState = children.minOrNull() ?: NOT_RUN
    }
}

/**
 * A node of the task tree: a configuration file, a group inside it, or a task.
 *
 * @param name what the user reads
 * @param path the configuration file this came from, relative to the project base — both the thing
 *   *Jump to Source* opens and half of what makes [key] unique
 * @param id what the runner's CLI is given to select this node; null when the node is not something
 *   the CLI can name (a file, a pre-commit repo)
 * @param stage the git hook this belongs to: a lefthook stage, or the stage a pre-commit hook
 *   declared it runs at. Part of the command for both, in different spellings.
 * @param line 0-based line in [path] where this is declared
 * @param detail a grey suffix — the command a lefthook task runs, a pre-commit repo's revision
 */
internal data class ByTaskNode(
    val name: String,
    val kind: ByTaskKind,
    val runner: ByTaskRunner,
    val path: String,
    val id: String? = null,
    val stage: String? = null,
    val line: Int = 0,
    val detail: String? = null,
    val children: List<ByTaskNode> = emptyList(),
) {
    /**
     * A stable name for this node, for keying what the last run said about it.
     *
     * Built from what the node *is* rather than from its identity, because every refresh rebuilds
     * the tree from the file: a hook that is still declared where it was is the same hook, and its
     * verdict should survive the file being saved.
     */
    val key: String
        get() = taskKey(runner, path, kind, id, stage)

    /** How many runnable tasks are at or under this node. */
    val taskCount: Int
        get() = if (children.isEmpty()) (if (kind.isTask) 1 else 0) else children.sumOf { it.taskCount }

    /** This node and everything under it, moved to [runner]. See [ByTaskRunners.preferred]. */
    fun withRunner(runner: ByTaskRunner): ByTaskNode =
        copy(runner = runner, children = children.map { it.withRunner(runner) })
}

/**
 * The name a task's verdict is stored under.
 *
 * A free function rather than only a property of [ByTaskNode] because both ends need it and only
 * one of them has a node: the tree keys what it draws, while a run configuration — which persists
 * these five fields and may outlive the tree that made it — keys what it reports.
 */
internal fun taskKey(
    runner: ByTaskRunner,
    path: String,
    kind: ByTaskKind,
    id: String?,
    stage: String?,
): String = listOf(runner.id, path, kind.name, id.orEmpty(), stage.orEmpty()).joinToString("|")

/** Which of the two interchangeable pre-commit runners a config file is offered under. */
internal object ByTaskRunners {

    /**
     * The runner to read `.pre-commit-config.yaml` with, given which binaries a project has.
     *
     * pre-commit wins when both are installed: it is the tool the config file was written for, and
     * a project that has it installed asked for it. prek is used when it is the only one there —
     * which is the case worth handling, since a prek-only project would otherwise be told to
     * install a tool it deliberately replaced. With neither installed the file is still listed under
     * pre-commit, so the tasks are visible and running one says which binary is missing.
     */
    fun preferred(preCommitFound: Boolean, prekFound: Boolean): ByTaskRunner = when {
        preCommitFound -> ByTaskRunner.PRE_COMMIT
        prekFound -> ByTaskRunner.PREK
        else -> ByTaskRunner.PRE_COMMIT
    }
}
