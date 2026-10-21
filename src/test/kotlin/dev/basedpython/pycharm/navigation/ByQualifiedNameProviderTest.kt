package dev.basedpython.pycharm.navigation

import com.intellij.ide.actions.QualifiedNameProvider
import com.intellij.ide.actions.QualifiedNameProviderUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.psi.PsiElement
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor

/**
 * Copy Reference names a `.by` module or a package by `by`'s answer, and decides nothing about the
 * name itself. What is left here is handing that answer to the action — on the EDT, the one the
 * action's background update has just had, for the file where it still is.
 */
@TestFixtures
class ByQualifiedNameProviderTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `the provider comes first, so a package in a basedpython project is named by by`() = onEdt {
        assertInstanceOf(ByQualifiedNameProvider::class.java, QualifiedNameProvider.EP_NAME.extensionList.first())
    }

    @Test
    fun `Copy Reference on a by file in the Project view copies the name by gives it`() = onEdt {
        val file = fixture.addFileToProject("src/shapes/circle.by", "class Circle: ...\n")
        ByModuleNames.getInstance(fixture.project).remember(file.virtualFile, "shapes.circle")

        val (visible, copied) = copyReference(file)

        assertTrue(visible, "shown, rather than hidden as a relative path")
        assertEquals("shapes.circle", copied)
    }

    @Test
    fun `a package is named too`() = onEdt {
        val directory = fixture.addFileToProject("src/shapes/__init__.by", "").containingDirectory
        ByModuleNames.getInstance(fixture.project).remember(directory.virtualFile, "shapes")

        assertEquals("shapes", QualifiedNameProviderUtil.getQualifiedName(directory))
    }

    @Test
    fun `a file by gives no name keeps the action hidden, as it was`() = onEdt {
        val file = fixture.addFileToProject("scripts/run-me.by", "")
        ByModuleNames.getInstance(fixture.project).remember(file.virtualFile, null)

        assertFalse(copyReference(file).first)
    }

    @Test
    fun `a file moved since by named it is not named after where it was`() = onEdt {
        val file = fixture.addFileToProject("src/shapes/circle.by", "")
        ByModuleNames.getInstance(fixture.project).remember(file.virtualFile, "shapes.circle")
        val elsewhere = fixture.tempDirFixture.findOrCreateDir("src/other")
        WriteAction.run<Exception> { file.virtualFile.move(this, elsewhere) }

        assertNull(QualifiedNameProviderUtil.getQualifiedName(file))
    }

    @Test
    fun `a file of another language is left to its own providers`() = onEdt {
        val file = fixture.addFileToProject("notes.txt", "")
        assertNull(ByQualifiedNameProvider().getQualifiedName(file))
    }

    /** Copy Reference as the Project view runs it on [element]: whether it shows, and what it copies. */
    private fun copyReference(element: PsiElement): Pair<Boolean, String?> {
        val action = ActionManager.getInstance().getAction("CopyReference")
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, fixture.project)
            .add(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY, arrayOf(element))
            .add(CommonDataKeys.PSI_ELEMENT, element)
            .build()
        val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP, null)
        action.update(event)
        val visible = event.presentation.isEnabledAndVisible
        CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(""))
        if (visible) action.actionPerformed(event)
        return visible to CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
    }
}
