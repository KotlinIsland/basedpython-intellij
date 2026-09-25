package dev.basedpython.pycharm.lsp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspCodeLensSupport
import com.intellij.platform.lsp.util.navigateOrShowPopup
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Location
import java.awt.event.MouseEvent

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
 */
internal class ByCodeLensSupport : LspCodeLensSupport() {

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
