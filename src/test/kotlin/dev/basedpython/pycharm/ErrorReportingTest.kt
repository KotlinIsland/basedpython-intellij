package dev.basedpython.pycharm

import com.intellij.diagnostic.DefaultIdeaErrorLogger
import com.intellij.diagnostic.JetBrainsMarketplaceErrorReportSubmitter
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.junit5.TestApplication
import dev.basedpython.pycharm.env.bundled.BundledBinaries
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * An exception or freeze blamed on this plugin is sent to its Marketplace page. The platform picks
 * the submitter by the plugin it is registered from, the same lookup the error dialog and the
 * automatic sender make, for an exception and for a freeze's thread dumps alike, so asking it which
 * submitter answers for this plugin is asking where a report goes.
 */
@TestApplication
class ErrorReportingTest {

    @Test
    fun `a report blamed on this plugin goes to Marketplace`() {
        val plugin = checkNotNull(PluginManagerCore.getPlugin(PluginId.getId(BundledBinaries.PLUGIN_ID))) {
            "${BundledBinaries.PLUGIN_ID} is not loaded in the test application"
        }
        assertInstanceOf(
            JetBrainsMarketplaceErrorReportSubmitter::class.java,
            DefaultIdeaErrorLogger.findSubmitter(RuntimeException("boom"), plugin),
        )
    }
}
