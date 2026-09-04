package dev.basedpython.pycharm.lsp.inlay

/**
 * The kinds of inlay hint `by` computes, one per switch the server itself has.
 *
 * Taken from the server rather than invented here: `ty_ide::InlayHintKind` is what it builds and
 * `InlayHintOptions` in `ty_server` is what it lets a client turn off, and [option] is that option's
 * name. Mirroring it means a kind is switched where it is produced — a hint set to [ByHintMode.NEVER]
 * is one the server never computes, rather than one the plugin drops after paying for it.
 *
 * Two of the server's options are missing on purpose: `templateBindingTypes` and `resolvedTemplates`
 * are django-template hints, and this plugin draws hints for basedpython files only (the platform's
 * own LSP rendering, which would draw them in an `.html` template, is switched off for `by` — see
 * `ByLspServerDescriptor`). A setting for a hint nothing draws would switch nothing.
 *
 * `by` emits no return-type hints, which is why there is no kind for one here: a return annotation
 * is not among the hints it computes, so the plugin's old "return type hints" toggle stood for
 * nothing at all.
 */
enum class ByHintKind(val option: String?, val display: String, val shape: ByHintShape) {
    VARIABLE_TYPES("variableTypes", "Variable types", ByHintShape.TYPE),
    LAMBDA_PARAMETER_TYPES("lambdaParameterTypes", "Lambda parameter types", ByHintShape.TYPE),
    CALL_TYPE_ARGUMENTS("callTypeArguments", "Call type arguments", ByHintShape.TYPE_ARGUMENTS),
    TYPE_ARGUMENT_NAMES("typeArgumentNames", "Type argument names", ByHintShape.TYPE_ARGUMENT_NAME),
    NUMERIC_PROMOTIONS("numericPromotions", "Numeric promotions", ByHintShape.NUMERIC_PROMOTION),
    REVEALED_TYPES("revealedTypes", "Revealed types", ByHintShape.REVEALED_TYPE),
    INFERRED_RAISES("inferredRaises", "Inferred raises clauses", ByHintShape.RAISES),

    CALL_ARGUMENT_NAMES("callArgumentNames", "Call argument names", ByHintShape.ARGUMENT_NAME),
    IMPLICIT_PARAMETERS("implicitParameters", "Implicit parameters", ByHintShape.IMPLICIT_PARAMETER),
    IMPLICIT_SELF("implicitSelf", "Implicit self", ByHintShape.IMPLICIT_PARAMETER),
    IMPLICIT_ARGUMENTS("implicitArguments", "Implicit arguments", ByHintShape.IMPLICIT_ARGUMENT),

    INFERRED_OVERRIDE("inferredOverride", "Inferred override", ByHintShape.OVERRIDE),
    INFERRED_VARIANCE("inferredVariance", "Inferred variance", ByHintShape.VARIANCE),
    INFERRED_REIFICATION("inferredReification", "Inferred reification", ByHintShape.REIFICATION),

    INFERRED_READS("inferredReads", "Inferred state reads", ByHintShape.READS),
    PARAMETER_STABILITY("parameterStability", "Unstable parameters", ByHintShape.STABILITY),
    DERIVED_DEPENDENCIES("derivedDependencies", "Derived dependencies", ByHintShape.DERIVED_DEPENDENCIES),

    /**
     * A hint this plugin does not recognise, which is a hint from a newer `by` than it was built
     * against.
     *
     * The only kind with no [option]: there is nothing to switch off at the server, because the
     * server has a name for it and this does not. It is still switchable here, so a hint that
     * arrives from an upgrade can be quietened or put on the push key the day it appears rather
     * than waiting for a plugin release.
     */
    OTHER(null, "Other hints", ByHintShape.UNKNOWN),
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
         * The kinds a hint of this shape could be.
         *
         * More than one where the wire cannot separate them: `by` writes a variable's type and a
         * lambda parameter's type identically, and an implicit `self` exactly like any other
         * implicit parameter. [ByHintModes] is what decides between the settings when they differ.
         */
        fun of(shape: ByHintShape): List<ByHintKind> = entries.filter { it.shape == shape }
    }
}

/**
 * What a hint looks like on the wire, which is as much as the client can tell about it.
 *
 * LSP carries two kinds, `Type` and `Parameter`, for the seventeen things `by` distinguishes — so
 * the rest is read off the label, which works because a hint stands in for code and is written the
 * way the language writes that code. `by`'s formats are fixed strings (`override `, `reified `,
 * ` raises `, `  revealed: `), so this is recovery, not guesswork; see [ByInlayHints.shapeOf].
 *
 * The shapes are coarser than [ByHintKind] wherever the server writes two kinds the same way. That
 * costs nothing for [ByHintMode.NEVER], which the server enforces by kind, and it is only visible
 * when two kinds sharing a shape are split between *always* and *on push*.
 */
enum class ByHintShape {
    /** `: int` — a binding's or a lambda parameter's inferred type. */
    TYPE,

    /** `[int]` — what a call specialised a generic to. */
    TYPE_ARGUMENTS,

    /** `T=` — the type parameter a positional type argument fills. */
    TYPE_ARGUMENT_NAME,

    /** ` | int` — the arms numeric promotion adds to `float` and `complex`. */
    NUMERIC_PROMOTION,

    /** `  revealed: int` — what a `reveal_type` call reveals. */
    REVEALED_TYPE,

    /** ` raises ValueError` — a function's inferred exception set. */
    RAISES,

    /** `x=` — an argument's parameter name. */
    ARGUMENT_NAME,

    /** `self`, `it: int` — a parameter the source never spells. */
    IMPLICIT_PARAMETER,

    /** `ctx=my_context` — an argument a call fills from a `context` declaration. */
    IMPLICIT_ARGUMENT,

    /** `override ` — a method that overrides without saying so. */
    OVERRIDE,

    /** `out `, `in `, `in out ` — the variance inferred for a type parameter. */
    VARIANCE,

    /** `reified ` — a type parameter reified without saying so. */
    REIFICATION,

    /** ` reads count, items` — the observables a basedpython-ui composable reads while composing. */
    READS,

    /** `unstable ` — a composable parameter the basedpython-ui runtime cannot compare. */
    STABILITY,

    /** ` depends on name, email` — what a basedpython-ui `derived(...)` computation depends on. */
    DERIVED_DEPENDENCIES,

    /** Anything else, which is anything a newer `by` has learned to say. */
    UNKNOWN,
    ;

    /**
     * Whether the inlay sits after the text it belongs to.
     *
     * It decides which side of the inlay the caret lands on when you type at exactly its offset,
     * and which side a selection swallows it with. The shapes that prefix something — a parameter's
     * name, a modifier the declaration does not spell — introduce the code after them; the rest
     * complete the code before them.
     */
    val relatesToPrecedingText: Boolean
        get() = when (this) {
            ARGUMENT_NAME, IMPLICIT_PARAMETER, OVERRIDE, VARIANCE, REIFICATION, STABILITY -> false
            else -> true
        }
}
