package dev.basedpython.pycharm.env.modules

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.basedpython.pycharm.env.manager.EnvBackend
import dev.basedpython.pycharm.env.manager.EnvDependencyTarget
import dev.basedpython.pycharm.env.manager.EnvOp
import dev.basedpython.pycharm.env.manager.EnvOperations
import dev.basedpython.pycharm.env.manager.EnvService
import dev.basedpython.pycharm.ui.log.BasedPythonLogNotifications
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Files
import java.nio.file.Path

/**
 * Creating, changing and removing a module, as one gesture each.
 *
 * ### uv does what uv can do
 *
 * Every step that uv has a command for is uv's: `uv init` scaffolds the module *and* lists it in the
 * project's manifest, `uv add`/`uv remove` wire a module into its siblings and write the
 * `[tool.uv.sources] … { workspace = true }` entry that makes the dependency resolve locally. Only
 * what uv has no command for is done by hand, and there is exactly one such thing — un-listing a
 * module ([TomlEdits]) — because uv adds a `members` entry and never takes one away.
 *
 * ### What none of this does
 *
 * Sync. Creating a module leaves the lock file describing a project that has one fewer, and the
 * honest thing to do about that is what the environment view already does: report the drift and
 * offer the button. Running a resolve — which can reach the network and download an interpreter —
 * off the back of "I made a new directory" is the behaviour this plugin's uv rule exists to prevent
 * (see [EnvService]). Adding the module as a dependency of another *does* sync, because that is
 * `uv add`'s own doing and the user asked for a wired-up module.
 */
internal object ModuleOperations {

    /** What *New module* collected. */
    data class NewModule(
        val name: String,
        /** Where it goes, relative to the project root, `/`-separated. */
        val path: String,
        val kind: ModuleKind,
        val description: String? = null,
        /** The interpreter to derive `requires-python` from, or null for the project's own. */
        val python: String? = null,
        /** Modules that should depend on the new one, by name. */
        val dependents: List<String> = emptyList(),
    )

    /**
     * What *Edit module* changed, as the state the module should end in rather than as a diff.
     *
     * The dialog knows what it showed and what the user left alone; working out which of those are
     * actual changes is [apply]'s job, against the layout as it is at the moment the operation
     * starts. That ordering matters: a dialog left open while a sibling was added in a terminal must
     * not un-declare it on OK.
     */
    data class ModuleEdit(
        val version: String?,
        val description: String?,
        val requiresPython: String?,
        /** The names of the modules that should depend on this one when this is over. */
        val dependents: Set<String>,
        /**
         * The name the module should end up with, when the user changed it.
         *
         * Null when the field was left alone, which is not the same as "the same name": a rename is
         * a different operation with different failure modes, and [apply] does it first and
         * separately rather than folding it into the metadata write.
         */
        val newName: String? = null,
    )

    /**
     * Scaffolds a module and wires it into whatever asked for it.
     *
     * The order is not arbitrary: the module has to exist before a sibling can declare it, and the
     * declaration is what installs it — so a create with dependents ends with a synced environment
     * and one without ends with the environment view reporting drift.
     */
    fun create(project: Project, request: NewModule) {
        val service = EnvService.getInstance(project)
        val backend = service.status.backend ?: return
        val root = service.status.projectRoot ?: return
        val directory = root.resolve(request.path.replace('/', java.io.File.separatorChar))

        EnvOperations.runInBackground(
            project,
            BasedPythonBundle.message("modules.progress.creating", request.name),
            // The directory itself, because everything uv is about to write inside it is new to the
            // IDE, and a module whose files the project view cannot see is not a module the user has.
            extraFiles = listOf(directory),
        ) { indicator ->
            val created = EnvOperations.runBlockingOp(
                project,
                backend,
                EnvOp.InitModule(
                    path = request.path,
                    name = request.name,
                    kind = request.kind,
                    python = request.python,
                    description = request.description,
                ),
            )
            if (!created) return@runInBackground

            for (dependent in request.dependents) {
                indicator.text = BasedPythonBundle.message("modules.progress.wiring", request.name, dependent)
                EnvOperations.runBlockingOp(
                    project,
                    backend,
                    EnvOp.Add(listOf(request.name), EnvDependencyTarget.Main, module = dependent),
                )
            }
        }
    }

    /**
     * Applies [edit] to [module]: the name first, then its metadata, then who depends on it.
     *
     * The order is forced by what each step reads. A rename moves the module's directory and changes
     * the name every other step is addressed to, so it goes first and the project is re-read
     * afterwards. Metadata is written before the uv commands run, because `uv add` re-reads the
     * manifest it is about to rewrite — an edit landing afterwards would be an edit to a file uv had
     * already replaced, and the last writer would win by accident.
     */
    fun apply(project: Project, module: ProjectModule, edit: ModuleEdit) {
        val service = EnvService.getInstance(project)
        val backend = service.status.backend ?: return
        val root = service.status.projectRoot ?: return

        EnvOperations.runInBackground(
            project,
            BasedPythonBundle.message("modules.progress.updating", module.name),
            // The module's directory and the one above it, rather than its manifest: a rename moves
            // the whole thing, and the parent is what has to be re-read for the IDE to see a
            // directory that is now called something else.
            extraFiles = listOfNotNull(module.root, module.root.parent, root.resolve(UvWorkspace.MANIFEST)),
        ) { indicator ->
            // The rename comes first and the project is re-read afterwards, because everything
            // below is addressed to a module whose name and directory it has just changed.
            val target = edit.newName
                ?.takeIf { ModuleNames.normalize(it) != module.key }
                ?.let { newName -> rename(project, backend, root, module, newName, indicator) ?: return@runInBackground }
                ?: module

            writeMetadata(project, target.root.resolve(UvWorkspace.MANIFEST), edit)

            val layout = backend.moduleLayout(root) ?: return@runInBackground
            val wanted = edit.dependents.map(ModuleNames::normalize).toSet()

            for (dependent in layout.dependents(target.name)) {
                if (dependent.key in wanted) continue
                indicator.text = BasedPythonBundle.message("modules.progress.unwiring", target.name, dependent.name)
                // Every list it is declared in, not just the main one: `uv remove` without the group
                // flag reports success having removed nothing.
                for (declaredIn in dependent.dependsOn(target.name)) {
                    EnvOperations.runBlockingOp(
                        project,
                        backend,
                        EnvOp.Remove(listOf(target.name), declaredIn, module = dependent.name),
                    )
                }
            }

            for (name in wanted) {
                val dependent = layout.byName(name) ?: continue
                if (dependent.dependsOn(target.name).isNotEmpty()) continue
                indicator.text = BasedPythonBundle.message("modules.progress.wiring", target.name, dependent.name)
                EnvOperations.runBlockingOp(
                    project,
                    backend,
                    EnvOp.Add(listOf(target.name), EnvDependencyTarget.Main, module = dependent.name),
                )
            }
        }
    }

    /**
     * Takes [module] out of the project, and its files with it when [deleteFiles].
     *
     * Three steps, in the only order that leaves nothing dangling: the siblings that declare it stop
     * declaring it, the root manifest stops listing it, and only then do the files go. Doing the
     * last one first would leave `uv remove` unable to resolve the workspace it is being asked to
     * edit.
     *
     * Un-listing is two different edits depending on how the module was listed, and the difference
     * is [ProjectModule.memberEntry]:
     *
     * - an **exact entry** is removed, since nothing else is named by it;
     * - a **glob** is left alone, because it names the module's siblings too. When the files are
     *   being deleted the glob stops matching by itself and there is nothing to do; when they are
     *   being kept, the path is added to `exclude`, which is uv's own way of saying "this directory
     *   is not a member" without changing what the glob means for anything else.
     */
    fun remove(project: Project, module: ProjectModule, deleteFiles: Boolean) {
        val service = EnvService.getInstance(project)
        val backend = service.status.backend ?: return
        val root = service.status.projectRoot ?: return
        if (module.isRoot) return

        EnvOperations.runInBackground(
            project,
            BasedPythonBundle.message("modules.progress.removing", module.name),
            extraFiles = listOf(module.root),
        ) { indicator ->
            val layout = backend.moduleLayout(root) ?: return@runInBackground
            for (dependent in layout.dependents(module.name)) {
                indicator.text = BasedPythonBundle.message("modules.progress.unwiring", module.name, dependent.name)
                for (target in dependent.dependsOn(module.name)) {
                    EnvOperations.runBlockingOp(
                        project,
                        backend,
                        EnvOp.Remove(listOf(module.name), target, module = dependent.name),
                    )
                }
            }

            unlist(project, root, module, deleteFiles)
            if (deleteFiles) deleteDirectory(project, module)
        }
    }

    /**
     * Renames [module] to [newName], and returns it as it is afterwards — or null when it could not
     * be done, in which case nothing has been changed.
     *
     * Six things have to change together, and the order below is the only one in which each step can
     * see a project that makes sense:
     *
     * 1. **The imports**, asked of `by` before anything moves. That is what the request is for, and
     *    it is the only moment it is answerable: the old path still holds the module, so the server
     *    can say which module it is. Nothing else happens if the server cannot answer — a rename
     *    that moves a directory and leaves every `import` naming the old one is a broken project
     *    made by a button that looked like it worked.
     * 2. **The siblings stop declaring it**, while it still exists under its old name. Doing this
     *    after the move would have uv resolving a workspace that names a member that is not there.
     * 3. **The directories move** — the import package first, then the module's own directory, since
     *    the first lives inside the second.
     * 4. **Its manifest** takes the new `[project] name`.
     * 5. **The root manifest's `members` entry** follows the directory, when it named it outright.
     * 6. **The siblings declare it again**, under the new name, which is also what reinstalls it.
     */
    private fun rename(
        project: Project,
        backend: EnvBackend,
        root: Path,
        module: ProjectModule,
        newName: String,
        indicator: com.intellij.openapi.progress.ProgressIndicator,
    ): ProjectModule? {
        indicator.text = BasedPythonBundle.message("modules.progress.renaming", module.name, newName)

        val plan = ModuleRenamePlan.of(module, newName) { Files.isDirectory(it) } ?: return null

        // 1. The imports, before anything is where it is not.
        if (ModuleImportEdits.applyFor(project, plan.moves()) == null) {
            report(project, BasedPythonBundle.message("modules.failed.imports"))
            return null
        }

        // 2. Un-declare it everywhere, while the workspace still resolves.
        val dependents = backend.moduleLayout(root)?.dependents(module.name).orEmpty()
        for (dependent in dependents) {
            indicator.text = BasedPythonBundle.message("modules.progress.unwiring", module.name, dependent.name)
            for (declaredIn in dependent.dependsOn(module.name)) {
                EnvOperations.runBlockingOp(
                    project,
                    backend,
                    EnvOp.Remove(listOf(module.name), declaredIn, module = dependent.name),
                )
            }
        }

        // 3. Move the directories.
        for (move in plan.moves()) {
            if (!moveDirectory(project, move)) return null
        }

        // 4 and 5. The two manifests that name it.
        val movedRoot = plan.moduleDirectory?.to ?: module.root
        writeManifest(project, movedRoot.resolve(UvWorkspace.MANIFEST)) { text ->
            TomlEdits.setString(text, PROJECT, "name", newName)
        }
        plan.memberEntry?.let { entry ->
            writeManifest(project, root.resolve(UvWorkspace.MANIFEST)) { text ->
                TomlEdits.addArrayItem(
                    TomlEdits.removeArrayItem(text, WORKSPACE, "members", entry.from),
                    WORKSPACE,
                    "members",
                    entry.to,
                )
            }
        }

        // 6. Declare it again, under the name it now has.
        for (dependent in dependents) {
            indicator.text = BasedPythonBundle.message("modules.progress.wiring", newName, dependent.name)
            EnvOperations.runBlockingOp(
                project,
                backend,
                EnvOp.Add(listOf(newName), EnvDependencyTarget.Main, module = dependent.name),
            )
        }

        return backend.moduleLayout(root)?.byName(newName)
    }

    /**
     * Moves one directory through the VFS, in a write action.
     *
     * Through the VFS rather than `java.nio` for the same reason a deletion is: open editors follow
     * the file, the indices are told, and the project view updates. A move that fails is reported and
     * stops the rename where it is — a half-moved module is not something to press on through.
     */
    private fun moveDirectory(project: Project, move: ModuleRenamePlan.Move): Boolean {
        val fs = LocalFileSystem.getInstance()
        val source = fs.refreshAndFindFileByNioFile(move.from) ?: return true
        val parent = fs.refreshAndFindFileByNioFile(move.to.parent ?: return false)
        val name = move.to.fileName?.toString() ?: return false

        return runCatching {
            WriteAction.runAndWait<Throwable> {
                if (parent != null && parent != source.parent) {
                    source.move(this, parent)
                }
                source.rename(this, name)
            }
            true
        }.getOrElse { failure ->
            report(
                project,
                BasedPythonBundle.message(
                    "modules.failed.move",
                    move.from.toString(),
                    move.to.toString(),
                    failure.message.orEmpty(),
                ),
            )
            false
        }
    }

    /** Rewrites [manifest] through [edit], leaving it alone when the edit changes nothing. */
    private fun writeManifest(project: Project, manifest: Path, edit: (String) -> String) {
        val original = runCatching { Files.readString(manifest) }.getOrNull() ?: return
        val updated = edit(original)
        if (updated == original) return
        runCatching { Files.writeString(manifest, updated) }.onFailure {
            report(
                project,
                BasedPythonBundle.message("modules.failed.manifest", manifest.toString(), it.message.orEmpty()),
            )
        }
    }

    // ---- the parts uv has no command for ------------------------------------

    /** Rewrites the module's own `[project]` metadata, when [edit] actually changes any of it. */
    private fun writeMetadata(project: Project, manifest: Path, edit: ModuleEdit) {
        val original = runCatching { Files.readString(manifest) }.getOrElse {
            report(project, BasedPythonBundle.message("modules.failed.manifest", manifest.toString(), it.message.orEmpty()))
            return
        }
        var updated = original
        updated = TomlEdits.setString(updated, PROJECT, "version", edit.version)
        updated = TomlEdits.setString(updated, PROJECT, "description", edit.description)
        updated = TomlEdits.setString(updated, PROJECT, "requires-python", edit.requiresPython)
        if (updated == original) return
        runCatching { Files.writeString(manifest, updated) }.onFailure {
            report(project, BasedPythonBundle.message("modules.failed.manifest", manifest.toString(), it.message.orEmpty()))
        }
    }

    /**
     * The root manifest with [module] no longer named by it.
     *
     * The decision, without the file it applies to: which of the two edits a removal needs — or
     * neither — is the part worth being sure of, and it is a function of three things that are all
     * on screen when the confirmation is shown. See [remove] for what each branch means.
     */
    fun unlisted(text: String, module: ProjectModule, deleteFiles: Boolean): String = when {
        module.memberEntry != null -> TomlEdits.removeArrayItem(text, WORKSPACE, "members", module.memberEntry)
        deleteFiles -> text
        else -> TomlEdits.addArrayItem(text, WORKSPACE, "exclude", module.relativePath)
    }

    /** Applies [unlisted] to the project's own manifest. */
    private fun unlist(project: Project, root: Path, module: ProjectModule, deleteFiles: Boolean) {
        val manifest = root.resolve(UvWorkspace.MANIFEST)
        val original = runCatching { Files.readString(manifest) }.getOrNull() ?: return
        val updated = unlisted(original, module, deleteFiles)
        if (updated == original) return
        runCatching { Files.writeString(manifest, updated) }.onFailure {
            report(project, BasedPythonBundle.message("modules.failed.manifest", manifest.toString(), it.message.orEmpty()))
        }
    }

    /**
     * Deletes the module's directory through the VFS.
     *
     * Through the VFS rather than `java.nio`, and in a write action, because the IDE has to be told:
     * open editors on files inside it are closed, the indices drop what was there, and the project
     * view updates. Deleting the files underneath the platform leaves it holding editors on files
     * that no longer exist.
     */
    private fun deleteDirectory(project: Project, module: ProjectModule) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(module.root) ?: return
        runCatching {
            WriteAction.runAndWait<Throwable> { file.delete(this) }
        }.onFailure {
            report(
                project,
                BasedPythonBundle.message("modules.failed.delete", module.root.toString(), it.message.orEmpty()),
            )
        }
    }

    private fun report(project: Project, message: String) {
        BasedPythonLogNotifications.create(
            project,
            BasedPythonBundle.message("modules.failed.title"),
            message,
            NotificationType.ERROR,
        ).notify(project)
    }

    /** True when [backend] can be asked to create a module at all — what the structure page needs. */
    fun isSupported(backend: EnvBackend?): Boolean = backend?.command(
        EnvOp.InitModule(path = "probe", name = null, kind = ModuleKind.LIBRARY),
    ) != null

    private val PROJECT = listOf("project")
    private val WORKSPACE = listOf("tool", "uv", "workspace")
}
