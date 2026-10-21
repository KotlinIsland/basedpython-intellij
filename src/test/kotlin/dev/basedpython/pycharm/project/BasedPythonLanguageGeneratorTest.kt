package dev.basedpython.pycharm.project

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** basedpython in the New Project wizard's list of languages, writing the project it lists. */
@TestFixtures
class BasedPythonLanguageGeneratorTest {

    private val fixture by codeInsightFixture()

    private val disposable = Disposer.newDisposable()

    @AfterEach
    fun cleanUp() = onEdt {
        Disposer.dispose(disposable)
        WriteAction.runAndWait<RuntimeException> {
            for (path in BasedPythonProjectScaffold.files(fixture.project.name).keys + "src") {
                LocalFileSystem.getInstance().findFileByNioFile(Path.of(fixture.project.basePath!!, path))?.delete(this)
            }
        }
    }

    private val generator: LanguageGeneratorNewProjectWizard
        get() = ExtensionPointName<LanguageGeneratorNewProjectWizard>("com.intellij.newProjectWizard.languageGenerator")
            .extensionList.single { it is BasedPythonLanguageGenerator }

    @Test
    fun `it is one of the wizard's languages, for a new project only`() = onEdt {
        assertEquals("basedpython", generator.name)
        assertTrue(generator.isEnabled(WizardContext(null, disposable)))
        // New Module, in a project that exists: the scaffold is a project's.
        assertFalse(generator.isEnabled(WizardContext(fixture.project, disposable)))
    }

    @Test
    fun `its step writes the project, and keeps the module the project has`() = onEdt {
        val project = fixture.project
        val modules = ModuleManager.getInstance(project).modules.toList()

        generator.createStep(RootNewProjectWizardStep(WizardContext(null, disposable))).setupProject(project)

        for (path in BasedPythonProjectScaffold.files(project.name).keys) {
            assertTrue(Files.isRegularFile(Path.of(project.basePath!!, path)), path)
        }
        assertEquals(modules, ModuleManager.getInstance(project).modules.toList())
    }
}
