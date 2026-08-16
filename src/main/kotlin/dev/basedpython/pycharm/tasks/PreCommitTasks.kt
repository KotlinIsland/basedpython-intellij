package dev.basedpython.pycharm.tasks

/**
 * `.pre-commit-config.yaml` as the tree the view shows: the file, its repos, and their hooks.
 *
 * Repos are a grouping and nothing more — pre-commit has no way to run one, which is why they carry
 * no id — but they are how the file is written and how a hook is recognised ("black, the one from
 * psf/black"), so flattening them away would cost more than it saves.
 *
 * The one thing read here beyond the names is `stages`. A hook that only runs at `pre-push` is
 * skipped by a plain `pre-commit run <id>`, silently and with a zero exit code, because that
 * command defaults to the `pre-commit` stage — so the stage a hook declares has to travel with it
 * as far as the command line. See [ByTaskCommands].
 */
internal object PreCommitTasks {

    /** The file's tasks, or null when it declares no repos at all. */
    fun parse(text: String, path: String): ByTaskNode? {
        val document = ByYaml.parse(text)
        val repos = document.value("repos").items().mapNotNull { it.asMapping() }
        if (repos.isEmpty()) return null

        // What a hook runs at when it says nothing itself. Absent, that is "every stage", which is
        // exactly the case [stageOf] reads as "the plain command already reaches it".
        val defaultStages = document.value("default_stages").strings()

        val sections = repos.map { repo ->
            val source = repo.value("repo").text() ?: LOCAL
            val hooks = repo.value("hooks").items().mapNotNull { it.asMapping() }.mapNotNull { hook ->
                val id = hook.value("id").text() ?: return@mapNotNull null
                ByTaskNode(
                    name = id,
                    kind = ByTaskKind.HOOK,
                    runner = ByTaskRunner.PRE_COMMIT,
                    path = path,
                    id = id,
                    stage = stageOf(hook.value("stages").strings().ifEmpty { defaultStages }),
                    line = hook.entry("id")?.line ?: repo.line,
                    detail = hook.value("name").text() ?: hook.value("entry").text(),
                )
            }
            ByTaskNode(
                name = repoName(source),
                kind = ByTaskKind.SECTION,
                runner = ByTaskRunner.PRE_COMMIT,
                path = path,
                line = repo.entry("repo")?.line ?: repo.line,
                detail = repo.value("rev").text(),
                children = hooks,
            )
        }.filter { it.children.isNotEmpty() }

        if (sections.isEmpty()) return null
        return ByTaskNode(
            name = path.substringAfterLast('/'),
            kind = ByTaskKind.FILE,
            runner = ByTaskRunner.PRE_COMMIT,
            path = path,
            children = sections,
        )
    }

    /**
     * The stage a hook has to be asked for by name, or null when the default run reaches it.
     *
     * Only a hook that cannot run at `pre-commit` needs one: passing `--hook-stage` for every hook
     * would be noise on the command line, and would be wrong for a hook declaring several stages —
     * pre-commit takes one stage, so naming the first of them would *narrow* a hook that already
     * runs where the command is pointed.
     */
    fun stageOf(stages: List<String>): String? {
        if (stages.isEmpty()) return null
        val normalized = stages.map(::normalizeStage)
        if (DEFAULT_STAGE in normalized) return null
        return normalized.first()
    }

    /**
     * A stage under the name pre-commit's CLI knows it by.
     *
     * `commit`, `push` and `merge-commit` are the pre-2.20 spellings. Configuration files still
     * carry them (pre-commit reads them and warns), and `--hook-stage commit` is refused by any
     * recent version, so the old name has to be translated rather than passed through.
     */
    fun normalizeStage(stage: String): String = LEGACY_STAGES[stage] ?: stage

    /**
     * A repo's URL as a row label: `https://github.com/psf/black` becomes `psf/black`.
     *
     * The host is dropped because it is the same for every row in almost every file, and the row
     * has to be scannable — `local` and `meta`, pre-commit's two non-URL sources, are left alone.
     *
     * Split on `:` as well as `/` so the scp-style form git itself accepts,
     * `git@github.com:psf/black.git`, comes out as the same two words the https form does. Without
     * that the whole `git@host:owner` reads as one segment and the label keeps a hostname.
     */
    fun repoName(source: String): String {
        if (source == LOCAL || source == META) return source
        val withoutScheme = source.substringAfter("://").removeSuffix("/")
        val withoutSuffix = withoutScheme.removeSuffix(".git")
        val segments = withoutSuffix.split('/', ':').filter { it.isNotEmpty() }
        if (segments.size < 2) return withoutSuffix
        // Drop the host, keep owner and repository — the part that names the tool.
        return segments.takeLast(2).joinToString("/")
    }

    private const val LOCAL = "local"
    private const val META = "meta"
    private const val DEFAULT_STAGE = "pre-commit"

    private val LEGACY_STAGES = mapOf(
        "commit" to "pre-commit",
        "push" to "pre-push",
        "merge-commit" to "pre-merge-commit",
        "rebase" to "pre-rebase",
    )
}
