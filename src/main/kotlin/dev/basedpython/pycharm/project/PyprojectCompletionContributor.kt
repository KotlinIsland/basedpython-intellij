package dev.basedpython.pycharm.project

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PlatformPatterns.psiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.toml.lang.psi.TomlArrayTable
import org.toml.lang.psi.TomlHeaderOwner
import org.toml.lang.psi.TomlKey
import org.toml.lang.psi.TomlTableHeader

/**
 * Completion in `pyproject.toml` for the `buff` configuration tables.
 *
 * Each table is offered its own keys — `select` in `[tool.ruff.lint]`, `quote-style` in
 * `[tool.ruff.format]` — and nothing is offered in a table that is not `buff`'s. Offering every key
 * everywhere, which this used to, suggested `quote-style` under `[project]` and under `[tool.ruff]`,
 * where `buff` rejects it as an unknown field.
 *
 * The keys are those of the basedpython fork's own `ruff.schema.json`, deprecated ones left out.
 * Registered for TOML only: the PSI is what says which table the caret is in.
 */
class PyprojectCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inFile(psiFile().withName("pyproject.toml")),
            PyprojectKeyProvider,
        )
    }

    internal object PyprojectKeyProvider : CompletionProvider<CompletionParameters>() {

        /** Keys by the table they belong in, as the table's dotted name. */
        val KEYS: Map<String, List<String>> = mapOf(
            "tool.ruff" to listOf(
                "builtins", "cache-dir", "exclude", "extend", "extend-exclude", "extend-include",
                "extension", "fix", "fix-only", "force-exclude", "include", "indent-width",
                "line-length", "namespace-packages", "output-format", "output-prefer-rule-codes",
                "per-file-target-version", "preview", "required-version", "respect-gitignore",
                "show-fixes", "src", "target-version", "unsafe-fixes",
            ),
            "tool.ruff.lint" to listOf(
                "allowed-confusables", "dummy-variable-rgx", "exclude", "explicit-preview-rules",
                "extend-fixable", "extend-per-file-ignores", "extend-safe-fixes", "extend-select",
                "extend-unsafe-fixes", "external", "fixable", "future-annotations", "ignore",
                "logger-objects", "per-file-ignores", "preview", "select", "task-tags",
                "typing-extensions", "typing-modules", "unfixable",
            ),
            "tool.ruff.format" to listOf(
                "assignment-alignment", "docstring-code-format", "docstring-code-line-length",
                "exclude", "indent-style", "line-ending", "nested-string-quote-style", "preview",
                "quote-style", "skip-magic-trailing-comma",
            ),
        )

        /** Table names offered inside a `[...]` header. */
        val SECTIONS: List<String> = listOf(
            "tool.ruff",
            "tool.ruff.lint",
            "tool.ruff.format",
            "tool.ruff.lint.per-file-ignores",
            "tool.ruff.lint.isort",
            "tool.ruff.lint.mccabe",
            "tool.ruff.lint.pydocstyle",
            "tool.basedpython",
            "project",
            "build-system",
            "dependency-groups",
            "tool.uv",
        )

        /** `target-version` values, from the schema's `PythonVersion`. */
        private val TARGET_VERSIONS = listOf(
            "py37", "py38", "py39", "py310", "py311", "py312", "py313", "py314", "py315",
        )

        /** The lint keys whose values are rule selectors. */
        private val SELECTOR_KEYS = setOf(
            "select", "ignore", "extend-select", "fixable", "unfixable", "extend-fixable",
            "extend-safe-fixes", "extend-unsafe-fixes",
        )

        private val RULE_PREFIXES = listOf(
            "E", "W", "F", "I", "N", "D", "UP", "ANN", "B", "C4", "SIM", "PTH", "PL", "RUF", "ALL",
        )

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val position = parameters.position

            PsiTreeUtil.getParentOfType(position, TomlTableHeader::class.java)?.let { header ->
                val typed = parameters.originalFile.text.substring(header.textRange.startOffset, parameters.offset)
                    .removePrefix("[").trimStart()
                val sections = result.withPrefixMatcher(typed)
                for (section in SECTIONS) {
                    sections.addElement(LookupElementBuilder.create(section).withBoldness(true).withTypeText("table"))
                }
                return
            }

            val table = PsiTreeUtil.getParentOfType(position, TomlHeaderOwner::class.java) ?: return
            if (table is TomlArrayTable) return
            val tableName = table.header.key?.dottedName() ?: return

            // Read off the line rather than the PSI: with nothing typed after `=` there is no value
            // element yet, and the completion placeholder parses as the start of another key.
            val text = parameters.originalFile.text
            val line = text.substring(text.lastIndexOf('\n', parameters.offset - 1) + 1, parameters.offset)
            if ('=' in line) {
                val key = line.substringBefore('=').trim()
                when {
                    tableName == "tool.ruff" && key == "target-version" ->
                        TARGET_VERSIONS.forEach { result.addElement(quoted(it, "target-version")) }
                    tableName == "tool.ruff.lint" && key in SELECTOR_KEYS ->
                        RULE_PREFIXES.forEach { result.addElement(quoted(it, "rule prefix")) }
                }
                return
            }

            val keys = KEYS[tableName] ?: return
            for (key in keys) {
                result.addElement(
                    LookupElementBuilder.create(key)
                        .withTypeText(tableName)
                        .withInsertHandler { ctx, _ ->
                            ctx.document.insertString(ctx.tailOffset, " = ")
                            ctx.editor.caretModel.moveToOffset(ctx.tailOffset)
                        },
                )
            }
        }

        private fun quoted(value: String, typeText: String) =
            LookupElementBuilder.create("\"$value\"").withPresentableText(value).withTypeText(typeText)

        private fun TomlKey.dottedName(): String = segments.joinToString(".") { it.name.orEmpty() }
    }
}
