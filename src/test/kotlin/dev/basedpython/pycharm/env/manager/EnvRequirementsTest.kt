package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Turning what the user typed in the Add Package field into arguments. */
class EnvRequirementsTest {

    @Test
    fun `several requirements can be typed at once`() {
        assertEquals(listOf("httpx", "rich", "attrs"), EnvRequirements.split("httpx rich attrs"))
    }

    @Test
    fun `surrounding and repeated whitespace is ignored`() {
        assertEquals(listOf("httpx", "rich"), EnvRequirements.split("  httpx\t\t rich \n"))
        assertEquals(emptyList<String>(), EnvRequirements.split("   "))
        assertEquals(emptyList<String>(), EnvRequirements.split(""))
    }

    /**
     * The case this is written for. A comma separates the halves of a version range, and splitting
     * on it turns one correct requirement into two that resolve to nothing.
     */
    @Test
    fun `a comma inside a version specifier is not a separator`() {
        assertEquals(listOf("httpx>=0.27,<1.0"), EnvRequirements.split("httpx>=0.27,<1.0"))
    }

    @Test
    fun `extras, URLs and paths pass through untouched`() {
        assertEquals(listOf("httpx[http2]"), EnvRequirements.split("httpx[http2]"))
        assertEquals(
            listOf("git+https://github.com/x/y@main"),
            EnvRequirements.split("git+https://github.com/x/y@main"),
        )
        assertEquals(listOf("./vendor/mylib"), EnvRequirements.split("./vendor/mylib"))
    }
}
