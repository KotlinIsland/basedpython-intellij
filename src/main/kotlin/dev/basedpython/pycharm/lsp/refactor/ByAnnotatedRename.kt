package dev.basedpython.pycharm.lsp.refactor

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByExperimental
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByNamedRenameParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * Renames that go with a rename and that the user confirms — overriding methods, the same parameter
 * of each, the module named after a class — as `by` groups them under one change annotation label:
 * [label] is that label (*Rename overriding methods*), [descriptions] what each annotation of the
 * group says it renames, [edit] the group's text edits as plain `changes`, and [fileRenames] the
 * files it moves, made after every text edit.
 */
internal data class ByRenameGroup(
    val label: String,
    val descriptions: List<String>,
    val edit: WorkspaceEdit,
    val fileRenames: List<RenameFile>,
)

/**
 * A rename as `by/rename` answers it: the [rename] itself, which is always made, and the [groups]
 * made only when the user keeps them, in the order `by` first lists them.
 */
internal data class ByAnnotatedRename(val rename: WorkspaceEdit, val groups: List<ByRenameGroup>) {

    /** The groups to make, for [kept] — the labels the user chose — and none other. */
    fun keeping(kept: Set<String>): List<ByRenameGroup> = groups.filter { it.label in kept }

    companion object {

        /**
         * Asks `by/rename` for renaming the symbol at [offset] of [document] to [newName]. Only of a
         * `by` that advertises `experimental.renameChangeAnnotations`: for any other the answer is
         * [ByAnswer.Failed] without asking, and the caller asks `textDocument/rename`, which has no
         * groups. An answer that is not a workspace edit this can read is [ByAnswer.Failed] too:
         * applying part of it could leave a group half made, or make one nobody kept.
         */
        @RequiresBackgroundThread
        @RequiresReadLock
        fun ask(client: LspClient, project: Project, file: VirtualFile, document: Document, offset: Int, newName: String): ByAnswer<ByAnnotatedRename> {
            if (!advertised(client)) return ByAnswer.Failed
            ByServerDocuments.ensureOpen(client, project, file)
            val params = ByNamedRenameParams(
                TextDocumentIdentifier(client.getDocumentIdentifier(file).uri),
                getLsp4jPosition(document, offset),
                newName,
                ByTextHash.of(document.immutableCharSequence),
            )
            return when (val answer = client.askBy(METHOD) { (it as ByServerExtensions).annotatedRename(params) }) {
                ByAnswer.None -> ByAnswer.None
                ByAnswer.Failed -> ByAnswer.Failed
                is ByAnswer.Answer -> answer.value.takeUnless { it.isJsonNull }?.let(::parse)?.let { ByAnswer.Answer(it) } ?: ByAnswer.Failed
            }
        }

        /** Whether [client] answers [METHOD]. */
        fun advertised(client: LspClient): Boolean = ByExperimental.advertises(client, ByExperimental.RENAME_CHANGE_ANNOTATIONS)

        /** The request, named once. */
        const val METHOD: String = "by/rename"

        /**
         * [json], a `WorkspaceEdit` with `documentChanges` and `changeAnnotations` as LSP shapes them,
         * read into the rename and its groups; null when it is not one this can make whole.
         *
         * An edit or a file move is in a group when its `annotationId` names an annotation with
         * `needsConfirmation`, and is part of the rename otherwise, as LSP says a client applies an
         * edit whose annotation asks for nothing. An `annotationId` naming no annotation, a file
         * created or deleted, or an edit without a range is not something to guess about.
         */
        fun parse(json: JsonElement): ByAnnotatedRename? = runCatching { read(json.asJsonObject) }.getOrNull()

        private val gson = Gson()

        private class Annotation(val label: String, val description: String?, val confirm: Boolean)

        private class Collecting(val label: String) {
            val descriptions = LinkedHashSet<String>()
            val changes = LinkedHashMap<String, MutableList<TextEdit>>()
            val fileRenames = ArrayList<RenameFile>()
        }

        private fun read(edit: JsonObject): ByAnnotatedRename? {
            val annotations = edit.getAsJsonObject("changeAnnotations")?.entrySet().orEmpty().associate { (id, value) ->
                val annotation = value.asJsonObject
                id to Annotation(
                    label = annotation.get("label").asString,
                    description = annotation.get("description")?.takeUnless { it.isJsonNull }?.asString,
                    confirm = annotation.get("needsConfirmation")?.takeUnless { it.isJsonNull }?.asBoolean == true,
                )
            }
            val rename = LinkedHashMap<String, MutableList<TextEdit>>()
            val renameFiles = ArrayList<RenameFile>()
            val groups = LinkedHashMap<String, Collecting>()

            /** The group an `annotationId` puts a change in, or null for the rename itself; throws for an unknown id. */
            fun groupOf(id: JsonElement?): Collecting? {
                if (id == null || id.isJsonNull) return null
                val annotation = annotations[id.asString] ?: error("annotation ${id.asString} is not in changeAnnotations")
                if (!annotation.confirm) return null
                return groups.getOrPut(annotation.label) { Collecting(annotation.label) }.also { group ->
                    annotation.description?.let(group.descriptions::add)
                }
            }

            edit.getAsJsonObject("changes")?.entrySet()?.forEach { (uri, edits) ->
                edits.asJsonArray.forEach { rename.getOrPut(uri) { ArrayList() } += textEdit(it.asJsonObject) }
            }
            edit.getAsJsonArray("documentChanges")?.forEach { element ->
                val change = element.asJsonObject
                when (change.get("kind")?.asString) {
                    null -> {
                        val uri = change.getAsJsonObject("textDocument").get("uri").asString
                        for (each in change.getAsJsonArray("edits")) {
                            val text = each.asJsonObject
                            val into = groupOf(text.get("annotationId"))?.changes ?: rename
                            into.getOrPut(uri) { ArrayList() } += textEdit(text)
                        }
                    }
                    "rename" -> {
                        val move = RenameFile(change.get("oldUri").asString, change.get("newUri").asString)
                        (groupOf(change.get("annotationId"))?.fileRenames ?: renameFiles) += move
                    }
                    else -> return null
                }
            }
            // a move of the rename's own is not one a rename makes, and nothing here would make it
            if (renameFiles.isNotEmpty()) return null
            return ByAnnotatedRename(
                WorkspaceEdit(rename.mapValues { it.value.toList() }),
                groups.values.map { it.toGroup() },
            )
        }

        private fun Collecting.toGroup() = ByRenameGroup(
            label,
            descriptions.toList(),
            WorkspaceEdit(changes.mapValues { it.value.toList() }),
            fileRenames.toList(),
        )

        private fun textEdit(json: JsonObject): TextEdit =
            TextEdit(checkNotNull(gson.fromJson(json.get("range"), Range::class.java)), json.get("newText").asString)
    }
}
