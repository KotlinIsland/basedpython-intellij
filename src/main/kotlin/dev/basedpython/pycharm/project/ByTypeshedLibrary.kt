package dev.basedpython.pycharm.project

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.BasedPythonIcons
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lang.dialect.ByTypeshedCache
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.Icon

/**
 * Tells the IDE that the stubs `by` navigates into are library files.
 *
 * Goto-definition on anything from the standard library lands in typeshed, which `by` carries inside
 * its own binary and extracts, file by file, into its cache — `~/.cache/ty/vendored/typeshed/<commit>`
 * on macOS and Linux, `%LOCALAPPDATA%\ty\cache\...` on Windows (`ty_ide`'s `cached_vendored_path`,
 * whose cache directory is `etcetera`'s XDG base strategy).
 *
 * Nothing about that says *library* to the IDE. The files are outside every content root, so they
 * are not project files; they are ordinary writable files on disk, so they are not read-only either.
 * Reader Mode's default — libraries and read-only files — therefore passed them over, which is why a
 * docstring in typeshed sat there as source while the same docstring in a Kotlin or Java library
 * renders on sight. Saying what these files actually are fixes that at the root rather than teaching
 * Reader Mode a special case, and the rest follows from it: they appear under External Libraries,
 * they join the "Project and Libraries" scope, and Navigate | File finds them.
 *
 * Editing one is meaningless — the extractor rewrites it from the binary — which is the other half
 * of why "library" is the honest description.
 *
 * The root has to exist to be one, and it comes into being the first time `by` extracts a stub. A
 * project opened before that ever happened picks it up on the next roots refresh.
 */
internal class ByTypeshedLibraryProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val root = typeshedRoot(project) ?: return emptyList()
        return listOf(ByTypeshedLibrary(root))
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        listOfNotNull(typeshedRoot(project))

    private fun typeshedRoot(project: Project): VirtualFile? {
        // A project with no basedpython in it has no business showing basedpython's stubs.
        if (!BasedPythonProjectDetector.isBasedPythonProject(project)) return null
        val path = ByTypeshedCache.root ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(path)?.takeIf { it.isDirectory }
    }
}

/**
 * The typeshed root as one library, named so it is recognisable in External Libraries.
 *
 * Every extracted commit lives under the one root, so a `by` upgrade needs no new entry: the old
 * stubs stay where they are, and both remain files nobody should be editing.
 */
private class ByTypeshedLibrary(private val root: VirtualFile) : SyntheticLibrary(), ItemPresentation {

    override fun getSourceRoots(): Collection<VirtualFile> = listOf(root)

    override fun getPresentableText(): String = BasedPythonBundle.message("library.typeshed")

    override fun getIcon(unused: Boolean): Icon = BasedPythonIcons.Logo

    override fun equals(other: Any?): Boolean = other is ByTypeshedLibrary && other.root == root

    override fun hashCode(): Int = root.hashCode()
}
