package dev.basedpython.pycharm.env.manager

/**
 * Naming a dependency list in a field the user can type into.
 *
 * The Add Package dialog offers the lists a project already has and lets a new group be typed, which
 * means the combo box is editable — and an editable combo renders its *selected* value through its
 * editor rather than through any renderer set on it. The editor calls `toString()`, so a typed model
 * put `Group(name=dev)` on screen. Holding text in the model instead of objects fixes that at the
 * source, and this is the pair of functions that keeps text and target in step.
 *
 * The tokens are chosen to be things a person would actually type. `dependencies` is what
 * `pyproject.toml` calls the main list; a bare name is a dependency group, which is the one thing
 * that can be conjured on demand; an extra carries a suffix, because `[project.optional-dependencies]`
 * is a decision about the package's public interface and should never be created by a typo.
 */
internal object EnvTargetLabels {

    /** What the main list is called, matching the `pyproject.toml` key. */
    const val MAIN: String = "dependencies"

    /** Marks an extra, so `cli` the group and `cli` the extra stay distinguishable. */
    const val EXTRA_SUFFIX: String = " (extra)"

    /** The token for [target]. */
    fun format(target: EnvDependencyTarget): String = when (target) {
        EnvDependencyTarget.Main -> MAIN
        is EnvDependencyTarget.Group -> target.name
        is EnvDependencyTarget.Extra -> target.name + EXTRA_SUFFIX
    }

    /**
     * The target [text] names, or null when it names nothing.
     *
     * Anything unrecognised is a dependency group, which is what makes "type a name and it will be
     * created" work. The one ambiguity this accepts is a project with a group genuinely named
     * `dependencies`: it would be read as the main list. That is a name nobody picks — it would
     * collide with the section right above it in the same file — and the alternative is refusing to
     * let anyone type the ordinary word for the ordinary list.
     */
    fun parse(text: String): EnvDependencyTarget? {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> null
            trimmed.equals(MAIN, ignoreCase = true) -> EnvDependencyTarget.Main
            trimmed.endsWith(EXTRA_SUFFIX, ignoreCase = true) ->
                trimmed.dropLast(EXTRA_SUFFIX.length).trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let(EnvDependencyTarget::Extra)
            else -> EnvDependencyTarget.Group(trimmed)
        }
    }

    /**
     * The tokens to offer, given what the project already declares and where the user was.
     *
     * The main list and `dev` are always offered whether or not the project has them yet: they are
     * the answer to almost every add, and a project with neither is exactly the one about to gain
     * its first.
     */
    fun options(
        existing: List<EnvDependencyTarget>,
        initial: EnvDependencyTarget,
    ): List<String> {
        val targets = LinkedHashSet<EnvDependencyTarget>()
        targets += EnvDependencyTarget.Main
        targets += EnvDependencyTarget.DEV
        targets += existing
        targets += initial
        return targets.map(::format)
    }
}
