package dev.basedpython.pycharm.lsp

import com.intellij.openapi.vfs.VirtualFile

/**
 * Which files the `by` server serves as django templates.
 *
 * The server does far more with a template than an HTML grammar can — completions for tag and
 * filter names, `{{ book.‸ }}` off the model's own fields, go-to-definition on `{% extends %}`,
 * `{% url %}` and `{% block %}`, and template diagnostics — but none of it happens unless the
 * *client* hands the file over. Nothing else claims `.html` for the language server, so this is
 * the whole gate.
 *
 * The rule matches the one the server itself documents: one of these extensions, somewhere under a
 * directory named `templates`. That is also django's own app-directories loader convention, and it
 * is what keeps this narrow — `.html` is the most common file extension there is, and claiming
 * every one of them would put the type checker in front of files that have nothing to do with it.
 * Callers additionally require a basedpython project, so an unrelated Django or Jinja repo is
 * never touched.
 *
 * ### why this is duplicated here rather than asked of the server
 *
 * The obvious objection is that the server already knows, so the client should ask. It cannot:
 *
 * - LSP has no "do you serve this document?" request. The *client* owns the document set and
 *   decides what to send; the only file scoping in the protocol is a `documentSelector` on a
 *   **dynamic** registration.
 * - `by` registers nothing dynamically for text documents. Probed against 0.0.1: the `initialize`
 *   result carries `capabilities` and `serverInfo` and nothing else — no `experimental` block — and
 *   the one `client/registerCapability` it sends is `workspace/didChangeWatchedFiles` with
 *   `globPattern: "**"`, which watches everything and so says nothing about what it serves.
 * - Even a perfect answer would come too late. This predicate is what decides whether to *start* a
 *   server at all, so with only a template open there is nobody to ask.
 *
 * The cost of duplicating it is a rule that can drift from the server's, and one case it already
 * gets wrong: a project whose `TEMPLATES[*]["DIRS"]` points somewhere not called `templates` is
 * served by the server but not offered by this. Fixing that properly needs the server to advertise
 * its document selector — worth asking upstream for, and the point at which this file should shrink
 * to a bootstrap rule.
 */
object ByTemplateFiles {

    /** Extensions django's own template loaders and the server both recognise. */
    val EXTENSIONS: Set<String> = setOf("html", "htm", "txt", "xml", "django", "dj")

    private const val TEMPLATES_DIR = "templates"

    /**
     * Pure form: [extension] is the file's, [ancestorNames] the names of the directories above it
     * in any order.
     */
    fun isTemplate(extension: String?, ancestorNames: List<String>): Boolean =
        extension?.lowercase() in EXTENSIONS &&
            ancestorNames.any { it.equals(TEMPLATES_DIR, ignoreCase = true) }

    /** Whether [file] is a django template, by the rule above. */
    fun isTemplate(file: VirtualFile): Boolean =
        isTemplate(file.extension, ancestorNames(file))

    private fun ancestorNames(file: VirtualFile): List<String> {
        val names = mutableListOf<String>()
        var dir = file.parent
        while (dir != null) {
            names += dir.name
            dir = dir.parent
        }
        return names
    }
}
