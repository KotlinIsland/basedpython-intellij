package dev.basedpython.pycharm.docs.help

import com.intellij.openapi.help.WebHelpProvider
import dev.basedpython.pycharm.docs.BasedPythonDocEntries

/**
 * Routes basedpython help topics (e.g. the "?" button in the basedpython
 * Settings dialog) to the online documentation in an external browser.
 *
 * The platform matches a requested `helpTopicId` against registered
 * [WebHelpProvider]s by [getHelpTopicPrefix]. The prefix is taken from the
 * `id` attribute of the `webHelpProvider` extension point, which here is
 * `dev.basedpython.pycharm`. When a topic matches, the platform calls
 * [getHelpPageUrl]; returning a non-null URL opens it in the browser.
 */
class BasedPythonWebHelpProvider : WebHelpProvider() {

    /**
     * Topics handled by this provider all start with this prefix. Mirrors the
     * `id` attribute of the `webHelpProvider` extension declaration.
     */
    override fun getHelpTopicPrefix(): String = HELP_TOPIC_PREFIX

    override fun getHelpPageUrl(helpTopicId: String): String? {
        if (!helpTopicId.startsWith(HELP_TOPIC_PREFIX)) {
            return null
        }
        // Map the topic suffix (e.g. "settings") onto a docs path; fall back to
        // the docs landing page for unknown or empty suffixes.
        val suffix = helpTopicId.removePrefix(HELP_TOPIC_PREFIX).trimStart('.')
        val path = TOPIC_PATHS[suffix].orEmpty()
        return BasedPythonDocEntries.DOCS_BASE + path
    }

    private companion object {
        /** Shared prefix for all basedpython help topics. */
        const val HELP_TOPIC_PREFIX: String = "dev.basedpython.pycharm."

        /** Maps a topic suffix to a docs sub-path appended to [BasedPythonDocEntries.DOCS_BASE]. */
        val TOPIC_PATHS: Map<String, String> = mapOf(
            "settings" to "ide/settings",
        )
    }
}
