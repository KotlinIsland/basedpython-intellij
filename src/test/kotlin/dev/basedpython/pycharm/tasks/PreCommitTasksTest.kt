package dev.basedpython.pycharm.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What a `.pre-commit-config.yaml` turns into. */
class PreCommitTasksTest {

    private val path = ".pre-commit-config.yaml"

    private val config = """
        # See https://pre-commit.com for more information
        default_stages: [pre-commit]
        repos:
          - repo: https://github.com/pre-commit/pre-commit-hooks
            rev: v4.5.0
            hooks:
              - id: trailing-whitespace
              - id: end-of-file-fixer
                name: Fix end of files
          - repo: https://github.com/astral-sh/ruff-pre-commit.git
            rev: v0.3.0
            hooks:
              - id: ruff
                args: [--fix]
              - id: ruff-format
                stages: [pre-push]
          - repo: local
            hooks:
              - id: by-check
                name: by check
                entry: by check
                language: system
                pass_filenames: false
    """.trimIndent()

    private fun parse() = checkNotNull(PreCommitTasks.parse(config, path))

    @Test
    fun `the file becomes one node per repo, named by owner and repository`() {
        val file = parse()

        assertEquals(path, file.name)
        assertEquals(ByTaskKind.FILE, file.kind)
        assertEquals(
            listOf("pre-commit/pre-commit-hooks", "astral-sh/ruff-pre-commit", "local"),
            file.children.map { it.name },
        )
        assertEquals(5, file.taskCount)
    }

    @Test
    fun `hooks are listed by id, with their human name beside them`() {
        val hooks = parse().children.flatMap { it.children }

        assertEquals(
            listOf("trailing-whitespace", "end-of-file-fixer", "ruff", "ruff-format", "by-check"),
            hooks.map { it.name },
        )
        assertEquals(listOf(ByTaskKind.HOOK), hooks.map { it.kind }.distinct())
        assertEquals("Fix end of files", hooks[1].detail)
        // No `name`, so the entry is what says what it does.
        assertEquals("by check", hooks[4].detail)
    }

    /**
     * The stage only travels with a hook that needs it. A plain `pre-commit run <id>` runs at the
     * `pre-commit` stage and silently skips anything declared elsewhere.
     */
    @Test
    fun `only a hook that cannot run at pre-commit carries a stage`() {
        val hooks = parse().children.flatMap { it.children }.associateBy { it.name }

        assertNull(hooks.getValue("ruff").stage)
        assertEquals("pre-push", hooks.getValue("ruff-format").stage)
    }

    @Test
    fun `a hook points at the line that declares it`() {
        val hooks = parse().children.flatMap { it.children }.associateBy { it.name }

        assertEquals(6, hooks.getValue("trailing-whitespace").line)
        assertEquals(18, hooks.getValue("by-check").line)
        assertEquals(3, parse().children.first().line)
    }

    @Test
    fun `every node knows which file it came from`() {
        val nodes = generateSequence(listOf(parse())) { level ->
            level.flatMap { it.children }.takeIf { it.isNotEmpty() }
        }.flatten()

        assertEquals(listOf(path), nodes.map { it.path }.distinct().toList())
    }

    @Test
    fun `a repo with no hooks is not a row, and a file with no repos is nothing at all`() {
        val emptyRepo = PreCommitTasks.parse("repos:\n  - repo: local\n    hooks: []\n", path)

        assertNull(emptyRepo)
        assertNull(PreCommitTasks.parse("", path))
        assertNull(PreCommitTasks.parse("default_stages: [pre-commit]\n", path))
    }

    @Test
    fun `the default stages apply to a hook that declares none`() {
        val file = checkNotNull(
            PreCommitTasks.parse(
                """
                default_stages: [pre-push]
                repos:
                  - repo: local
                    hooks:
                      - id: everywhere
                      - id: at-commit
                        stages: [pre-commit]
                """.trimIndent(),
                path,
            ),
        )
        val hooks = file.children.single().children.associateBy { it.name }

        assertEquals("pre-push", hooks.getValue("everywhere").stage)
        assertNull(hooks.getValue("at-commit").stage)
    }

    @Test
    fun `the pre-2_20 stage names are translated to the ones the CLI accepts`() {
        assertEquals("pre-commit", PreCommitTasks.normalizeStage("commit"))
        assertEquals("pre-push", PreCommitTasks.normalizeStage("push"))
        assertEquals("pre-merge-commit", PreCommitTasks.normalizeStage("merge-commit"))
        assertEquals("manual", PreCommitTasks.normalizeStage("manual"))
        // …including where it decides whether a stage is needed at all.
        assertNull(PreCommitTasks.stageOf(listOf("commit")))
        assertEquals("pre-push", PreCommitTasks.stageOf(listOf("push")))
        assertNull(PreCommitTasks.stageOf(emptyList()))
    }

    @Test
    fun `repo names keep what identifies them and drop what does not`() {
        assertEquals("psf/black", PreCommitTasks.repoName("https://github.com/psf/black"))
        assertEquals("psf/black", PreCommitTasks.repoName("https://github.com/psf/black.git"))
        assertEquals("psf/black", PreCommitTasks.repoName("git@github.com:psf/black.git"))
        assertEquals("local", PreCommitTasks.repoName("local"))
        assertEquals("meta", PreCommitTasks.repoName("meta"))
    }
}
