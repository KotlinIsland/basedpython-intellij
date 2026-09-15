package dev.basedpython.pycharm.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The YAML subset the hook configurations are read with.
 *
 * Every shape here is one a real `.pre-commit-config.yaml` or `lefthook.yml` is written in — the two
 * indentations a block sequence can take, a mapping that starts on the same line as its dash, flow
 * sequences of stage names, block scalars — plus the cases that would silently lose tasks if the
 * parser guessed: a `#` inside a command, a colon inside a value.
 */
class ByYamlTest {

    @Test
    fun `a flat mapping keeps its order and its lines`() {
        val document = ByYaml.parse(
            """
            min_version: 1.5.0
            colors: false
            """.trimIndent(),
        )

        assertEquals(listOf("min_version", "colors"), document.entries.map { it.key })
        assertEquals("1.5.0", document.value("min_version").text())
        assertEquals(0, document.entry("min_version")?.line)
        assertEquals(1, document.entry("colors")?.line)
    }

    @Test
    fun `a sequence indented under its key is read`() {
        val document = ByYaml.parse(
            """
            repos:
              - repo: local
              - repo: meta
            """.trimIndent(),
        )

        val repos = document.value("repos").items().mapNotNull { it.asMapping() }
        assertEquals(listOf("local", "meta"), repos.map { it.value("repo").text() })
    }

    /** The other half of real configs write the dashes at the key's own column, which is also YAML. */
    @Test
    fun `a sequence at the key's own indentation is read`() {
        val document = ByYaml.parse(
            """
            repos:
            - repo: local
            - repo: meta
            """.trimIndent(),
        )

        val repos = document.value("repos").items().mapNotNull { it.asMapping() }
        assertEquals(listOf("local", "meta"), repos.map { it.value("repo").text() })
    }

    @Test
    fun `a mapping that starts on the dash takes the lines under it`() {
        val document = ByYaml.parse(
            """
            hooks:
              - id: black
                name: Black
                stages: [pre-push, manual]
              - id: isort
            """.trimIndent(),
        )

        val hooks = document.value("hooks").items().mapNotNull { it.asMapping() }
        assertEquals(2, hooks.size)
        assertEquals("black", hooks[0].value("id").text())
        assertEquals("Black", hooks[0].value("name").text())
        assertEquals(listOf("pre-push", "manual"), hooks[0].value("stages").strings())
        assertEquals("isort", hooks[1].value("id").text())
        // The line of the hook, which is what Jump to Source opens.
        assertEquals(1, hooks[0].entry("id")?.line)
        assertEquals(4, hooks[1].entry("id")?.line)
    }

    @Test
    fun `nested mappings are three levels deep where lefthook needs them`() {
        val document = ByYaml.parse(
            """
            pre-commit:
              commands:
                lint:
                  run: ruff check
            """.trimIndent(),
        )

        val run = document.value("pre-commit").asMapping()
            ?.value("commands").asMapping()
            ?.value("lint").asMapping()
            ?.value("run").text()
        assertEquals("ruff check", run)
    }

    @Test
    fun `a block scalar keeps its lines and loses its indentation`() {
        val document = ByYaml.parse(
            """
            commands:
              format:
                run: |
                  ruff format .
                  git add -u
              lint:
                run: ruff check
            """.trimIndent(),
        )

        val commands = document.value("commands").asMapping()!!
        assertEquals("ruff format .\ngit add -u", commands.value("format").asMapping()?.value("run").text())
        // The block scalar must not swallow the sibling that follows it.
        assertEquals("ruff check", commands.value("lint").asMapping()?.value("run").text())
    }

    @Test
    fun `a comment is dropped, and a hash inside a value is not`() {
        val document = ByYaml.parse(
            """
            # the whole line is a comment
            rev: v1.2.3  # and so is this
            run: git log --format=%h#%d
            quoted: "a # b"
            """.trimIndent(),
        )

        assertEquals("v1.2.3", document.value("rev").text())
        assertEquals("git log --format=%h#%d", document.value("run").text())
        assertEquals("a # b", document.value("quoted").text())
    }

    @Test
    fun `a colon inside a value does not end the key`() {
        val document = ByYaml.parse(
            """
            repo: https://github.com/psf/black
            run: sed 's/a: b/c/' file
            """.trimIndent(),
        )

        assertEquals("https://github.com/psf/black", document.value("repo").text())
        assertEquals("sed 's/a: b/c/' file", document.value("run").text())
    }

    @Test
    fun `quotes come off, and their escapes with them`() {
        val document = ByYaml.parse(
            """
            single: 'it''s here'
            double: "say \"hi\""
            plain: bare value
            """.trimIndent(),
        )

        assertEquals("it's here", document.value("single").text())
        assertEquals("say \"hi\"", document.value("double").text())
        assertEquals("bare value", document.value("plain").text())
    }

    /** Expected values are what PyYAML's `safe_load` reads from the same text. */
    @Test
    fun `escapes are undone in one pass, so an escaped backslash stays a backslash`() {
        val document = ByYaml.parse(
            """
            newline: "a\nb"
            backslash_n: "a\\nb"
            backslash_t: "a\\tb"
            tab: "a\tb"
            unicode: "café \x41"
            """.trimIndent(),
        )

        assertEquals("a\nb", document.value("newline").text())
        assertEquals("a\\nb", document.value("backslash_n").text())
        assertEquals("a\\tb", document.value("backslash_t").text())
        assertEquals("a\tb", document.value("tab").text())
        assertEquals("café A", document.value("unicode").text())
    }

    /** Expected values are what PyYAML's `safe_load` reads from the same text. */
    @Test
    fun `an escaped quote does not end the quoted text`() {
        val document = ByYaml.parse(
            """
            quote_hash: "a \" # b"  # real comment
            single_hash: 'it''s # here'  # real comment
            "quoted \" key": v
            flow: ["a \" , b", c]
            """.trimIndent(),
        )

        assertEquals("a \" # b", document.value("quote_hash").text())
        assertEquals("it's # here", document.value("single_hash").text())
        assertEquals("v", document.value("quoted \" key").text())
        assertEquals(listOf("a \" , b", "c"), document.value("flow").strings())
    }

    @Test
    fun `a scalar sequence item is text, not a mapping`() {
        val document = ByYaml.parse(
            """
            args:
              - --fix
              - --line-length=100
            """.trimIndent(),
        )

        assertEquals(listOf("--fix", "--line-length=100"), document.value("args").strings())
    }

    @Test
    fun `an empty document has no entries and does not throw`() {
        assertTrue(ByYaml.parse("").entries.isEmpty())
        assertTrue(ByYaml.parse("# only a comment\n").entries.isEmpty())
        assertNull(ByYaml.parse("repos:\n").value("repos").text())
    }

    @Test
    fun `a document marker is skipped rather than read as a key`() {
        val document = ByYaml.parse(
            """
            ---
            repos:
            - repo: local
            """.trimIndent(),
        )

        assertEquals(1, document.entries.size)
        assertEquals("repos", document.entries.single().key)
    }
}
