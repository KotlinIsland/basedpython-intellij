package dev.basedpython.pycharm.run.main

import dev.basedpython.pycharm.lsp.ext.ByMainFunctionReply
import dev.basedpython.pycharm.lsp.ext.ByMainParameterReply

/**
 * How a parameter can be handed to `main` itself: a positional-only parameter is always passed
 * positionally even when the command line named it with `--`, a keyword-only one takes no positional
 * slot at all, and everything else can go either way.
 */
internal enum class ByParameterKind {
    POSITIONAL,
    ANY,
    KEYWORD,
    ;

    companion object {
        /** The spelling `by/entryPoint` uses, which is the one the generated parser's spec carries. */
        fun of(wire: String?): ByParameterKind = when (wire) {
            "positional" -> POSITIONAL
            "keyword" -> KEYWORD
            else -> ANY
        }
    }
}

/** What kind of value a command-line parameter takes, which decides how the form asks for it. */
internal enum class ByCliType {
    STR,
    INT,
    FLOAT,
    /** A flag pair — `--verbose` / `--no-verbose` — rather than a value, and it takes no positional slot. */
    BOOL,
    PATH,
    ;

    companion object {
        /**
         * The type behind the converter the generated parser is handed, as `by/entryPoint` names it;
         * a flag pair has none. The server only ever names these, so anything else is no type at all.
         */
        fun ofConverter(converter: String?): ByCliType? = when (converter) {
            null -> BOOL
            "str" -> STR
            "int" -> INT
            "float" -> FLOAT
            "Path", "pathlib.Path" -> PATH
            else -> null
        }
    }
}

/** One parameter of `main`, as the command line sees it. */
internal data class ByMainParameter(
    val name: String,
    /** The annotation as written, or `""` when the parameter has none. */
    val annotation: String,
    /** The default as written, or null when the parameter has none. */
    val default: String?,
    val kind: ByParameterKind,
    val isRequired: Boolean,
    /** What the command line fills it with; null when the command line cannot fill it. */
    val type: ByCliType?,
    /** Every option spelling the generated parser registers, the one to write first; empty when unexposed. */
    val flags: List<String>,
    /** The `--no-…` spellings of a [ByCliType.BOOL]; empty otherwise. */
    val negativeFlags: List<String>,
) {
    val isExposed: Boolean get() = type != null

    val flag: String get() = flags.first()

    /** The `--no-…` spelling that sets a [ByCliType.BOOL] parameter false. */
    val negativeFlag: String get() = negativeFlags.first()

    companion object {
        fun of(reply: ByMainParameterReply): ByMainParameter {
            val cli = reply.cli
            val name = reply.name.orEmpty()
            return ByMainParameter(
                name = name,
                annotation = reply.annotation.orEmpty(),
                default = reply.default,
                kind = ByParameterKind.of(reply.kind),
                isRequired = reply.required,
                type = cli?.let { ByCliType.ofConverter(it.converter) },
                flags = cli?.flags.orEmpty(),
                negativeFlags = cli?.negativeFlags.orEmpty(),
            )
        }
    }
}

/**
 * A module's `main` as its program's command line, as `by/entryPoint` reported it.
 *
 * basedpython turns `main`'s parameters into the program's command-line interface and appends the
 * `__main__` guard that feeds them in, so this is everything needed to ask a user for the arguments
 * a run needs — and to know when there is nothing to ask. Which `main` it is, and which of its
 * parameters the command line fills, is the transpiler's decision, made in the server.
 */
internal data class ByMainFunction(
    /** 0-based line of the `def`'s name. */
    val line: Int,
    val isAsync: Boolean,
    /** Declared order. Variadics are left out: they are never exposed and never require a value. */
    val parameters: List<ByMainParameter>,
    /** `main`'s docstring, which becomes the generated parser's `--help` description. */
    val docstring: String?,
    /** The name of the parameter that stops this `main` being an entry point, if any. */
    private val blockedByName: String?,
) {
    /** The parameters the command line fills. */
    val exposed: List<ByMainParameter> get() = parameters.filter { it.isExposed }

    /** Those of [exposed] that have no default, so a run without them fails to start. */
    val required: List<ByMainParameter> get() = exposed.filter { it.isRequired }

    /**
     * The parameter that stops this `main` being an entry point at all, if any.
     *
     * A required parameter the command line cannot supply means calling `main` would raise
     * `TypeError`, so basedpython emits *no* guard — the module runs and quietly does nothing. That
     * silence is worth naming in the UI, because nothing else about the run reports it.
     */
    val blockedBy: ByMainParameter? get() = blockedByName?.let { name -> parameters.firstOrNull { it.name == name } }

    val isEntryPoint: Boolean get() = blockedByName == null

    /** True when there is an argument form worth showing. */
    val takesArguments: Boolean get() = isEntryPoint && exposed.isNotEmpty()

    companion object {
        fun of(reply: ByMainFunctionReply): ByMainFunction = ByMainFunction(
            line = reply.nameRange?.start?.line ?: 0,
            isAsync = reply.isAsync,
            parameters = reply.parameters.map(ByMainParameter::of),
            docstring = reply.docstring?.trim()?.ifBlank { null },
            blockedByName = reply.blockedBy,
        )
    }
}
