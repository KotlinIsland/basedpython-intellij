package dev.basedpython.pycharm.run.test.node

import dev.basedpython.pycharm.run.test.tree.ByTestLocations

/**
 * What `--collect-only` found, arranged for the question a gutter icon asks: *is the declaration on
 * this line a test, and how many tests are under it?*
 *
 * Keyed by `.by` source path and the `::` chain of names leading to a declaration, with a count at
 * every level: a file's `TestGroup` holds however many tests are inside it, a parametrized
 * `test_add` holds one per generated case, a plain test holds one. That is the same count the node
 * view shows, and it comes from the same place.
 *
 * The distinction that matters is [knows]: a file pytest collected and a file it never saw are not
 * the same "no tests here". Only the first is evidence.
 */
internal class ByTestIndex private constructor(
    private val files: Map<String, Map<List<String>, Int>>,
    /** When the collection behind this index started, as epoch millis. */
    val takenAtMillis: Long,
    /**
     * True when the absence of a file from this index is evidence rather than silence.
     *
     * Collection is run over the whole project with no target, so a *complete* sweep that never
     * mentions a file means that file has no tests pytest would run — the file is not named like a
     * test file, its tests are in a class pytest skips, whatever the reason. Two things spoil that
     * reading: a collection that found nothing at all (there is no sweep to speak of), and one
     * pytest interrupted on a collection error, which stops it before it has seen every file.
     */
    val isComplete: Boolean,
) {

    /**
     * True when the collection has per-declaration knowledge of [file] (a `.by` path relative to
     * the project base) — that is, it named at least one test in it.
     *
     * A node id carrying no name after the path (which is all a file-level collection item is)
     * teaches nothing about any line, so it does not count: claiming the file would strip every
     * icon in it while offering nothing in their place.
     */
    fun knows(file: String): Boolean = files.containsKey(file)

    /**
     * How many collected tests sit at or under [symbols] in [file], or null when the collection
     * has nothing there — which for a [knows] file means pytest did not collect it.
     */
    fun testsAt(file: String, symbols: List<String>): Int? = files[file]?.get(symbols)

    /** True when nothing at all was collected, so every lookup would be a null. */
    fun isEmpty(): Boolean = files.isEmpty()

    companion object {

        /** Nothing has been collected: every question about it answers "no idea". */
        val EMPTY: ByTestIndex = ByTestIndex(emptyMap(), takenAtMillis = 0, isComplete = false)

        /**
         * Indexes [collection]'s node ids, taken at [takenAtMillis].
         *
         * Every prefix of a node id is counted, so the count at a class or a parametrized function
         * is the number of tests below it rather than the number of children — `TestGroup` with two
         * methods is 2, and a `test_add` with four cases is 4.
         */
        fun of(collection: ByCollection, takenAtMillis: Long): ByTestIndex {
            val files = HashMap<String, MutableMap<List<String>, Int>>()
            for (node in collection.nodes) {
                // Reuse the node-id parser the test tree navigates with: it drops the `[params]` of
                // a generated case, so a case counts towards the function that produced it. Which
                // file that is depends on where the node came from — a transpiled `.py` stands for
                // its `.by` source, while plain pytest already named a file in the project.
                val location = ByTestLocations.parse(node.nodeId) ?: continue
                if (location.symbols.isEmpty()) continue
                val counts = files.getOrPut(ByTestNodes.sourcePath(node)) { HashMap() }
                for (depth in 1..location.symbols.size) {
                    val prefix = location.symbols.subList(0, depth)
                    counts[prefix] = (counts[prefix] ?: 0) + 1
                }
            }
            // Files that only produced a collection *error* are deliberately left out, so they
            // count as unknown rather than as empty: a test file that raises while being imported
            // collects nothing, and dropping its gutter icons would hide the tests exactly when
            // the user wants to run one and watch it fail. An error also means pytest stopped
            // early, which is what [isComplete] refuses below.
            return ByTestIndex(
                files = files,
                takenAtMillis = takenAtMillis,
                isComplete = collection.errors.isEmpty() && files.isNotEmpty(),
            )
        }
    }
}
