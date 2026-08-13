package dev.basedpython.pycharm.run.test.node

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.run.test.ByDeclarationPath
import dev.basedpython.pycharm.run.test.ByTestDeclarations
import dev.basedpython.pycharm.run.test.tree.ByTestSources
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * The one answer to "is this declaration a test?", shared by everything that acts on it.
 *
 * The gutter icon ([dev.basedpython.pycharm.run.testmarker.ByTestRunLineMarkerContributor]) and the
 * configuration behind the click ([dev.basedpython.pycharm.run.ByTestFromFileProducer]) have to
 * agree: an icon whose producer declines leaves a green arrow that runs the wrong thing — the whole
 * module, via the plain `by run` producer — and a producer that fires where no icon is drawn is
 * reachable from the context menu and nowhere else.
 */
internal object ByTestLookup {

    /** What the last collection has to say about one declaration. */
    sealed interface Verdict {
        /** Not a test: pytest did not collect it, or nothing known suggests it is one. */
        data object NotATest : Verdict

        /** Named like a test, with no collection in a position to confirm or deny it. */
        data object Unknown : Verdict

        /** Collected: [count] tests sit at or under the declaration. */
        data class Tests(val count: Int) : Verdict
    }

    /** The verdict for [declaration] in [file], from whatever is known right now. */
    fun verdict(project: Project, file: VirtualFile, declaration: ByDeclarationPath): Verdict =
        verdict(
            index = ByTestNodeService.getInstance(project).index,
            path = ByTestSources.relativePath(project, file),
            declaration = declaration,
            fileChangedSinceCollection = changedSinceCollection(project, file),
        )

    /**
     * Starts a collection if none has run, so that the answers stop being guesses.
     *
     * Called by the gutter markers, which is the moment the question is actually being asked of a
     * file the user is looking at. Deliberately *not* called from
     * [dev.basedpython.pycharm.run.ByTestFromFileProducer]: producers run while a context menu or
     * the Run Anything popup is being built, and spawning a subprocess there is a side effect
     * nobody asked for.
     *
     * Asked for rather than waited for. The collection runs in the background and its arrival
     * restarts the daemon ([ByTestNodeService.setState]), so a gutter that painted the naming
     * convention a second ago redraws itself with pytest's answer. And asked at the first question
     * rather than at project open, because collecting is not a free cache warm-up: pytest *imports*
     * every test module, so this runs a project's `conftest.by` — worth doing for a project being
     * worked in, not for every project that happens to be opened.
     */
    fun ensureCollected(project: Project) {
        val service = ByTestNodeService.getInstance(project)
        // `refreshIfNeeded` is what keeps this to once, a failed collection included: an error is a
        // result, not a reason to try again on the next keystroke.
        if (service.index === ByTestIndex.EMPTY && BasedPythonSettings.getInstance(project).byEnabled) {
            service.refreshIfNeeded()
        }
    }

    /**
     * The rule, given everything already resolved.
     *
     * In order:
     *
     *  1. the collection names this declaration — it is a test, and how many;
     *  2. the collection names *other* tests in this file — it swept the file and passed this
     *     declaration over, so it is not one;
     *  3. the collection swept the whole project without naming the file, and the file has not
     *     changed since — then the file has no tests pytest would run, whatever its declarations
     *     are called. This is the case of a `def test_x` sitting in `main.by`: pytest collects
     *     nothing there, so an icon offering to run it would be offering nothing;
     *  4. otherwise nothing is known — no collection yet, one that was interrupted or found
     *     nothing, or a file edited since — and the name is the only evidence left.
     *
     * Step 3 is what step 4 must not swallow: falling back whenever a file is merely absent would
     * put every uncollectable `def test_…` back on the gutter, which is the guess this whole path
     * exists to replace.
     */
    fun verdict(
        index: ByTestIndex,
        path: String?,
        declaration: ByDeclarationPath,
        fileChangedSinceCollection: Boolean,
    ): Verdict {
        if (path != null) {
            index.testsAt(path, declaration.symbols)?.let { return Verdict.Tests(it) }
            if (index.knows(path)) return Verdict.NotATest
            if (index.isComplete && !fileChangedSinceCollection) return Verdict.NotATest
        }
        return if (ByTestDeclarations.isConventionalTest(declaration)) Verdict.Unknown
        else Verdict.NotATest
    }

    /**
     * True when [file] may hold tests the last collection could not have seen.
     *
     * Two ways that happens, and both have to count: the file was written to disk after the
     * collection read it, or it has edits that were never saved at all — collection runs `by` over
     * the files on disk, so an unsaved buffer is invisible to it by definition.
     */
    private fun changedSinceCollection(project: Project, file: VirtualFile): Boolean {
        val index = ByTestNodeService.getInstance(project).index
        return FileDocumentManager.getInstance().isFileModified(file) ||
            file.timeStamp > index.takenAtMillis
    }
}
