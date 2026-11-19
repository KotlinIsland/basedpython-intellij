package dev.basedpython.pycharm.lang.dialect

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.service
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.ui.EditorNotificationProvider
import dev.basedpython.pycharm.onboarding.BasedPythonOptInBannerProvider
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.settings.app.BasedPythonAppSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Whether basedpython is used for a project's Python files, and the banner that asks. */
@TestFixtures
class BasedPythonOptInTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    private var welcomeShown: Boolean = false

    @BeforeEach
    fun remember() {
        welcomeShown = BasedPythonOptIn.welcomeShown()
    }

    @AfterEach
    fun restore() = onEdt {
        project.service<BasedPythonProjectOptIn>().choice = ProjectOptIn.UNDECIDED
        BasedPythonAppSettings.getInstance().pythonProjects = PythonProjectsDefault.ASK
        BasedPythonSettings.getInstance(project).loadState(BasedPythonSettings.State())
        PropertiesComponent.getInstance().setValue("dev.basedpython.pycharm.welcomeShown", welcomeShown)
    }

    @Test
    fun `a marker is used whatever was chosen`() {
        for (choice in ProjectOptIn.entries) for (default in PythonProjectsDefault.entries) {
            assertTrue(BasedPythonOptIn.uses(ProjectKind.BASEDPYTHON, choice, default), "$choice $default")
            assertFalse(BasedPythonOptIn.shouldAsk(ProjectKind.BASEDPYTHON, choice, default), "$choice $default")
        }
    }

    @Test
    fun `the project's own choice wins over the IDE's`() {
        for (kind in listOf(ProjectKind.PYTHON, ProjectKind.OTHER)) for (default in PythonProjectsDefault.entries) {
            assertTrue(BasedPythonOptIn.uses(kind, ProjectOptIn.USE, default), "$kind $default")
            assertFalse(BasedPythonOptIn.uses(kind, ProjectOptIn.SKIP, default), "$kind $default")
            assertFalse(BasedPythonOptIn.shouldAsk(kind, ProjectOptIn.USE, default), "$kind $default")
            assertFalse(BasedPythonOptIn.shouldAsk(kind, ProjectOptIn.SKIP, default), "$kind $default")
        }
    }

    @Test
    fun `always covers projects that look like python, and asks in the rest`() {
        assertTrue(BasedPythonOptIn.uses(ProjectKind.PYTHON, ProjectOptIn.UNDECIDED, PythonProjectsDefault.ALWAYS))
        assertFalse(BasedPythonOptIn.shouldAsk(ProjectKind.PYTHON, ProjectOptIn.UNDECIDED, PythonProjectsDefault.ALWAYS))
        // a Rust repository with a stray script
        assertFalse(BasedPythonOptIn.uses(ProjectKind.OTHER, ProjectOptIn.UNDECIDED, PythonProjectsDefault.ALWAYS))
        assertTrue(BasedPythonOptIn.shouldAsk(ProjectKind.OTHER, ProjectOptIn.UNDECIDED, PythonProjectsDefault.ALWAYS))
    }

    @Test
    fun `ask asks, and never neither uses nor asks`() {
        for (kind in listOf(ProjectKind.PYTHON, ProjectKind.OTHER)) {
            assertFalse(BasedPythonOptIn.uses(kind, ProjectOptIn.UNDECIDED, PythonProjectsDefault.ASK))
            assertTrue(BasedPythonOptIn.shouldAsk(kind, ProjectOptIn.UNDECIDED, PythonProjectsDefault.ASK))
            assertFalse(BasedPythonOptIn.uses(kind, ProjectOptIn.UNDECIDED, PythonProjectsDefault.NEVER))
            assertFalse(BasedPythonOptIn.shouldAsk(kind, ProjectOptIn.UNDECIDED, PythonProjectsDefault.NEVER))
        }
    }

    @Test
    fun `the banner asks above the project's python files until it is answered`() = onEdt {
        // the light project's base has no markers
        assertEquals(ProjectKind.OTHER, BasedPythonProjectDetector.kind(project))
        val py = fixture.addFileToProject("main.py", "").virtualFile
        val pyi = fixture.addFileToProject("main.pyi", "").virtualFile
        val text = fixture.addFileToProject("notes.txt", "").virtualFile

        assertNotNull(banner().collectNotificationData(project, py))
        assertNotNull(banner().collectNotificationData(project, pyi))
        assertNull(banner().collectNotificationData(project, text))
        // not the project's: a library's, or no file of it at all
        assertNull(banner().collectNotificationData(project, LightVirtualFile("scratch.py", "")))
        assertFalse(BasedPythonProjectDetector.isBasedPythonProject(project))

        BasedPythonOptIn.choose(project, ProjectOptIn.USE)
        assertNull(banner().collectNotificationData(project, py))
        assertTrue(BasedPythonProjectDetector.isBasedPythonProject(project))
        assertTrue(BasedPythonOptIn.welcomeShown())

        BasedPythonOptIn.choose(project, ProjectOptIn.SKIP)
        assertNull(banner().collectNotificationData(project, py))
        assertFalse(BasedPythonProjectDetector.isBasedPythonProject(project))
    }

    @Test
    fun `all projects reaches the project it was clicked in, even one the IDE's choice does not`() = onEdt {
        val py = fixture.addFileToProject("main.py", "").virtualFile

        BasedPythonOptIn.chooseForIdeFrom(project)

        assertEquals(PythonProjectsDefault.ALWAYS, BasedPythonAppSettings.getInstance().pythonProjects)
        // the light project does not look like python, so Always alone would have left it asking
        assertEquals(ProjectOptIn.USE, BasedPythonOptIn.projectChoice(project))
        assertNull(banner().collectNotificationData(project, py))
        assertTrue(BasedPythonProjectDetector.isBasedPythonProject(project))
    }

    @Test
    fun `never stops the asking, and a project with by off is not asked`() = onEdt {
        val py = fixture.addFileToProject("main.py", "").virtualFile

        BasedPythonOptIn.chooseForIde(PythonProjectsDefault.NEVER)
        assertNull(banner().collectNotificationData(project, py))

        BasedPythonOptIn.chooseForIde(PythonProjectsDefault.ASK)
        BasedPythonSettings.getInstance(project).byEnabled = false
        assertNull(banner().collectNotificationData(project, py))
    }

    @Test
    fun `the IDE's choice is stored by id, blank for ask`() {
        val settings = BasedPythonAppSettings.getInstance()
        settings.pythonProjects = PythonProjectsDefault.ALWAYS
        assertEquals("always", settings.state.pythonProjects)
        settings.pythonProjects = PythonProjectsDefault.ASK
        assertEquals("", settings.state.pythonProjects)
        assertEquals(PythonProjectsDefault.ASK, PythonProjectsDefault.fromId("nonsense"))
    }

    private fun banner(): BasedPythonOptInBannerProvider =
        EditorNotificationProvider.EP_NAME.getExtensions(project).filterIsInstance<BasedPythonOptInBannerProvider>().single()
}
