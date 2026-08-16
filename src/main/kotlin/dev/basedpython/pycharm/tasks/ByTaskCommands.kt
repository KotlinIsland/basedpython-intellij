package dev.basedpython.pycharm.tasks

/**
 * What each runner is asked on the command line to run one node of the tree.
 *
 * Pure, and the place every launch goes through — the tool window, the run configuration and the
 * settings editor's preview all read the command from here, so what the view says will run is what
 * runs.
 *
 * A null return means "this node names nothing the runner can be pointed at": a pre-commit repo, a
 * lefthook file as a whole, the `pyproject.toml` itself. Those are grouping rows, and the view
 * disables Run on them rather than quietly running something adjacent.
 */
internal object ByTaskCommands {

    /** The arguments for [node], after the executable. */
    fun arguments(node: ByTaskNode, allFiles: Boolean): List<String>? =
        arguments(node.runner, node.kind, node.id, node.stage, allFiles)

    fun arguments(
        runner: ByTaskRunner,
        kind: ByTaskKind,
        id: String?,
        stage: String?,
        allFiles: Boolean,
    ): List<String>? = when (runner) {
        ByTaskRunner.PRE_COMMIT -> preCommit(kind, id, stage, allFiles, stageFlag = "--hook-stage")
        // prek is a drop-in for pre-commit's config, not for its flags: the stage filter is spelled
        // `--stage` here, and passing `--hook-stage` to it fails outright.
        ByTaskRunner.PREK -> preCommit(kind, id, stage, allFiles, stageFlag = "--stage")
        ByTaskRunner.LEFTHOOK -> lefthook(kind, id, stage, allFiles)
        ByTaskRunner.PYPROJECTX -> pyprojectx(kind, id)
    }

    private fun preCommit(
        kind: ByTaskKind,
        id: String?,
        stage: String?,
        allFiles: Boolean,
        stageFlag: String,
    ): List<String>? = when (kind) {
        // `run` with no hook id is every hook, which is what the file node means.
        ByTaskKind.FILE -> buildList {
            add("run")
            if (allFiles) add("--all-files")
        }
        ByTaskKind.HOOK -> buildList {
            add("run")
            add(id ?: return null)
            // A hook that does not run at `pre-commit` is skipped by the default stage — silently,
            // and with a zero exit code, which reads as "it passed". See [PreCommitTasks.stageOf].
            if (stage != null) {
                add(stageFlag)
                add(stage)
            }
            if (allFiles) add("--all-files")
        }
        // A repo is where a hook came from, not something either CLI can be pointed at.
        else -> null
    }

    private fun lefthook(kind: ByTaskKind, id: String?, stage: String?, allFiles: Boolean): List<String>? {
        val hook = stage ?: return null
        val selector = when (kind) {
            ByTaskKind.SECTION -> emptyList()
            ByTaskKind.COMMAND -> listOf("--commands", id ?: return null)
            ByTaskKind.JOB -> listOf("--jobs", id ?: return null)
            // `lefthook run` filters on commands and on jobs; scripts have never had a flag of
            // their own, so the closest thing to running one is running the hook it belongs to.
            ByTaskKind.SCRIPT -> emptyList()
            else -> return null
        }
        return buildList {
            add("run")
            add(hook)
            addAll(selector)
            if (allFiles) add("--all-files")
            // The IDE's console is not a terminal, and lefthook's spinner in one is a stream of
            // cursor escapes rather than progress.
            add("--no-tty")
        }
    }

    /** `./pw <alias>` — the wrapper takes the alias as its first argument and nothing else. */
    private fun pyprojectx(kind: ByTaskKind, id: String?): List<String>? =
        if (kind == ByTaskKind.ALIAS) listOf(id ?: return null) else null

    /**
     * True when running [node] on all files is a thing its runner can be asked for.
     *
     * pyprojectx has no notion of a file list — an alias is a command line, and which files it
     * touches is the command's business — so the toggle is left out of its rows rather than
     * silently ignored.
     */
    fun supportsAllFiles(runner: ByTaskRunner): Boolean = runner != ByTaskRunner.PYPROJECTX

    /** The whole command as a line of text, for tooltips and the run configuration's editor. */
    fun describe(executable: String, arguments: List<String>): String =
        (listOf(executable) + arguments).joinToString(" ")
}
