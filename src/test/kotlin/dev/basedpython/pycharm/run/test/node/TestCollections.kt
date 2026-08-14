package dev.basedpython.pycharm.run.test.node

/** A collection of [nodeIds], all from [source] — the shape most of these tests care about. */
internal fun collectionOf(
    vararg nodeIds: String,
    source: ByTestSource = ByTestSource.TRANSPILED,
    errors: List<ByCollectionError> = emptyList(),
): ByCollection = ByCollection(nodeIds.map { ByCollectedNode(it, source) }, errors)
