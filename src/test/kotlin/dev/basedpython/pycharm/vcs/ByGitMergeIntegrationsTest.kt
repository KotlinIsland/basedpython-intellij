package dev.basedpython.pycharm.vcs

import dev.basedpython.pycharm.lsp.ext.ByGitMergeIntegration
import dev.basedpython.pycharm.vcs.ByGitMergeIntegrations.Offer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What a project is offered for each state `by/gitMergeIntegration` reports. */
class ByGitMergeIntegrationsTest {

    private val driver = "'/x/by' merge-driver %O %A %B %L %P %S %X %Y"

    private fun state(enabled: Boolean = false, repository: Boolean = true, missing: List<String> = emptyList(), driver: String? = null, found: Boolean = false, current: Boolean = false) =
        ByGitMergeIntegration(enabled, repository, "file:///p/.gitattributes", missing, driver, found, current)

    @Test
    fun `nothing outside a repository, or where it works`() {
        assertEquals(Offer.None, ByGitMergeIntegrations.offerFor(state(repository = false)))
        assertEquals(Offer.None, ByGitMergeIntegrations.offerFor(state(enabled = true, driver = driver, found = true, current = true)))
    }

    @Test
    fun `a repository with no driver is offered the integration`() {
        assertEquals(Offer.Enable, ByGitMergeIntegrations.offerFor(state(missing = listOf("*.by"))))
        // a working driver some patterns are not merged with
        assertEquals(Offer.Enable, ByGitMergeIntegrations.offerFor(state(missing = listOf("api.lock"), driver = driver, found = true, current = true)))
    }

    @Test
    fun `a driver whose by is gone, or that by no longer writes, is offered a repair`() {
        assertEquals(Offer.Repair, ByGitMergeIntegrations.offerFor(state(driver = driver, found = false, current = false)))
        assertEquals(Offer.Repair, ByGitMergeIntegrations.offerFor(state(driver = driver, found = true, current = false)))
    }
}
