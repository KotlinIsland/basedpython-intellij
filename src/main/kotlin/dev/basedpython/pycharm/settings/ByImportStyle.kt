package dev.basedpython.pycharm.settings

/**
 * How an auto-import — a completion's, a quick fix's, a pasted name's — brings in a name from a
 * module the file imports nothing from yet: `by`'s `completions.importStyle`, by its [id].
 *
 * A module the file already imports is imported from the way the file does it, whichever is set;
 * that, and where the import goes, is `by`'s.
 */
enum class ByImportStyle(val id: String, val display: String) {
    /** `from inspect import walktree`, completing `walktree`. `by`'s default. */
    FROM("from", "from module import name"),

    /** `import inspect`, completing `inspect.walktree`. */
    MODULE("module", "import module"),
    ;

    companion object {
        fun fromId(id: String?): ByImportStyle = entries.firstOrNull { it.id == id } ?: FROM
    }
}
