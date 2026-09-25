package dev.basedpython.pycharm.lsp

import com.google.gson.JsonParser
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Reading the `editor.action.showReferences` command `by` puts on a navigating code lens. */
class ByCodeLensSupportTest {

    /**
     * The arguments as lsp4j hands them over: JSON elements, since a command's arguments are `any`.
     * The text is what `by` sent for a template two views render, as recorded from a live session.
     */
    private fun arguments(json: String): List<Any?> = JsonParser.parseString(json).asJsonArray.toList()

    @Test
    fun `the locations are the third argument`() {
        val locations = ByCodeLensSupport.showReferencesLocations(
            arguments(
                """
                ["file:///p/blog/templates/blog/post.html", {"character": 0, "line": 0}, [
                  {"range": {"end": {"character": 8, "line": 2}, "start": {"character": 4, "line": 2}}, "uri": "file:///p/blog/views.py"},
                  {"range": {"end": {"character": 11, "line": 5}, "start": {"character": 4, "line": 5}}, "uri": "file:///p/blog/views.py"}
                ]]
                """,
            ),
        )
        assertEquals(
            listOf(
                Location("file:///p/blog/views.py", Range(Position(2, 4), Position(2, 8))),
                Location("file:///p/blog/views.py", Range(Position(5, 4), Position(5, 11))),
            ),
            locations,
        )
    }

    @Test
    fun `a command without its locations is not read as one with none`() {
        // an empty list would be a lens that navigates nowhere, which `by` never sends; a missing one
        // is a server that changed what it sends, and has to be told apart from it
        assertNull(ByCodeLensSupport.showReferencesLocations(arguments("""["file:///p/a.html", {"character": 0, "line": 0}]""")))
        assertNull(ByCodeLensSupport.showReferencesLocations(null))
    }

    @Test
    fun `a location missing its range is refused rather than navigated to the top of the file`() {
        assertNull(
            ByCodeLensSupport.showReferencesLocations(
                arguments("""["file:///p/a.html", {"character": 0, "line": 0}, [{"uri": "file:///p/views.py"}]]"""),
            ),
        )
    }
}
