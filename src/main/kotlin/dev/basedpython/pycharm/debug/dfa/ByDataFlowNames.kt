package dev.basedpython.pycharm.debug.dfa

/**
 * Which names to ask a debugger about.
 *
 * The debugger answers about names it is *given*: asking for a whole frame would read values
 * nobody is going to reason about, and a frame's locals are the expensive part of a stop. The
 * client knows which names matter because the client is the one holding the source — so this reads
 * the region that is about to be analysed and takes the names out of it.
 *
 * Deliberately not a parse. A `.by` file's PSI in this plugin is the file plus one leaf per token,
 * so there is no tree to walk, and a wrong name here costs nothing: the debugger reports it
 * unbound and the server never sees it. What a wrong name must not do is be *missed*, which is why
 * this over-collects rather than trying to be clever about scope.
 */
object ByDataFlowNames {

    /**
     * How many names one stop may ask about.
     *
     * A bound rather than a guess at what is interesting: every name costs a read in the debuggee
     * on a thread that is being held, and this runs again on every step. A file with more
     * identifiers below the stop line than this is one where the first few hundred are the ones
     * being looked at.
     */
    const val MAX_NAMES: Int = 128

    /**
     * A name, or a dotted path — `limit`, `self.limit`, `self.config.timeout`.
     *
     * Keywords are excluded because a debugger has nothing to say about them and `by` would find
     * no place for them. Everything else that looks like a name is taken.
     */
    private val NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")

    /**
     * Names that are never worth asking about.
     *
     * Python's keywords, plus the soft keywords a name could otherwise be confused with. A
     * debugger asked about `if` answers "unbound", which is correct and useless.
     */
    private val KEYWORDS = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
        "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
        "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
        "try", "while", "with", "yield",
        // basedpython's own
        "abstract", "data", "enum", "final", "let", "newtype", "override", "private", "protocol",
        "public", "static", "match", "case", "type", "extension",
    )

    /**
     * The names mentioned at or after `offset`, in first-appearance order.
     *
     * Order matters only for which names survive [MAX_NAMES], and first-appearance is the right
     * tie-break: the code nearest the stop line is the code the user is looking at.
     */
    fun below(text: CharSequence, offset: Int): List<String> {
        if (offset >= text.length) return emptyList()

        val seen = LinkedHashSet<String>()
        for (match in NAME.findAll(text, offset)) {
            val name = match.value
            val root = name.substringBefore('.')
            if (root in KEYWORDS) continue
            // A dotted path whose root is a keyword is not a path; one whose *later* segment is a
            // keyword cannot be produced by the pattern, because a keyword is a whole word
            seen.add(name)
            if (seen.size >= MAX_NAMES) break
        }
        return seen.toList()
    }
}
