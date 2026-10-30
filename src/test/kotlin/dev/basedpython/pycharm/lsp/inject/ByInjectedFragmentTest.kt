package dev.basedpython.pycharm.lsp.inject

import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.general.LanguageInjectionContributor
import com.intellij.lang.injection.general.SimpleInjection
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lang.BasedPythonStringLiteral
import dev.basedpython.pycharm.lsp.isByServerFile
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.intellij.plugins.intelliLang.inject.TemporaryPlacesRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * basedpython injected into a basedpython string — *Inject language or reference* on one — is a
 * fragment the servers do not know: they are asked about the host file, never about the fragment,
 * whose offsets are not the host's.
 */
@TestFixtures
class ByInjectedFragmentTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `a fragment named as a by file is still no server's`() = onEdt {
        val contributor = LanguageInjectionContributor { context ->
            SimpleInjection(BasedPythonLanguage, "", "", TemporaryPlacesRegistry.SUPPORT_ID)
                .takeIf { context is BasedPythonStringLiteral }
        }
        LanguageInjectionContributor.INJECTOR_EXTENSION.addExplicitExtension(
            BasedPythonLanguage, contributor, fixture.testRootDisposable,
        )
        val q = "\"\"\""
        val source = "a = $q\nlet b = 1\n$q\n"
        val file = fixture.configureByText("main.by", source)
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            string("$q\nlet b = 1\n$q", text = listOf("\nlet b = 1\n"))
        }

        val injected = InjectedLanguageManager.getInstance(fixture.project)
            .findInjectedElementAt(file, source.indexOf("let"))?.containingFile
        val fragment = injected!!.virtualFile

        assertTrue(fragment is VirtualFileWindow, "$fragment")
        assertEquals("by", fragment.extension)
        assertFalse(fragment.isByServerFile())
    }
}
