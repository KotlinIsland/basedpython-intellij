package dev.basedpython.pycharm.lsp.inlay

/**
 * The kinds of inlay hint `by` computes, one per switch the server itself has.
 *
 * Taken from the server rather than invented here: `InlayHintOptions` in `ty_server` is what it lets
 * a client turn off, and [option] is that option's name. It is also the name `by` tags each hint
 * with (`data.kind`, see [ByInlayHints.kindOf]), so a hint is filed under the setting that switches
 * it off by the server's own word for it, never by reading its label. Mirroring the options means a
 * kind is switched where it is produced — a hint set to [ByHintMode.NEVER] is one the server never
 * computes, rather than one the plugin drops after paying for it.
 *
 * Two of the server's options are missing on purpose: `templateBindingTypes` and `resolvedTemplates`
 * are django-template hints, and this plugin draws hints for basedpython files only (the platform's
 * own LSP rendering, which would draw them in an `.html` template, is switched off for `by` — see
 * `ByLspServerDescriptor`). A setting for a hint nothing draws would switch nothing.
 *
 * [relatesToPrecedingText] decides which side of the inlay the caret lands on when you type at
 * exactly its offset, and which side a selection swallows it with. The kinds that prefix something —
 * a parameter's name, a modifier the declaration does not spell — introduce the code after them; the
 * rest complete the code before them.
 *
 * [parameterHint] says which of the two toggles the plugin had before these modes existed covered the
 * kind: "parameter hints" for the ones `by` sends under LSP's `Parameter` kind, "type hints" for the
 * rest. It is read only to carry an old settings file forward.
 */
enum class ByHintKind(
    val option: String?,
    val display: String,
    val relatesToPrecedingText: Boolean = true,
    val parameterHint: Boolean = false,
) {
    /** `: int` after a binding. */
    VARIABLE_TYPES("variableTypes", "Variable types"),

    /** `: int` after an unannotated lambda parameter. */
    LAMBDA_PARAMETER_TYPES("lambdaParameterTypes", "Lambda parameter types"),

    /** `: int` after a parameter whose type comes from the method it overrides. */
    INHERITED_PARAMETER_TYPES("inheritedParameterTypes", "Inherited parameter types"),

    /** `: int` after a property declaration that leaves its type to its accessors. */
    PROPERTY_TYPES("propertyTypes", "Property types"),

    /** `-> int` after a `def` that leaves its return annotation out. */
    INFERRED_RETURN_TYPES("inferredReturnTypes", "Inferred return types"),

    /** `[int]` — what a call specialised a generic to. */
    CALL_TYPE_ARGUMENTS("callTypeArguments", "Call type arguments"),

    /** `T=` — the type parameter a positional type argument fills. */
    TYPE_ARGUMENT_NAMES("typeArgumentNames", "Type argument names"),

    /** `| int` — the arms numeric promotion adds to `float` and `complex`. */
    NUMERIC_PROMOTIONS("numericPromotions", "Numeric promotions"),

    /** `int` at the end of a `reveal_type(...)` line — what the call reveals. */
    REVEALED_TYPES("revealedTypes", "Revealed types"),

    /** `raises ValueError` — a function's inferred exception set. */
    INFERRED_RAISES("inferredRaises", "Inferred raises clauses"),

    /** `1` after an enum member that leaves its value out. */
    ENUM_VALUES("enumValues", "Enum values"),

    /** `x=` — an argument's parameter name. */
    CALL_ARGUMENT_NAMES("callArgumentNames", "Call argument names", relatesToPrecedingText = false, parameterHint = true),

    /** `it: int` — a parameter a trailing lambda binds without spelling it. */
    IMPLICIT_PARAMETERS("implicitParameters", "Implicit parameters", relatesToPrecedingText = false, parameterHint = true),

    /** `self` — the receiver an `init(...)` binds without spelling it. */
    IMPLICIT_SELF("implicitSelf", "Implicit self", relatesToPrecedingText = false, parameterHint = true),

    /** `ctx=my_context` — an argument a call fills from a `context` declaration. */
    IMPLICIT_ARGUMENTS("implicitArguments", "Implicit arguments", parameterHint = true),

    /** `=1` — the default a parameter takes from the method its `def` overrides. */
    INHERITED_PARAMETER_DEFAULTS("inheritedParameterDefaults", "Inherited parameter defaults", parameterHint = true),

    /** `override` — a method that overrides without saying so. */
    INFERRED_OVERRIDE("inferredOverride", "Inferred override", relatesToPrecedingText = false),

    /** `out`, `in`, `in out` — the variance inferred for a type parameter. */
    INFERRED_VARIANCE("inferredVariance", "Inferred variance", relatesToPrecedingText = false),

    /** `reified` — a type parameter reified without saying so. */
    INFERRED_REIFICATION("inferredReification", "Inferred reification", relatesToPrecedingText = false),

    /** `reads count, items` — the observables a basedpython-ui composable reads while composing. */
    INFERRED_READS("inferredReads", "Inferred state reads"),

    /** `unstable` — a composable parameter the basedpython-ui runtime cannot compare. */
    PARAMETER_STABILITY("parameterStability", "Unstable parameters", relatesToPrecedingText = false),

    /** `depends on name, email` — what a basedpython-ui `derived(...)` computation depends on. */
    DERIVED_DEPENDENCIES("derivedDependencies", "Derived dependencies"),

    /** `invalidates Counter, Total` — the composables a basedpython-ui state write re-runs. */
    INFERRED_INVALIDATIONS("inferredInvalidations", "Inferred invalidations"),

    /**
     * A hint whose kind this plugin does not know: one tagged with a kind from a newer `by` than the
     * plugin was built against, or one from a `by` that does not tag its hints at all.
     *
     * The only kind with no [option]: there is nothing to switch off at the server, because the
     * server has no name for it that this plugin knows. It is still switchable here, so a hint that
     * arrives from an upgrade can be quietened or put on the push key the day it appears rather than
     * waiting for a plugin release.
     */
    OTHER(null, "Other hints"),
    ;

    /**
     * What this kind is called in the settings file.
     *
     * `by`'s own name for it, so the two files read alike and a kind is recognisable in either.
     * [OTHER] has no name there and takes one of its own.
     */
    val settingsKey: String get() = option ?: "other"

    companion object {
        /**
         * The kind `by` named [option] — its `inlayHints` option, which is also what it tags a hint
         * with — or [OTHER] for a name this plugin does not know, or for no name at all.
         */
        fun ofOption(option: String?): ByHintKind =
            option?.let { name -> entries.firstOrNull { it.option == name } } ?: OTHER
    }
}
