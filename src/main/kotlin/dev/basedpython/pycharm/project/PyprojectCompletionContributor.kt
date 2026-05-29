package dev.basedpython.pycharm.project

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PlatformPatterns.psiFile
import com.intellij.util.ProcessingContext

/**
 * Completion contributor for `pyproject.toml` providing ruff/buff config key suggestions.
 *
 * Implementation notes
 * --------------------
 * This contributor is registered on [PlainTextLanguage] and is scoped by file name
 * (`pyproject.toml`) so it works even without the TOML language plugin on the classpath.
 *
 * When the TOML plugin IS present, the integrator should additionally register this class
 * (or a thin subclass) on `language="TOML"` — see _integration/O.md for details.
 *
 * Registered via plugin.xml:
 *   <completion.contributor language="TEXT"
 *       implementationClass="dev.basedpython.pycharm.project.PyprojectCompletionContributor"/>
 */
class PyprojectCompletionContributor : CompletionContributor() {

    init {
        // Fire for any position inside a plain-text file named pyproject.toml
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement()
                .inFile(psiFile().withName("pyproject.toml")),
            PyprojectKeyProvider,
        )
    }

    private object PyprojectKeyProvider : CompletionProvider<CompletionParameters>() {

        // [tool.ruff] / [tool.ruff.format] / [tool.ruff.lint] keys
        private val RUFF_KEYS = listOf(
            // top-level ruff settings
            "line-length",
            "target-version",
            "src",
            "exclude",
            "extend-exclude",
            "force-exclude",
            "respect-gitignore",
            "output-format",
            "cache-dir",
            "fix",
            "fix-only",
            "unsafe-fixes",
            "show-fixes",
            "show-source",
            "show-files",
            // lint
            "select",
            "ignore",
            "extend-select",
            "extend-ignore",
            "per-file-ignores",
            "extend-per-file-ignores",
            "fixable",
            "unfixable",
            "typing-modules",
            "task-tags",
            "logger-objects",
            "allowed-confusables",
            "dummy-variable-rgx",
            // format
            "quote-style",
            "indent-style",
            "magic-trailing-comma",
            "line-ending",
            "docstring-code-format",
            "docstring-code-line-length",
        )

        // Section header suggestions (TOML section names relevant to ruff/basedpython)
        private val SECTION_KEYS = listOf(
            "[tool.ruff]",
            "[tool.ruff.lint]",
            "[tool.ruff.format]",
            "[tool.ruff.lint.per-file-ignores]",
            "[tool.ruff.lint.isort]",
            "[tool.ruff.lint.mccabe]",
            "[tool.ruff.lint.pydocstyle]",
            "[tool.basedpython]",
            "[project]",
            "[build-system]",
            "[tool.uv]",
            "[tool.uv.dev-dependencies]",
        )

        // Common target-version values
        private val TARGET_VERSIONS = listOf(
            "py38", "py39", "py310", "py311", "py312", "py313",
        )

        // Common rule set prefixes / codes for select/ignore
        private val RULE_PREFIXES = listOf(
            "E", "W", "F", "I", "N", "D", "UP", "ANN", "B", "C4", "SIM",
            "PTH", "PL", "RUF", "ALL",
        )

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val text = parameters.position.containingFile.text
            val offset = parameters.offset
            val lineStart = text.lastIndexOf('\n', offset - 1) + 1
            val currentLine = text.substring(lineStart, offset)

            when {
                // Section header line
                currentLine.trimStart().startsWith("[") -> {
                    SECTION_KEYS.forEach { section ->
                        result.addElement(
                            LookupElementBuilder.create(section)
                                .withPresentableText(section)
                                .withBoldness(true)
                                .withTypeText("section")
                        )
                    }
                }

                // Key = value line: suggest keys
                "=" !in currentLine -> {
                    RUFF_KEYS.forEach { key ->
                        result.addElement(
                            LookupElementBuilder.create(key)
                                .withPresentableText(key)
                                .withTypeText("ruff")
                                .withInsertHandler { ctx, _ ->
                                    ctx.document.insertString(ctx.tailOffset, " = ")
                                    ctx.editor.caretModel.moveToOffset(ctx.tailOffset)
                                }
                        )
                    }
                }

                // After = for target-version
                currentLine.contains("target-version") && currentLine.contains("=") -> {
                    TARGET_VERSIONS.forEach { v ->
                        result.addElement(
                            LookupElementBuilder.create("\"$v\"")
                                .withPresentableText(v)
                                .withTypeText("target-version")
                        )
                    }
                }

                // After = for select/ignore/extend-select/extend-ignore
                (currentLine.contains("select") || currentLine.contains("ignore")) &&
                    currentLine.contains("=") -> {
                    RULE_PREFIXES.forEach { prefix ->
                        result.addElement(
                            LookupElementBuilder.create("\"$prefix\"")
                                .withPresentableText(prefix)
                                .withTypeText("rule prefix")
                        )
                    }
                }
            }
        }
    }
}
