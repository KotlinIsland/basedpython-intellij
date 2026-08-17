package dev.basedpython.pycharm.env.modules

import java.nio.file.Path

/**
 * What renaming a module moves, worked out before anything is moved.
 *
 * Renaming a module is not one rename. A uv workspace member called `alpha` is up to three separate
 * names for the same thing, and they are not required to agree:
 *
 * - the **distribution name** in its `[project] name`, which is what a sibling writes to depend on it;
 * - the **import package** under `src/`, which is what `import alpha` finds — spelled with
 *   underscores where the distribution name has dashes;
 * - the **directory** the member lives in, which is what the workspace's `members` list points at.
 *
 * Only the first is what the user typed. The other two follow it *when they were following it
 * already*: a member whose directory is called something else entirely was named that way
 * deliberately, and a rename that quietly renamed it too would be doing something nobody asked for.
 * So each is renamed only when its current name is the one derived from the old distribution name —
 * which is what [of] decides, and why it is a pure function with tests rather than a sequence of
 * conditions buried in the operation.
 *
 * ### The order the moves have to happen in
 *
 * Inner first. The import package lives *inside* the module directory, so its path is only valid
 * while the directory is still where it was. [importPackage] is therefore listed against the old
 * directory, and doing [moduleDirectory] first would leave it pointing at nothing.
 */
internal data class ModuleRenamePlan(
    /** The module's own directory, when it is named after the module. */
    val moduleDirectory: Move?,
    /** The importable package inside it, when there is one named after the module. */
    val importPackage: Move?,
    /** The `members` entry naming the directory, when the root manifest names it outright. */
    val memberEntry: Move.Text?,
    /** The distribution name itself, which is the one thing a rename always changes. */
    val distribution: Move.Text,
) {

    /**
     * Every directory move, innermost first.
     *
     * The order is the point — see the note above — so this is offered as one list rather than as
     * two fields the caller has to remember to read in the right order.
     */
    fun moves(): List<Move> = listOfNotNull(importPackage, moduleDirectory)

    /** A path that becomes another path. */
    data class Move(val from: Path, val to: Path) {
        /** A string that becomes another string: a name, or a path written in a manifest. */
        data class Text(val from: String, val to: String)
    }

    companion object {

        /**
         * The plan for renaming [module] to [newName], or null when the name is not really changing.
         *
         * [exists] is asked about candidate directories rather than the filesystem being read here,
         * so the layout rules — `src/` package, flat package, neither — are decided in one place
         * that a test can drive.
         */
        fun of(module: ProjectModule, newName: String, exists: (Path) -> Boolean): ModuleRenamePlan? {
            val old = module.name
            if (!ModuleNames.isValid(newName)) return null
            if (ModuleNames.normalize(old) == ModuleNames.normalize(newName)) return null

            val oldImport = ModuleNames.importName(old)
            val newImport = ModuleNames.importName(newName)

            // Both layouts uv writes, and the only two worth guessing: `src/<package>` is what
            // `uv init --lib` produces, and a flat `<package>` beside the manifest is what a project
            // that predates that convention has.
            val importPackage = listOf(
                module.root.resolve("src").resolve(oldImport),
                module.root.resolve(oldImport),
            )
                .firstOrNull(exists)
                ?.let { Move(it, it.resolveSibling(newImport)) }

            // The directory is renamed only when it is named after the module. A member kept in
            // `packages/legacy-thing` under the name `thing` was put there on purpose.
            val directoryName = module.root.fileName?.toString()
            val moduleDirectory = directoryName
                ?.takeIf { it == old || it == ModuleNames.normalize(old) || it == oldImport }
                ?.let { Move(module.root, module.root.resolveSibling(directoryNameFor(it, old, newName))) }

            val memberEntry = module.memberEntry
                ?.takeIf { moduleDirectory != null }
                ?.let { entry ->
                    val renamed = moduleDirectory
                        ?.to
                        ?.fileName
                        ?.toString()
                        ?: return@let null
                    Move.Text(entry, entry.substringBeforeLast('/', "").let { parent ->
                        if (parent.isEmpty()) renamed else "$parent/$renamed"
                    })
                }

            return ModuleRenamePlan(
                moduleDirectory = moduleDirectory,
                importPackage = importPackage,
                memberEntry = memberEntry,
                distribution = Move.Text(old, newName),
            )
        }

        /**
         * The directory's new name, spelled the way its old one was.
         *
         * A directory called `my_lib` for the distribution `my-lib` was following the *import*
         * spelling, and should go on following it; one called `my-lib` was following the
         * distribution name. Renaming both to the same thing would silently change which convention
         * the project uses.
         */
        private fun directoryNameFor(current: String, oldName: String, newName: String): String =
            if (current == ModuleNames.importName(oldName) && current != ModuleNames.normalize(oldName)) {
                ModuleNames.importName(newName)
            } else {
                ModuleNames.normalize(newName)
            }
    }
}
