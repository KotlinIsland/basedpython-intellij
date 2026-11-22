package dev.basedpython.pycharm.lsp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspCodeLensSupport
import com.intellij.platform.lsp.util.navigateOrShowPopup
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources
import dev.basedpython.pycharm.run.model.ByProgramModel
import java.awt.event.MouseEvent
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Location

private val LOG = Logger.getInstance(ByCodeLensSupport::class.java)

/**
 * `by`'s code lenses, with the one command that is the client's to run run by the client.
 *
 * `by` gives a lens that goes somewhere — django's *rendered by `blog.views.post`* above a template —
 * the command `editor.action.showReferences`, because LSP has no navigation command of its own and
 * that is the one VS Code registers and other clients emulate (see `SHOW_REFERENCES_COMMAND` in `by`'s
 * `code_lens.rs`). It is not `by`'s: the server does not list it in its `executeCommandProvider`, and
 * it could not carry it out if asked, since what it asks for is an editor showing places.
 *
 * The platform does not know it either, so it did what it does with every lens command and sent it
 * back as `workspace/executeCommand`, which `by` refused (`Invalid command
 * \`editor.action.showReferences\``) and the platform dropped: the lens did nothing. Here it is
 * handled where it belongs — the editor jumps to the one place, or offers the several — and every
 * other command goes to the server as before: a lens that runs `manage.py` is `by`'s to run.
 *
 * *run BookTest* and *run test*, the lenses that run a django test, are for an editor with no run icon
 * of its own and are not drawn where the gutter has one: `by` runs the test and says in a balloon how
 * it went, where the icon's run has the test tree, its tracebacks, rerun and the debugger. They stay in
 * a file `by/testItems` lists no test of, since nothing else there runs one: django's own `tests.py`
 * with a `by` that looks for pytest's file names (measured, 0.0.3 and 99f067bc2; 6ececa578 lists it).
 */
internal class ByCodeLensSupport(private val project: Project) : LspCodeLensSupport() {

    override fun shouldDisplayCodeLens(file: VirtualFile, codeLens: CodeLens): Boolean {
        if (!runsTest(codeLens.command) || !BasedPythonSources.isOwnedSource(file)) return true
        // not known yet, it is drawn: a second way to run a test is better than none
        return ByProgramModel.getInstance(project).testItems(file).isNullOrEmpty()
    }

    override fun codeLensClicked(lspClient: LspClient, contextFile: VirtualFile, command: Command, mouseEvent: MouseEvent?) {
        if (command.command != SHOW_REFERENCES) {
            super.codeLensClicked(lspClient, contextFile, command, mouseEvent)
            return
        }
        val locations = showReferencesLocations(command.arguments)
        if (locations == null) {
            // `by` wrote the arguments, so this is a server that changed what it sends
            LOG.warn("`$SHOW_REFERENCES` from `by` did not carry its locations: ${command.arguments}")
            return
        }
        navigateOrShowPopup(lspClient, locations, command.title, mouseEvent)
    }

    companion object {
        /** The command a navigating lens carries. */
        const val SHOW_REFERENCES: String = "editor.action.showReferences"

        /** The command of a lens that runs `manage.py`, which `by` carries out. */
        private const val RUN_MANAGE = "ty.runManageCommand"

        /** The `manage.py` command that runs tests. */
        private const val TEST = "test"

        /** Whether [command] is one of `by`'s that runs tests: `manage.py test …`, its arguments with it. */
        fun runsTest(command: Command?): Boolean {
            if (command?.command != RUN_MANAGE) return false
            val run = command.arguments?.firstOrNull() as? JsonObject ?: return false
            val first = (run.get("arguments") as? JsonArray)?.firstOrNull() as? JsonPrimitive
            return first?.isString == true && first.asString == TEST
        }

        /** Index of the locations in the command's arguments: after the document and the position. */
        private const val LOCATIONS_ARGUMENT = 2

        private val GSON = Gson()

        /**
         * The places a `showReferences` command offers, or `null` if [arguments] are not that
         * command's.
         *
         * The arguments are the document the lens is in, the position it sits at, and the locations
         * — VS Code's order, which `by` follows. They arrive as the JSON lsp4j read them as, since a
         * command's arguments are `any` in the protocol.
         */
        fun showReferencesLocations(arguments: List<Any?>?): List<Location>? {
            val locations = arguments?.getOrNull(LOCATIONS_ARGUMENT) as? JsonArray ?: return null
            return try {
                locations.map { it.toLocation() ?: return null }
            } catch (e: JsonParseException) {
                null
            }
        }

        private fun JsonElement.toLocation(): Location? {
            val location = GSON.fromJson(this, Location::class.java) ?: return null
            // Gson fills a missing field with null whatever Kotlin thinks of it
            @Suppress("SENSELESS_COMPARISON")
            if (location.uri == null || location.range == null || location.range.start == null) return null
            return location
        }
    }
}
