package dev.basedpython.pycharm.lsp.inlay

/**
 * `by`'s kinds of inlay hint, by their option names, as of the `by` these tests were written against:
 * what a test that iterates every kind iterates. The plugin itself has no such list — its kinds are
 * whatever `by/inlayHintCatalog` names.
 */
internal val TEST_HINT_KINDS: List<ByHintKind> = listOf(
    "variableTypes", "lambdaParameterTypes", "inheritedParameterTypes", "inferredParameterTypes", "propertyTypes",
    "returnTypes", "callTypeArguments", "typeArgumentNames", "inferredBaseTypeArguments", "numericPromotions",
    "revealedTypes", "inferredRaises", "enumValues", "callArgumentNames", "implicitParameters", "implicitSelf",
    "implicitArguments", "inheritedParameterDefaults", "inferredOverride", "inferredVariance", "inferredReification",
    "inferredReads", "parameterStability", "derivedDependencies", "inferredInvalidations",
).map(::ByHintKind)

internal val TEST_HINT_SETTINGS: List<String> = TEST_HINT_KINDS.map { it.setting }

/** A catalog naming [TEST_HINT_KINDS], each titled by its setting, with no examples. */
internal fun testHintCatalog(): ByInlayHintCatalog = ByInlayHintCatalog(
    groups = listOf(ByHintGroup("all", "All")),
    kinds = TEST_HINT_SETTINGS.map { ByHintKindInfo(setting = it, title = "Title of $it", group = "all") },
)

/** Runs [body] with [catalog] as the last one by gave, and puts back what was there. */
internal fun <T> withHintCatalog(catalog: ByInlayHintCatalog = testHintCatalog(), body: () -> T): T {
    val service = ByInlayCatalog.getInstance()
    val saved = service.state.json
    service.update(catalog)
    try {
        return body()
    } finally {
        service.loadState(ByInlayCatalog.State().apply { json = saved })
    }
}
