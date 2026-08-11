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
