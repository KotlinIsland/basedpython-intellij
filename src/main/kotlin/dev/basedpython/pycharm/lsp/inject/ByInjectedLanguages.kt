package dev.basedpython.pycharm.lsp.inject

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType

/**
 * Matching the id a marker spelled to a language this IDE actually has.
 *
 * `by` reports the id verbatim and interprets nothing, which is what lets it mark a language it has
 * never heard of. So the whole of the matching is here, and it has to cope with how people write
 * these markers rather than with a fixed vocabulary: `# language=html`, `language=HTML`,
 * `language=js`, `language=JavaScript` all name the same thing to whoever typed them.
 *
 * An id nothing matches is not an error and is not reported as one. It means this IDE has no
 * support for that language — a `language=svelte` in an IDE with no Svelte plugin — and the right
 * outcome is an ordinary string, which is exactly what happens when nothing is injected.
 */
object ByInjectedLanguages {

    /** The language [id] names, or null when nothing here provides it. */
    fun find(id: String): Language? {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return null
        return Language.findLanguageByID(trimmed)
            ?: byIdIgnoringCase(trimmed)
            ?: byDisplayName(trimmed)
            ?: byFileExtension(trimmed)
    }

    private fun byIdIgnoringCase(id: String): Language? =
        Language.getRegisteredLanguages().firstOrNull { it.id.equals(id, ignoreCase = true) }

    private fun byDisplayName(id: String): Language? =
        Language.getRegisteredLanguages().firstOrNull { it.displayName.equals(id, ignoreCase = true) }

    /**
     * The language of the file type registered for `.<id>`, which is how the short spellings
     * resolve: `js`, `py`, `kt`, `md`.
     *
     * Asked last, because an extension is the least direct of the four ways to name a language and
     * the one most likely to collide with something else's.
     */
    private fun byFileExtension(id: String): Language? =
        (FileTypeManager.getInstance().getFileTypeByExtension(id) as? LanguageFileType)?.language

    // Deliberately no cache. A `Language` belongs to whichever plugin registered it, and holding
    // one in a field of this plugin's own outlives that plugin being unloaded — which pins its
    // classloader for as long as this one is loaded. The scan is over a few dozen languages and
    // runs once per fragment, behind a request to `by` that costs orders of magnitude more.
}
