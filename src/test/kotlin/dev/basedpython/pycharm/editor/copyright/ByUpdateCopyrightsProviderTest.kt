package dev.basedpython.pycharm.editor.copyright

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.maddyhome.idea.copyright.CopyrightProfile
import com.maddyhome.idea.copyright.CopyrightUpdaters
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The notice the Copyright plugin writes at the top of a `.by` file, through the updater as the
 * plugin itself drives it: prepare, then complete in a write command.
 *
 * The notice is laid out the plugin's default way for line comments — a bare `#` above and below —
 * which is a *Copyright | Formatting* setting rather than anything decided here.
 */
@TestFixtures
class ByUpdateCopyrightsProviderTest {

    private val fixture by codeInsightFixture()

    private val profile = CopyrightProfile("demo").apply { notice = "Copyright (c) Demo" }

    private fun update(source: String): String {
        val file = fixture.configureByText("main.by", source)
        val updater = ByUpdateCopyrightsProvider().createInstance(
            fixture.project, fixture.module, file.virtualFile, BasedPythonFileType.INSTANCE, profile,
        )
        updater.prepare()
        WriteCommandAction.runWriteCommandAction(fixture.project) { updater.complete() }
        PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
        return fixture.editor.document.text
    }

    @Test
    fun `the copyright plugin finds an updater for basedpython`() {
        assertTrue(CopyrightUpdaters.INSTANCE.forFileType(BasedPythonFileType.INSTANCE) is ByUpdateCopyrightsProvider)
    }

    @Test
    fun `a notice goes above everything, the module docstring included`() = onEdt {
        assertEquals(
            "#\n# Copyright (c) Demo\n#\n\n\"\"\"The module.\"\"\"\nx = 1\n",
            update("\"\"\"The module.\"\"\"\nx = 1\n"),
        )
    }

    @Test
    fun `a shebang stays the first line`() = onEdt {
        assertEquals(
            "#!/usr/bin/env by\n\n#\n# Copyright (c) Demo\n#\n\nx = 1\n",
            update("#!/usr/bin/env by\nx = 1\n"),
        )
    }

    @Test
    fun `an old notice is replaced rather than added to`() = onEdt {
        assertEquals(
            "#\n# Copyright (c) Demo\n#\n\nx = 1\n",
            update("#\n# Copyright (c) Someone Else\n#\n\nx = 1\n"),
        )
    }
}
