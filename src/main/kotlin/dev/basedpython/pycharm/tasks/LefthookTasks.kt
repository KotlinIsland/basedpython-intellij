package dev.basedpython.pycharm.tasks

/**
 * `lefthook.yml` as a tree: the git hooks it configures, and what each of them runs.
 *
 * Unlike pre-commit's, lefthook's groups *are* runnable — `lefthook run pre-commit` is the whole
 * hook — while the file as a whole is not, since lefthook has no "run everything" command. That
 * asymmetry is the reason a node carries what it can be selected by rather than a flag saying
 * whether it is runnable; see [ByTaskCommands].
 *
 * A hook is recognised by what it holds (`commands`, `scripts` or `jobs`) rather than by its name.
 * Lefthook's top level mixes hooks with settings — `colors`, `source_dir`, `remotes`, `extends`,
 * `min_version` and more — and the two are told apart reliably by shape, whereas a list of git hook
 * names would be a list to keep up to date and would still miss `p4-*` and `sendemail-validate`.
 */
internal object LefthookTasks {

    /** The file's tasks, or null when it configures no hook. */
    fun parse(text: String, path: String): ByTaskNode? {
        val document = ByYaml.parse(text)
        val sections = document.entries.mapNotNull { entry ->
            val body = entry.value.asMapping() ?: return@mapNotNull null
            val tasks = commands(body, entry.key, path) +
                scripts(body, entry.key, path) +
                jobs(body.value("jobs").items(), entry.key, path)
            if (tasks.isEmpty()) return@mapNotNull null
            ByTaskNode(
                name = entry.key,
                kind = ByTaskKind.SECTION,
                runner = ByTaskRunner.LEFTHOOK,
                path = path,
                id = entry.key,
                stage = entry.key,
                line = entry.line,
                children = tasks,
            )
        }
        if (sections.isEmpty()) return null
        return ByTaskNode(
            name = path.substringAfterLast('/'),
            kind = ByTaskKind.FILE,
            runner = ByTaskRunner.LEFTHOOK,
            path = path,
            children = sections,
        )
    }

    private fun commands(hook: ByYamlValue.Mapping, stage: String, path: String): List<ByTaskNode> =
        hook.value("commands").asMapping()?.entries.orEmpty().map { entry ->
            ByTaskNode(
                name = entry.key,
                kind = ByTaskKind.COMMAND,
                runner = ByTaskRunner.LEFTHOOK,
                path = path,
                id = entry.key,
                stage = stage,
                line = entry.line,
                detail = preview(entry.value.asMapping()?.value("run").text()),
            )
        }

    /**
     * The `scripts:` of a hook.
     *
     * Listed, but with nothing to select them by: `lefthook run` filters on `--commands` and
     * `--jobs`, and there has never been a `--scripts`. A script row therefore runs its whole hook,
     * which [ByTaskCommands] arranges by having nothing else to fall back to — and which the view
     * says out loud rather than leaving the user to notice that three other things also ran.
     */
    private fun scripts(hook: ByYamlValue.Mapping, stage: String, path: String): List<ByTaskNode> =
        hook.value("scripts").asMapping()?.entries.orEmpty().map { entry ->
            ByTaskNode(
                name = entry.key,
                kind = ByTaskKind.SCRIPT,
                runner = ByTaskRunner.LEFTHOOK,
                path = path,
                stage = stage,
                line = entry.line,
                detail = entry.value.asMapping()?.value("runner").text(),
            )
        }

    /**
     * The `jobs:` of a hook, groups included.
     *
     * A group is a job with jobs of its own, and both levels are addressable by name
     * (`--jobs lint`, `--jobs ruff`), so a group is a runnable node that also has children.
     */
    private fun jobs(items: List<ByYamlValue>, stage: String, path: String): List<ByTaskNode> =
        items.mapNotNull { it.asMapping() }.mapNotNull { job ->
            val name = job.value("name").text() ?: return@mapNotNull null
            val nested = job.value("group").asMapping()?.value("jobs").items()
            ByTaskNode(
                name = name,
                kind = ByTaskKind.JOB,
                runner = ByTaskRunner.LEFTHOOK,
                path = path,
                id = name,
                stage = stage,
                line = job.entry("name")?.line ?: job.line,
                detail = preview(job.value("run").text() ?: job.value("script").text()),
                children = jobs(nested, stage, path),
            )
        }

    /**
     * A `run:` as one line of grey text.
     *
     * Lefthook commands are frequently block scalars several lines long; the row has room for the
     * first of them, which is the one that says what the command is.
     */
    private fun preview(run: String?): String? =
        run?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() }
}
