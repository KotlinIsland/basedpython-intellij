package dev.basedpython.pycharm.env.manager

import dev.basedpython.pycharm.env.manager.index.PackageNameStore

/**
 * Which catalogue names to show for what the user has typed.
 *
 * ### Why this is not a completion lookup
 *
 * It was one, and the platform's completion machinery turned out to be the wrong instrument for a
 * package index. Every default it brings had to be fought: the autopopup does not fire unless the
 * provider overrides `acceptChar`; the matchers accept subsequences and substrings rather than
 * prefixes; and a lookup assumes the provider's answer for a short prefix contains its answer for a
 * longer one, which a capped query over 872,009 names cannot promise. Forcing a restart per
 * keystroke fixed the results and made the list blink, because a restart is a teardown.
 *
 * PyCharm's own package dialog does not use completion either — it is a field with a list under it.
 * So this is a plain filter over the catalogue, and the list is an ordinary component that is
 * repopulated rather than destroyed and recreated.
 */
internal object EnvPackageSearch {

    /** How many names the list shows. Enough to scroll, few enough to build instantly. */
    const val MAX_RESULTS: Int = 100

    /**
     * The names to offer for [text], or empty when there is nothing to search for.
     *
     * Only the last requirement on the line is searched, and only while it is still a bare name:
     * once a version specifier, an extra or a URL has been typed the catalogue has nothing to add,
     * and a list that keeps offering names while you type `>=0.27` is in the way rather than
     * helping.
     */
    fun resultsFor(store: PackageNameStore, text: String): List<String> {
        val query = queryIn(text) ?: return emptyList()
        return store.startingWith(query, MAX_RESULTS)
    }

    /**
     * The part of [text] the catalogue should be searched for, or null.
     *
     * Null rather than an empty search for an empty field: showing the alphabetical head of a
     * package index means showing its numeric junk, which is a worse first impression than an empty
     * list and a hint.
     */
    fun queryIn(text: String): String? {
        val last = EnvRequirements.split(text).lastOrNull() ?: return null
        // The name has to be all there is. `httpx>=0.27` is finished being searched for.
        val name = EnvRequirements.packageName(last) ?: return null
        return name.takeIf { it == last && it.isNotEmpty() }
    }

    /**
     * [text] with its last requirement replaced by [name], preserving anything typed before it.
     *
     * Picking from the list edits only the requirement being typed, so a line that already reads
     * `httpx rich ba` becomes `httpx rich basedpython` rather than losing the first two.
     */
    fun replaceLastRequirement(text: String, name: String): String {
        val requirements = EnvRequirements.split(text).toMutableList()
        if (requirements.isEmpty()) return name
        requirements[requirements.lastIndex] = name
        return requirements.joinToString(" ")
    }
}
