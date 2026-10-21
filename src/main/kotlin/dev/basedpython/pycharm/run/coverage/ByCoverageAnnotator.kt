package dev.basedpython.pycharm.run.coverage

import com.intellij.coverage.BaseCoverageAnnotator
import com.intellij.coverage.CoverageBundle
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import com.intellij.util.ui.JBUI
import java.awt.Graphics
import java.awt.Rectangle

/**
 * What a loaded `by run --coverage` report says, everywhere the IDE shows coverage: the percentages
 * in the Project view and the Coverage tool window, and the gutter of an open editor.
 *
 * Both are this plugin's own because the platform's are reachable only through internal API. Its
 * `SimpleCoverageAnnotator` counts a file only when `CoverageEngine.coverageProjectViewStatisticsApplicableTo`
 * says so, and its editor annotator needs `getQualifiedName` — both `@ApiStatus.Internal`, and
 * overriding one is internal API use (see docs/internal-api.md). Neither needs to know anything about
 * the language: a report names files by path and lines by number.
 */
@Service(Service.Level.PROJECT)
class ByCoverageAnnotator(private val project: Project) : BaseCoverageAnnotator(project), Disposable {

    /** Each file the report names, and each directory above one, by report key. */
    @Volatile
    private var summary: CoverageSummary = CoverageSummary.EMPTY

    /** The highlighters drawn into each open editor. Read and written on the EDT only. */
    private val drawn = HashMap<Editor, List<RangeHighlighter>>()

    override fun onSuiteChosen(newSuite: CoverageSuitesBundle?) {
        super.onSuiteChosen(newSuite)
        // called when a suite is opened, re-read and closed alike; whatever follows draws afresh
        summary = CoverageSummary.EMPTY
        ApplicationManager.getApplication().invokeLater(::eraseAll, project.disposed)
    }

    override fun createRenewRequest(suite: CoverageSuitesBundle, dataManager: CoverageDataManager): Runnable? {
        val data = suite.coverageData ?: return null
        return Runnable {
            summary = CoverageSummary.of(data)
            dataManager.triggerPresentationUpdate()
            ApplicationManager.getApplication().invokeLater({
                EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::draw)
            }, project.disposed)
        }
    }

    override fun getFileCoverageInformationString(
        project: Project,
        file: VirtualFile,
        currentSuite: CoverageSuitesBundle,
        manager: CoverageDataManager,
    ): String? = ByCoverageEngine.reportKeys(file).firstNotNullOfOrNull { summary.files[it] }?.let(::linesCovered)

    override fun getDirCoverageInformationString(
        project: Project,
        directory: VirtualFile,
        currentSuite: CoverageSuitesBundle,
        manager: CoverageDataManager,
    ): String? {
        val info = ByCoverageEngine.reportKeys(directory).firstNotNullOfOrNull { summary.directories[it] } ?: return null
        return CoverageBundle.message("coverage.view.text.files.covered", percent(info.coveredFilesCount, info.totalFilesCount)) +
            ", " + linesCovered(info)
    }

    /**
     * Draws the report's lines into [editor]'s gutter, replacing what was drawn there before.
     *
     * Nothing is drawn for a file changed since the run: its lines may no longer be the lines the
     * report numbered. Once drawn, a mark moves with its line as the file is edited.
     */
    internal fun draw(editor: Editor) {
        erase(editor)
        val bundle = activeBundle() ?: return
        val data = bundle.coverageData ?: return
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (FileDocumentManager.getInstance().isFileModified(file) || file.timeStamp > bundle.lastCoverageTimeStamp) return
        val classData = ByCoverageEngine.reportKeys(file).firstNotNullOfOrNull(data::getClassData) ?: return
        val markup = editor.markupModel
        drawn[editor] = classData.lines.filterIsInstance<LineData>()
            .filter { it.lineNumber in 1..document.lineCount }
            .map { line ->
                markup.addLineHighlighter(line.lineNumber - 1, HighlighterLayer.SELECTION - 1, null).also {
                    it.lineMarkerRenderer = CoverageStripe(
                        if (line.hits > 0) CodeInsightColors.LINE_FULL_COVERAGE else CodeInsightColors.LINE_NONE_COVERAGE,
                    )
                }
            }
    }

    /** Forgets [editor], whose markup goes with it. */
    internal fun released(editor: Editor) {
        drawn.remove(editor)
    }

    private fun activeBundle(): CoverageSuitesBundle? =
        CoverageDataManager.getInstance(project).activeSuites().firstOrNull { it.coverageEngine is ByCoverageEngine }

    private fun erase(editor: Editor) {
        drawn.remove(editor)?.forEach(RangeHighlighter::dispose)
    }

    private fun eraseAll() {
        drawn.values.forEach { highlighters -> highlighters.forEach(RangeHighlighter::dispose) }
        drawn.clear()
    }

    /**
     * Takes this plugin's marks out of every editor, so none outlives it when it is unloaded.
     *
     * Unloading disposes on the EDT, where markup may be changed. A project closing may dispose
     * elsewhere, and there the editors go with the project, marks and all.
     */
    override fun dispose() {
        if (ApplicationManager.getApplication().isDispatchThread) eraseAll()
    }

    /**
     * The opening of an editor on a file the report names, which is drawn into straight away.
     *
     * Only where a report has been loaded: the annotator exists once the platform has asked the
     * engine for it, and an editor opened in a project that never ran with coverage creates nothing.
     */
    class EditorOpened : EditorFactoryListener {
        override fun editorCreated(event: EditorFactoryEvent) {
            val project = event.editor.project ?: return
            project.getServiceIfCreated(ByCoverageAnnotator::class.java)?.draw(event.editor)
        }

        override fun editorReleased(event: EditorFactoryEvent) {
            val project = event.editor.project ?: return
            project.getServiceIfCreated(ByCoverageAnnotator::class.java)?.released(event.editor)
        }
    }

    companion object {
        fun getInstance(project: Project): ByCoverageAnnotator = project.service()

        private fun linesCovered(info: FileCoverageInfo): String =
            CoverageBundle.message("coverage.view.text.lines.covered", percent(info.coveredLineCount, info.totalLineCount))

        /** Rounded down, as the platform's own figures are, so a file is 100% only when it is. */
        private fun percent(covered: Int, total: Int): Int = if (total == 0) 100 else (covered * 100L / total).toInt()
    }
}

/** The stripe the platform's own coverage gutter draws, in the colour scheme's coverage colours. */
private class CoverageStripe(private val key: TextAttributesKey) : LineMarkerRendererEx {
    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
        val attributes = editor.colorsScheme.getAttributes(key) ?: return
        g.color = attributes.backgroundColor ?: attributes.foregroundColor ?: return
        g.fillRect(r.x, r.y, minOf(r.width, JBUI.scale(STRIPE_WIDTH)), r.height)
    }

    override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.LEFT

    private companion object {
        const val STRIPE_WIDTH = 8
    }
}

/**
 * A report reduced to what the Project view and the Coverage tool window show: lines covered per
 * file, and files and lines covered per directory, every directory above a file included.
 */
internal class CoverageSummary private constructor(
    val files: Map<String, BaseCoverageAnnotator.FileCoverageInfo>,
    val directories: Map<String, BaseCoverageAnnotator.DirCoverageInfo>,
) {
    companion object {
        val EMPTY = CoverageSummary(emptyMap(), emptyMap())

        fun of(data: ProjectData): CoverageSummary {
            val files = HashMap<String, BaseCoverageAnnotator.FileCoverageInfo>()
            val directories = HashMap<String, BaseCoverageAnnotator.DirCoverageInfo>()
            for ((path, classData) in data.classes) {
                val lines = classData.lines.filterIsInstance<LineData>()
                // a file with no statements, like an empty `__init__.py`, can be neither covered
                // nor missed, and counting it would pull a directory's share of covered files down
                if (lines.isEmpty()) continue
                val file = BaseCoverageAnnotator.FileCoverageInfo().apply {
                    totalLineCount = lines.size
                    coveredLineCount = lines.count { it.hits > 0 }
                }
                files[path] = file
                var directory = path.substringBeforeLast('/', "")
                while (directory.isNotEmpty()) {
                    directories.getOrPut(directory, BaseCoverageAnnotator::DirCoverageInfo).apply {
                        totalFilesCount++
                        if (file.coveredLineCount > 0) coveredFilesCount++
                        totalLineCount += file.totalLineCount
                        coveredLineCount += file.coveredLineCount
                    }
                    directory = directory.substringBeforeLast('/', "")
                }
            }
            return CoverageSummary(files, directories)
        }
    }
}
