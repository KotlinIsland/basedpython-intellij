package dev.basedpython.pycharm.project

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptionsProcessor
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.reference.RefEntity
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.env.manager.EnvToolWindowFactory
import dev.basedpython.pycharm.lsp.diagnostics.ByDiagnosticsInspection
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.tasks.ByTaskToolWindowFactory
import dev.basedpython.pycharm.testFramework.asBasedPythonProject
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import dev.basedpython.pycharm.ui.log.BasedPythonLogToolWindowFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A plain Python project — a `pyproject.toml` that says nothing about basedpython, and no `.by`
 * sources — gets nothing of this plugin's that it did not ask for.
 *
 * Measured in PyCharm 263.7471 before: *Inspect Code* listed an ERROR under basedpython | by
 * diagnostics, "by did not check the project: no by binary was found", and the "basedpython",
 * "basedpython Tasks" and "basedpython Environment" tool windows were all offered.
 */
@TestFixtures
class PlainPythonProjectTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    /** Writes [names] at the project base on disk, through the VFS, and deletes them after [body]. */
    private fun withFilesAtBase(vararg names: String, body: () -> Unit) {
        val created = WriteAction.computeAndWait<List<VirtualFile>, RuntimeException> {
            val base = VfsUtil.createDirectories(project.basePath!!)
            names.filter { base.findChild(it) == null }.map { base.createChildData(this, it) }
        }
        try {
            body()
        } finally {
            WriteAction.runAndWait<RuntimeException> { created.forEach { it.delete(this) } }
        }
    }

    private fun windowsOffered(): List<Boolean> = listOf(
        BasedPythonLogToolWindowFactory().shouldBeAvailable(project),
        ByTaskToolWindowFactory().shouldBeAvailable(project),
        EnvToolWindowFactory().shouldBeAvailable(project),
    )

    /** What *Inspect Code* over the whole project gets from `by`'s diagnostics inspection. */
    private fun inspectCodeReports(): List<String> {
        val reported = mutableListOf<String>()
        val processor = object : ProblemDescriptionsProcessor {
            override fun addProblemElement(refEntity: RefEntity?, vararg descriptions: CommonProblemDescriptor) {
                descriptions.forEach { reported += it.descriptionTemplate }
            }
        }
        val manager = InspectionManager.getInstance(project) as InspectionManagerEx
        ByDiagnosticsInspection().runInspection(AnalysisScope(project), manager, manager.createNewGlobalContext(), processor)
        return reported
    }

    @Test
    fun `no basedpython tool window is offered to a plain Python project`() = onEdt {
        withFilesAtBase("pyproject.toml", ".pre-commit-config.yaml", "uv.lock") {
            assertEquals(listOf(false, false, false), windowsOffered())
        }
    }

    @Test
    fun `the same project marked basedpython is offered all three`() = onEdt {
        withFilesAtBase("pyproject.toml", ".pre-commit-config.yaml", "uv.lock") {
            asBasedPythonProject(project) {
                assertEquals(listOf(true, true, true), windowsOffered())
            }
        }
    }

    @Test
    fun `Inspect Code reports nothing from by in a plain Python project`() = onEdt {
        val settings = BasedPythonSettings.getInstance(project)
        val enabled = settings.state.byEnabled
        settings.byEnabled = true
        try {
            withFilesAtBase("pyproject.toml") {
                assertTrue(inspectCodeReports().isEmpty(), "there is nothing here for by to check")
            }
        } finally {
            settings.state.byEnabled = enabled
        }
    }

    @Test
    fun `Inspect Code in a basedpython project with no by says why it checked nothing`() = onEdt {
        asBasedPythonProject(project) {
            val reports = inspectCodeReports()
            assertFalse(reports.isEmpty(), "an empty list would read as a project with nothing wrong in it")
        }
    }
}
