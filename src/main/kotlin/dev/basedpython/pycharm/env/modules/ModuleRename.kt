package dev.basedpython.pycharm.env.modules

import dev.basedpython.pycharm.env.manager.EnvDependencyTarget
import dev.basedpython.pycharm.env.manager.EnvOp
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Path

/**
 * Renaming a module, as one step that either happens completely or not at all.
 *
 * Six things change together — the imports, the siblings' declarations, the import package, the
 * module's directory, its `[project] name` and the root's `members` entry — and each of them can
 * fail: the server may not answer, a directory may already exist where this one is going, `uv`
 * may fail to resolve. A rename that stops at the first failure and leaves the rest is a project
 * that no longer builds, made by a dialog whose OK looked like it worked. So:
 *
 * 1. **Everything that can be checked without changing anything is checked first**: the plan, the
 *    layout as it is now, that every destination is free, and the server's answer for the imports
 *    — asked, not applied.
 * 2. **Every step that changes something records how to take it back**, and the first one that
 *    fails — or a cancellation — takes back every step before it, newest first.
 *
 * What cannot be taken back is what uv did to the environment: `uv remove` and `uv add` sync, and a
 * rename that is rolled back leaves the manifests and the lock as they were and the environment
 * reported as drifted, which the view offers to fix.
 *
 * ### The order
 *
 * 1. **The siblings stop declaring it**, while it still exists under its old name. Doing this after
 *    the move would have uv resolving a workspace that names a member that is not there.
 * 2. **The imports**, with the edits the server gave before anything moved — the only moment the
 *    question was answerable, since the old path still held the module.
 * 3. **The directories move** — the import package first, then the module's own directory, since
 *    the first lives inside the second.
 * 4. **Its manifest** takes the new `[project] name`, and **the root's `members` entry** follows the
 *    directory, when it named it outright.
 * 5. **The siblings declare it again**, under the new name and in the same lists they declared it
 *    in before — a module a sibling used only in its `dev` group does not become a runtime
 *    dependency by being renamed.
 *
 * Everything that touches the project goes through [Io], so the rules above are tested against a
 * temporary directory and a stand-in for uv rather than asserted in comments.
 */
internal class ModuleRename(private val io: Io, private val projectRoot: Path) {

    /** What a rename does to the world, one primitive at a time. */
    interface Io {
        /** The project's modules as they are right now, or null when they cannot be read. */
        fun layout(): ModuleLayout?

        /** True when [path] is a directory — what [ModuleRenamePlan.of] asks about. */
        fun isDirectory(path: Path): Boolean

        /** True when anything at all is at [path]. */
        fun exists(path: Path): Boolean

        /** The server's import edits for [moves], asked and not applied; null when it cannot say. */
        fun importEdits(moves: List<ModuleRenamePlan.Move>): ImportEdits?

        /** Moves one directory; false, having reported why, when it could not. */
        fun move(move: ModuleRenamePlan.Move): Boolean

        /** The text of [file], or null when there is no such file. */
        fun read(file: Path): String?

        /** Makes [file] say [text], or removes it when [text] is null; false, having reported why, when it could not. */
        fun write(file: Path, text: String?): Boolean

        /** Runs one backend command to completion; false, having reported why, when it failed. */
        fun run(op: EnvOp): Boolean

        fun report(message: String)

        fun progress(text: String)
    }

    /** The import edits a rename applies, and takes back when a later step fails. */
    interface ImportEdits {
        fun apply(): Boolean

        fun revert()
    }

    /** Thrown to stop the forward steps; the failure itself has already been reported. */
    private class Failed : RuntimeException(null, null, false, false)

    /**
     * Renames [module] to [newName] and returns it as it is afterwards, or null when it was not
     * renamed — in which case every manifest, directory and import is as it was.
     */
    fun rename(module: ProjectModule, newName: String): ProjectModule? {
        io.progress(BasedPythonBundle.message("modules.progress.renaming", module.name, newName))

        // ---- checks: nothing below has changed anything yet ------------------------------------

        val plan = ModuleRenamePlan.of(module, newName, io::isDirectory) ?: return null
        val layout = io.layout() ?: return null
        val current = layout.byName(module.name) ?: return null
        // Each sibling with every list it declares the module in — captured now, because step 1
        // erases the only record of it.
        val declarations: List<Pair<ProjectModule, List<EnvDependencyTarget>>> =
            layout.dependents(current.name).map { it to it.dependsOn(current.name) }

        for (move in plan.moves()) {
            if (!io.isDirectory(move.from)) {
                io.report(BasedPythonBundle.message("modules.failed.moveMissing", move.from.toString()))
                return null
            }
            if (io.exists(move.to)) {
                io.report(BasedPythonBundle.message("modules.failed.moveTaken", move.from.toString(), move.to.toString()))
                return null
            }
        }

        val imports = io.importEdits(plan.moves()) ?: run {
            io.report(BasedPythonBundle.message("modules.failed.imports"))
            return null
        }

        // What every manifest uv or this class is about to rewrite said before, and the lock uv
        // re-resolves on each command. Absent files are recorded as absent, so a lock that a failed
        // rename created is removed again rather than left behind.
        val manifest = current.root.resolve(UvWorkspace.MANIFEST)
        val snapshot = (
            listOf(projectRoot.resolve(UvWorkspace.MANIFEST), projectRoot.resolve(LOCK), manifest) +
                declarations.map { (dependent, _) -> dependent.root.resolve(UvWorkspace.MANIFEST) }
            ).distinct().associateWith { io.read(it) }

        // ---- changes: each one pushes its own undo --------------------------------------------

        val undo = ArrayDeque<() -> Unit>()
        try {
            undo.addFirst { restore(snapshot) }

            // 1. The siblings stop declaring it, from every list they declare it in.
            for ((dependent, targets) in declarations) {
                io.progress(BasedPythonBundle.message("modules.progress.unwiring", current.name, dependent.name))
                for (target in targets) {
                    check(io.run(EnvOp.Remove(listOf(current.name), target, module = dependent.name)))
                }
            }

            // 2. The imports.
            check(imports.apply())
            undo.addFirst { imports.revert() }

            // 3. The directories, innermost first.
            for (move in plan.moves()) {
                check(io.move(move))
                undo.addFirst { io.move(ModuleRenamePlan.Move(move.to, move.from)) }
            }

            // 4. The two manifests that name it.
            val movedManifest = (plan.moduleDirectory?.to ?: current.root).resolve(UvWorkspace.MANIFEST)
            edit(movedManifest) { TomlEdits.setString(it, PROJECT, "name", newName) }
            plan.memberEntry?.let { entry ->
                edit(projectRoot.resolve(UvWorkspace.MANIFEST)) { text ->
                    TomlEdits.addArrayItem(
                        TomlEdits.removeArrayItem(text, WORKSPACE, "members", entry.from),
                        WORKSPACE,
                        "members",
                        entry.to,
                    )
                }
            }

            // 5. The siblings declare it again, in the lists they had it in.
            for ((dependent, targets) in declarations) {
                io.progress(BasedPythonBundle.message("modules.progress.wiring", newName, dependent.name))
                for (target in targets) {
                    check(io.run(EnvOp.Add(listOf(newName), target, module = dependent.name)))
                }
            }
        } catch (e: Throwable) {
            // A step that failed has reported itself; a cancellation, or anything unexpected, goes
            // on up once the project is back the way it was.
            rollBack(undo)
            if (e is Failed) return null
            throw e
        }

        return io.layout()?.byName(newName)
    }

    private fun check(succeeded: Boolean) {
        if (!succeeded) throw Failed()
    }

    /** Rewrites [file] through [change]; a missing file or a failed write stops the rename. */
    private fun edit(file: Path, change: (String) -> String) {
        val original = io.read(file) ?: run {
            io.report(BasedPythonBundle.message("modules.failed.manifest", file.toString(), "not found"))
            throw Failed()
        }
        val updated = change(original)
        if (updated != original) check(io.write(file, updated))
    }

    /** Puts back what [snapshot] recorded, for every file whose content has changed since. */
    private fun restore(snapshot: Map<Path, String?>) {
        for ((file, text) in snapshot) {
            if (io.read(file) != text) io.write(file, text)
        }
    }

    /**
     * Runs every recorded undo, newest first, each on its own: one that throws must not leave the
     * steps before it in place.
     */
    private fun rollBack(undo: ArrayDeque<() -> Unit>) {
        for (step in undo) {
            try {
                step()
            } catch (e: Throwable) {
                io.report(BasedPythonBundle.message("modules.failed.rollback", e.message ?: e.toString()))
            }
        }
        undo.clear()
    }

    private companion object {
        const val LOCK = "uv.lock"
        val PROJECT = listOf("project")
        val WORKSPACE = listOf("tool", "uv", "workspace")
    }
}
