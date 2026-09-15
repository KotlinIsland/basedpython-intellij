package dev.basedpython.pycharm.env.modules

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProgressIndicator
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
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Creating, changing and removing a module, as one gesture each.
 *
 * ### uv does what uv can do
 *
 * Every step that uv has a command for is uv's: `uv init` scaffolds the module *and* lists it in the
 * project's manifest, `uv add`/`uv remove` wire a module into its siblings and write the
 * `[tool.uv.sources] … { workspace = true }` entry that makes the dependency resolve locally, and
 * `uv version` sets a module's version. Only what uv has no command for is done by hand
 * ([TomlEdits]): un-listing a module, because uv adds a `members` entry and never takes one away,
 * and the rest of a module's metadata — its description and its `requires-python`.
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
     * and one without ends with the environment view reporting drift. The first command that fails
     * stops the rest; it has already said why.
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
            // And the manifest of every module about to declare it, which `uv add --package` rewrites.
            extraFiles = listOf(directory) + EnvOperations.manifestsOfModules(project, request.dependents),
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
                val wired = EnvOperations.runBlockingOp(
                    project,
                    backend,
                    EnvOp.Add(listOf(request.name), EnvDependencyTarget.Main, module = dependent),
                )
                if (!wired) return@runInBackground
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
     * already replaced, and the last writer would win by accident. The first step that fails stops
     * the rest.
     */
    fun apply(project: Project, module: ProjectModule, edit: ModuleEdit) {
        val service = EnvService.getInstance(project)
        val backend = service.status.backend ?: return
        val root = service.status.projectRoot ?: return
        // Every manifest this gesture can rewrite: the module's own, and every module that declares
        // it now or is about to. uv rewrites those behind the editor's back, so their unsaved edits
        // are flushed first and they are re-read afterwards.
        val touched = listOf(module.name) +
            service.status.modules?.dependents(module.name).orEmpty().map { it.name } +
            edit.dependents

        EnvOperations.runInBackground(
            project,
            BasedPythonBundle.message("modules.progress.updating", module.name),
            extraFiles = EnvOperations.manifestsOfModules(project, touched),
        ) { indicator ->
            // The rename comes first and the project is re-read afterwards, because everything
            // below is addressed to a module whose name and directory it has just changed.
            val target = edit.newName
                ?.takeIf { ModuleNames.normalize(it) != module.key }
                ?.let { newName ->
                    ModuleRename(ProjectIo(project, backend, root, indicator), root).rename(module, newName)
                        ?: return@runInBackground
                }
                ?: module

            val version = edit.version?.trim()?.takeIf { it.isNotEmpty() }
            if (version != null && version != target.version) {
                val set = EnvOperations.runBlockingOp(project, backend, EnvOp.SetVersion(version, module = target.name))
                if (!set) return@runInBackground
            }
            if (!writeMetadata(project, target.root.resolve(UvWorkspace.MANIFEST), edit)) return@runInBackground

            val layout = service.readModules(backend, root) ?: return@runInBackground
            val wanted = edit.dependents.map(ModuleNames::normalize).toSet()

            for (dependent in layout.dependents(target.name)) {
                if (dependent.key in wanted) continue
                indicator.text = BasedPythonBundle.message("modules.progress.unwiring", target.name, dependent.name)
                // Every list it is declared in, not just the main one: `uv remove` without the group
                // flag reports success having removed nothing.
                for (declaredIn in dependent.dependsOn(target.name)) {
                    val removed = EnvOperations.runBlockingOp(
                        project,
                        backend,
                        EnvOp.Remove(listOf(target.name), declaredIn, module = dependent.name),
                    )
                    if (!removed) return@runInBackground
                }
            }

            for (name in wanted) {
                val dependent = layout.byName(name) ?: continue
                if (dependent.dependsOn(target.name).isNotEmpty()) continue
                indicator.text = BasedPythonBundle.message("modules.progress.wiring", target.name, dependent.name)
                val added = EnvOperations.runBlockingOp(
                    project,
                    backend,
                    EnvOp.Add(listOf(target.name), EnvDependencyTarget.Main, module = dependent.name),
                )
                if (!added) return@runInBackground
            }
        }
    }

    /**
     * Takes [module] out of the project, and its files with it when [deleteFiles].
     *
     * Three steps, in the only order that leaves nothing dangling: the siblings that declare it stop
     * declaring it, the root manifest stops listing it, and only then do the files go. Doing the
     * last one first would leave `uv remove` unable to resolve the workspace it is being asked to
     * edit — and a sibling that could not be made to stop declaring it stops the whole removal, since
     * un-listing or deleting a module something still depends on leaves that something unresolvable.
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
        val dependents = service.status.modules?.dependents(module.name).orEmpty().map { it.name }

        EnvOperations.runInBackground(
            project,
            BasedPythonBundle.message("modules.progress.removing", module.name),
            extraFiles = EnvOperations.manifestsOfModules(project, dependents),
        ) { indicator ->
            val layout = service.readModules(backend, root) ?: return@runInBackground
            for (dependent in layout.dependents(module.name)) {
                indicator.text = BasedPythonBundle.message("modules.progress.unwiring", module.name, dependent.name)
                for (target in dependent.dependsOn(module.name)) {
                    val removed = EnvOperations.runBlockingOp(
                        project,
                        backend,
                        EnvOp.Remove(listOf(module.name), target, module = dependent.name),
                    )
                    if (!removed) return@runInBackground
                }
            }

            if (!unlist(project, root, module, deleteFiles)) return@runInBackground
            if (deleteFiles) deleteDirectory(project, module)
        }
    }

    /** [ModuleRename.Io] against the running IDE: the VFS, `by`, and the backend. */
    private class ProjectIo(
        private val project: Project,
        private val backend: EnvBackend,
        private val root: Path,
        private val indicator: ProgressIndicator,
    ) : ModuleRename.Io {

        override fun layout(): ModuleLayout? = EnvService.getInstance(project).readModules(backend, root)

        override fun isDirectory(path: Path): Boolean = Files.isDirectory(path)

        override fun exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

        override fun importEdits(moves: List<ModuleRenamePlan.Move>): ModuleRename.ImportEdits? =
            ModuleImportEdits.prepare(project, moves)

        override fun move(move: ModuleRenamePlan.Move): Boolean = moveDirectory(project, move)

        override fun read(file: Path): String? = ManifestDocuments.read(file)

        override fun write(file: Path, text: String?): Boolean = writeDocument(project, file, text)

        override fun run(op: EnvOp): Boolean = EnvOperations.runBlockingOp(project, backend, op)

        override fun report(message: String) = report(project, message)

        override fun progress(text: String) {
            indicator.text = text
        }
    }

    /**
     * Moves one directory through the VFS, in a write action.
     *
     * Through the VFS rather than `java.nio` for the same reason a deletion is: open editors follow
     * the file, the indices are told, and the project view updates. A move that fails is reported,
     * and false — the rename takes back what it had done.
     */
    private fun moveDirectory(project: Project, move: ModuleRenamePlan.Move): Boolean {
        val fs = LocalFileSystem.getInstance()
        return runCatching {
            val source = fs.refreshAndFindFileByNioFile(move.from) ?: error("${move.from} does not exist")
            val parent = fs.refreshAndFindFileByNioFile(requireNotNull(move.to.parent))
                ?: error("${move.to.parent} does not exist")
            val name = requireNotNull(move.to.fileName).toString()
            WriteAction.runAndWait<Throwable> {
                if (parent != source.parent) source.move(this, parent)
                if (source.name != name) source.rename(this, name)
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

    /** [ManifestDocuments.write], reporting a failure; true when the file says [text] afterwards. */
    private fun writeDocument(project: Project, file: Path, text: String?): Boolean =
        runCatching { ManifestDocuments.write(project, file, text) }
            .onFailure {
                report(project, BasedPythonBundle.message("modules.failed.manifest", file.toString(), it.message.orEmpty()))
            }
            .isSuccess

    /** Rewrites [manifest] through [change] and the VFS; true unless the file could not be read or written. */
    private fun editManifest(project: Project, manifest: Path, change: (String) -> String): Boolean {
        val original = ManifestDocuments.read(manifest) ?: run {
            report(project, BasedPythonBundle.message("modules.failed.manifest", manifest.toString(), "not found"))
            return false
        }
        val updated = change(original)
        return updated == original || writeDocument(project, manifest, updated)
    }

    // ---- the parts uv has no command for ------------------------------------

    /**
     * Rewrites the module's own `[project]` metadata that uv has no command for, when [edit]
     * actually changes any of it. The version is not among it: `uv version` sets that, and
     * [EditModuleDialog] does not let one be cleared.
     */
    private fun writeMetadata(project: Project, manifest: Path, edit: ModuleEdit): Boolean =
        editManifest(project, manifest) { text ->
            TomlEdits.setString(
                TomlEdits.setString(text, PROJECT, "description", edit.description),
                PROJECT,
                "requires-python",
                edit.requiresPython,
            )
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
    private fun unlist(project: Project, root: Path, module: ProjectModule, deleteFiles: Boolean): Boolean =
        editManifest(project, root.resolve(UvWorkspace.MANIFEST)) { unlisted(it, module, deleteFiles) }

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
