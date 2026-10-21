package dev.basedpython.pycharm.run.coverage

import com.intellij.coverage.CoverageAnnotator
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.view.CoverageViewExtension
import com.intellij.coverage.view.DirectoryCoverageViewExtension
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfiguration

/**
 * *Run with Coverage* for `by run` and test configurations.
 *
 * The measuring and the mapping are `by`'s: `by run --coverage <file>` runs the program under
 * coverage.py and writes an LCOV report whose transpiled modules are already their `.by` files and
 * lines (see [ByCoverageProgramRunner]). Nothing here reads a sourcemap; the report is loaded as it
 * is ([ByCoverageRunner]) and every file in it is named by its own path.
 *
 * The editor's gutter is drawn by [ByCoverageAnnotator], not by the platform. The platform's editor
 * annotator finds a file's data through `getCorrespondingOutputPaths` and `getQualifiedName`, both
 * `@ApiStatus.Internal` — overriding them is internal API use (see docs/internal-api.md) — and
 * without them it finds none, so [coverageEditorHighlightingApplicableTo] keeps it out of the way.
 */
class ByCoverageEngine : CoverageEngine() {

    override fun getPresentableText(): String = "basedpython Coverage"

    override fun isApplicableTo(conf: RunConfigurationBase<*>): Boolean = measurable(conf)

    override fun createCoverageEnabledConfiguration(conf: RunConfigurationBase<*>): CoverageEnabledConfiguration =
        ByCoverageEnabledConfiguration(conf)

    override fun createCoverageSuite(
        name: String,
        project: Project,
        runner: CoverageRunner,
        fileProvider: CoverageFileProvider,
        timestamp: Long,
    ): CoverageSuite = ByCoverageSuite(name, project, runner, fileProvider, timestamp)

    override fun createCoverageSuite(
        name: String,
        project: Project,
        runner: CoverageRunner,
        fileProvider: CoverageFileProvider,
        timestamp: Long,
        config: CoverageEnabledConfiguration,
    ): CoverageSuite = ByCoverageSuite(name, project, runner, fileProvider, timestamp)

    override fun createEmptyCoverageSuite(coverageRunner: CoverageRunner): CoverageSuite = ByCoverageSuite()

    override fun getCoverageAnnotator(project: Project): CoverageAnnotator = ByCoverageAnnotator.getInstance(project)

    override fun coverageEditorHighlightingApplicableTo(psiFile: PsiFile): Boolean = false

    override fun getQualifiedNames(sourceFile: PsiFile): Set<String> =
        sourceFile.virtualFile?.let(::reportKeys).orEmpty()

    override fun createCoverageViewExtension(project: Project, suiteBundle: CoverageSuitesBundle): CoverageViewExtension =
        DirectoryCoverageViewExtension(project, getCoverageAnnotator(project), suiteBundle)

    internal companion object {
        /**
         * Whether [conf] runs through `by run`, which is what measures.
         *
         * A test configuration that runs plain `python -m pytest` does not: it never starts `by`.
         */
        fun measurable(conf: RunConfigurationBase<*>): Boolean =
            conf is ByRunConfiguration || (conf is ByTestConfiguration && !conf.options.plainPytest)

        /**
         * The names [file] may have in a loaded report: its path, and its canonical path when that
         * differs.
         *
         * Spelled the way the platform's LCOV reader keys a report — system-independent, and
         * lower-cased on Windows, where paths are not case-sensitive.
         */
        fun reportKeys(file: VirtualFile): Set<String> =
            setOfNotNull(file.path, file.canonicalPath).mapTo(linkedSetOf(), ::reportKey)

        fun reportKey(path: String): String {
            val independent = FileUtil.toSystemIndependentName(path)
            return if (SystemInfo.isWindows) independent.lowercase() else independent
        }
    }
}
