package dev.basedpython.pycharm.lsp.inlay

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** What a hint's right-click menu offers, and that choosing a mode there sets it. */
@TestFixtures
class ByHintMenuTest {

    private val fixture by codeInsightFixture()

    private val kind = ByHintKind("variableTypes")

    private fun location(line: Int) = Location("file:///p/builtins.byi", Range(Position(line, 0), Position(line, 4)))

    private val navigated = mutableListOf<Location>()
    private var accepted = 0

    /** `: dict[str, dict[str, int]]`, both `dict`s and both `str`s naming the same places. */
    private val label = ": dict[str, dict[str, int]]"
    private val links = listOf(
        ByHintLink(2, 6, location(1)),
        ByHintLink(7, 10, location(2)),
        ByHintLink(12, 16, location(1)),
        ByHintLink(17, 20, location(2)),
        ByHintLink(22, 25, location(3)),
    )

    private fun menu(accept: Boolean = true, linked: Boolean = true) = ByHintMenu(
        project = fixture.project,
        kind = kind,
        label = label,
        links = if (linked) links else emptyList(),
        navigate = if (linked) ({ navigated += it }) else null,
        accept = if (accept) ({ accepted++ }) else null,
        display = { _, _ -> },
    )

    private fun texts(menu: ByHintMenu): List<String> = menu.group().getChildren(ActionManager.getInstance()).map {
        if (it is Separator) "--${it.text.orEmpty()}" else it.templateText.orEmpty()
    }

    private fun event() = TestActionEvent.createTestEvent(SimpleDataContext.getProjectContext(fixture.project))

    @BeforeEach
    fun always() = onEdt { BasedPythonSettings.getInstance(fixture.project).setInlayMode(kind, ByHintMode.ALWAYS) }

    @Test
    fun `it offers inserting, each place once, copying, the kind's modes and its settings`() = onEdt {
        val texts = texts(menu())
        assertEquals("Insert into Code", texts[0])
        assertEquals(listOf("Go to dict", "Go to str", "Go to int"), texts.filter { it.startsWith("Go to") })
        assertTrue("Copy Hint Text" in texts)
        assertEquals(3, texts.count { it.endsWith("Show") || it.startsWith("Show While") })
        assertEquals("Inlay Hints Settings…", texts.last())
    }

    @Test
    fun `a hint with no edits and no places offers neither`() = onEdt {
        val texts = texts(menu(accept = false, linked = false))
        assertFalse(texts.any { it == "Insert into Code" || it.startsWith("Go to") })
    }

    @Test
    fun `insert and go to do what the double click and the Ctrl+click do`() = onEdt {
        val actions = menu().group().getChildren(ActionManager.getInstance())
        actions.first { it.templateText == "Insert into Code" }.actionPerformed(event())
        actions.first { it.templateText == "Go to str" }.actionPerformed(event())
        assertEquals(1, accepted)
        assertEquals(listOf(location(2)), navigated)
    }

    @Test
    fun `the kind's mode is the one ticked, and ticking another sets it`() = onEdt {
        val modes = menu().group().getChildren(ActionManager.getInstance()).filterIsInstance<ToggleAction>()
        val settings = BasedPythonSettings.getInstance(fixture.project)
        assertEquals(listOf(true, false, false), modes.map { it.isSelected(event()) })
        modes.last().setSelected(event(), true)
        assertEquals(ByHintMode.NEVER, settings.inlayMode(kind))
        assertEquals(listOf(false, false, true), modes.map { it.isSelected(event()) })
    }
}
