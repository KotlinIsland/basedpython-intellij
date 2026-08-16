package dev.basedpython.pycharm.env.manager.index

/**
 * Enough of PEP 440 to order versions and to answer "will this release run on this Python".
 *
 * ### Why this exists rather than a library
 *
 * Both questions are unavoidable once the dialog offers a version list. Sorting them as strings puts
 * `1.9` above `1.10`, and a `requires_python` of `>=2.7, !=3.0.*, !=3.1.*, <4` — a real one, from
 * urllib3 — cannot be compared by any amount of string handling. The platform ships no PEP 440
 * implementation and pulling one in for two functions is not worth a dependency.
 *
 * ### What it covers
 *
 * The release segment, epochs, pre-releases (`a`/`b`/`rc` and their spellings), `.post` and `.dev`,
 * and the comparison rules that put `2.0.0a1` below `2.0.0` and `1.0.dev1` below both. Specifiers:
 * `==`, `!=`, `<=`, `>=`, `<`, `>`, `~=` and `===`, with `.*` wildcards and comma-separated clauses
 * that all have to hold.
 *
 * What it does not cover is local versions (`+local`) beyond ignoring them, and the finer points of
 * pre-release *exclusion* — a specifier that says `>=3.8` is treated as satisfied by `3.8.0rc1`,
 * where the strict reading excludes pre-releases unless asked for. That distinction cannot change
 * the answer here, because the thing being tested against is an interpreter version the user already
 * has installed.
 */
internal object Pep440 {

    /**
     * A version, decomposed for comparison.
     *
     * [preKey] carries the ordering rule rather than the data: a release with no pre-release sorts
     * *above* one with, and a bare `.dev` sorts below everything, so the two absent cases map to
     * opposite extremes rather than to a shared "none".
     */
    private data class Parsed(
        val epoch: Int,
        val release: List<Int>,
        val preKey: Triple<Int, String, Int>,
        val post: Int,
        val dev: Int,
    )

    private val VERSION = Regex(
        """^\s*v?(?:(\d+)!)?(\d+(?:\.\d+)*)""" +
            """(?:[-_.]?(a|b|c|rc|alpha|beta|pre|preview)[-_.]?(\d*))?""" +
            """(?:[-_.]?(post|rev|r)[-_.]?(\d*)|-(\d+))?""" +
            """(?:[-_.]?(dev)[-_.]?(\d*))?""" +
            """(?:\+[a-z0-9]+(?:[-_.][a-z0-9]+)*)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parse(version: String): Parsed? {
        val m = VERSION.matchEntire(version.trim().lowercase()) ?: return null
        val epoch = m.groupValues[1].toIntOrNull() ?: 0
        val release = m.groupValues[2].split('.').map { it.toIntOrNull() ?: 0 }

        val preLetter = m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::normalisePre)
        val preNumber = m.groupValues[4].toIntOrNull() ?: 0
        val post = (m.groupValues[6].takeIf { it.isNotEmpty() } ?: m.groupValues[7])
            .let { if (m.groupValues[5].isNotEmpty() || m.groupValues[7].isNotEmpty()) (it.toIntOrNull() ?: 0) else -1 }
        val hasDev = m.groupValues[8].isNotEmpty()
        val dev = if (hasDev) (m.groupValues[9].toIntOrNull() ?: 0) else Int.MAX_VALUE

        // A bare `.dev` release sorts below every other spelling of the same version; anything with
        // no pre-release at all sorts above every pre-release of it.
        val preKey = when {
            preLetter == null && post < 0 && hasDev -> Triple(-1, "", 0)
            preLetter == null -> Triple(1, "", 0)
            else -> Triple(0, preLetter, preNumber)
        }
        return Parsed(epoch, release, preKey, post, dev)
    }

    /** `alpha`/`beta`/`c`/`pre`/`preview` are spellings of `a`/`b`/`rc`. */
    private fun normalisePre(letter: String): String = when (letter) {
        "alpha" -> "a"
        "beta" -> "b"
        "c", "pre", "preview" -> "rc"
        else -> letter
    }

    /** True when [version] can be read as a version at all. */
    fun isValid(version: String): Boolean = parse(version) != null

    /**
     * Orders two versions, newest last.
     *
     * A version that cannot be parsed sorts below every one that can, rather than throwing: the
     * index carries some genuinely strange historical versions and one of them must not take the
     * whole list down.
     */
    fun compare(a: String, b: String): Int {
        val left = parse(a)
        val right = parse(b)
        if (left == null || right == null) {
            return when {
                left == null && right == null -> a.compareTo(b)
                left == null -> -1
                else -> 1
            }
        }
        if (left.epoch != right.epoch) return left.epoch.compareTo(right.epoch)

        // Compared position by position with the shorter padded, so `1.2` and `1.2.0` are equal.
        val width = maxOf(left.release.size, right.release.size)
        for (i in 0 until width) {
            val l = left.release.getOrElse(i) { 0 }
            val r = right.release.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        comparePre(left.preKey, right.preKey).let { if (it != 0) return it }
        if (left.post != right.post) return left.post.compareTo(right.post)
        return left.dev.compareTo(right.dev)
    }

    private fun comparePre(a: Triple<Int, String, Int>, b: Triple<Int, String, Int>): Int {
        if (a.first != b.first) return a.first.compareTo(b.first)
        if (a.second != b.second) return a.second.compareTo(b.second)
        return a.third.compareTo(b.third)
    }

    /** Newest first — the order a version picker offers them in. */
    val NEWEST_FIRST: Comparator<String> = Comparator { a, b -> compare(b, a) }

    /** True when [version] is a pre-release: an alpha, beta, release candidate or dev build. */
    fun isPreRelease(version: String): Boolean {
        val parsed = parse(version) ?: return false
        return parsed.preKey.first <= 0
    }

    /**
     * True when [version] satisfies [specifier].
     *
     * Every comma-separated clause has to hold — that is what `>=2.7, !=3.0.*, <4` means. A blank or
     * absent specifier is satisfied by anything, which is the right reading of a package that
     * declares no `requires_python`: it is not claiming incompatibility, it is saying nothing.
     *
     * A clause this cannot parse is treated as satisfied rather than as failing. Reporting a package
     * as incompatible on the strength of a specifier we could not read would hide a perfectly good
     * release behind our own gap.
     */
    fun satisfies(version: String, specifier: String?): Boolean {
        if (specifier.isNullOrBlank()) return true
        return specifier.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .all { clause -> satisfiesClause(version, clause) }
    }

    private fun satisfiesClause(version: String, clause: String): Boolean {
        val match = Regex("""^(===|==|!=|<=|>=|~=|<|>)\s*(.+)$""").matchEntire(clause) ?: return true
        val op = match.groupValues[1]
        val operand = match.groupValues[2].trim()

        if (op == "===") return version.trim() == operand

        val wildcard = operand.endsWith(".*")
        val bare = if (wildcard) operand.dropLast(2) else operand

        return when (op) {
            "==" -> if (wildcard) startsWithRelease(version, bare) else compare(version, bare) == 0
            "!=" -> if (wildcard) !startsWithRelease(version, bare) else compare(version, bare) != 0
            "<=" -> compare(version, bare) <= 0
            ">=" -> compare(version, bare) >= 0
            "<" -> compare(version, bare) < 0
            ">" -> compare(version, bare) > 0
            // `~= X.Y` is ">= X.Y, == X.*": the last component may move, nothing above it.
            "~=" -> compare(version, bare) >= 0 && startsWithRelease(version, bare.substringBeforeLast('.'))
            else -> true
        }
    }

    /** True when [version]'s release segment begins with [prefix]'s — what `==X.Y.*` asks. */
    private fun startsWithRelease(version: String, prefix: String): Boolean {
        val v = parse(version)?.release ?: return false
        val p = parse(prefix)?.release ?: return false
        if (p.size > v.size) return false
        return p.indices.all { v[it] == p[it] }
    }
}
