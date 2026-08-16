package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Reading and rewriting what the user typed in the Add Package field.
 *
 * A requirement is one string carrying three separate things — `httpx[http2]>=0.27,<1.0` is a name,
 * a set of extras and a version range — so the dialog has to take the name out to look the package
 * up and put extras back in when a box is ticked, without disturbing anything else that was typed.
 */
class EnvRequirementsTest {

    // ---- splitting a line ---------------------------------------------------

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

    // ---- finding the package name -------------------------------------------

    @Test
    fun `the name is what is left after the specifier, extras and marker`() {
        assertEquals("httpx", EnvRequirements.packageName("httpx"))
        assertEquals("httpx", EnvRequirements.packageName("httpx>=0.27"))
        assertEquals("httpx", EnvRequirements.packageName("httpx[http2]"))
        assertEquals("httpx", EnvRequirements.packageName("httpx[http2,cli]>=0.27,<1.0"))
        assertEquals("httpx", EnvRequirements.packageName("""httpx ; python_version < "3.11" """))
        assertEquals("zope.interface", EnvRequirements.packageName("zope.interface==5.4"))
        assertEquals("Flask-SQLAlchemy", EnvRequirements.packageName("Flask-SQLAlchemy"))
    }

    /**
     * A direct reference names a repository or a directory, not a package the index can answer for,
     * so it yields nothing rather than a plausible-looking guess.
     */
    @Test
    fun `a URL or a path is not a package name`() {
        assertNull(EnvRequirements.packageName("git+https://github.com/x/y@main"))
        assertNull(EnvRequirements.packageName("https://example.invalid/x.whl"))
        assertNull(EnvRequirements.packageName("./vendor/mylib"))
        assertNull(EnvRequirements.packageName("/opt/wheels/x.whl"))
        assertNull(EnvRequirements.packageName("mylib @ https://example.invalid/x.whl"))
    }

    @Test
    fun `nothing typed names nothing`() {
        assertNull(EnvRequirements.packageName(""))
        assertNull(EnvRequirements.packageName("   "))
        assertNull(EnvRequirements.packageName(">=1.0"))
    }

    // ---- reading and writing extras -----------------------------------------

    @Test
    fun `the extras already written in are found`() {
        assertEquals(listOf("http2"), EnvRequirements.extrasOf("httpx[http2]"))
        assertEquals(listOf("http2", "cli"), EnvRequirements.extrasOf("httpx[http2,cli]>=0.27"))
        assertEquals(listOf("http2", "cli"), EnvRequirements.extrasOf("httpx[ http2 , cli ]"))
        assertEquals(emptyList<String>(), EnvRequirements.extrasOf("httpx>=0.27"))
        assertEquals(emptyList<String>(), EnvRequirements.extrasOf("httpx[]"))
    }

    /** Extras go after the name and before the specifier — that is where the syntax puts them. */
    @Test
    fun `ticking an extra inserts it without disturbing the version`() {
        assertEquals("httpx[http2]", EnvRequirements.withExtras("httpx", listOf("http2")))
        assertEquals("httpx[http2]>=0.27", EnvRequirements.withExtras("httpx>=0.27", listOf("http2")))
        assertEquals(
            "httpx[http2,cli]>=0.27,<1.0",
            EnvRequirements.withExtras("httpx>=0.27,<1.0", listOf("http2", "cli")),
        )
    }

    @Test
    fun `re-ticking replaces the previous set rather than appending to it`() {
        assertEquals("httpx[cli]", EnvRequirements.withExtras("httpx[http2]", listOf("cli")))
        assertEquals(
            "httpx[cli]>=0.27",
            EnvRequirements.withExtras("httpx[http2,socks]>=0.27", listOf("cli")),
        )
    }

    /** `httpx[]` is not something any resolver accepts, so the brackets go entirely. */
    @Test
    fun `unticking everything removes the brackets`() {
        assertEquals("httpx", EnvRequirements.withExtras("httpx[http2]", emptyList()))
        assertEquals("httpx>=0.27", EnvRequirements.withExtras("httpx[http2]>=0.27", emptyList()))
    }

    @Test
    fun `duplicate and blank extras are dropped`() {
        assertEquals("httpx[http2]", EnvRequirements.withExtras("httpx", listOf("http2", "http2", " ")))
    }

    /** There is nowhere in a URL that an extra could go, so it is left exactly as typed. */
    @Test
    fun `a requirement with no name is left alone`() {
        val url = "git+https://github.com/x/y@main"
        assertEquals(url, EnvRequirements.withExtras(url, listOf("http2")))
    }

    // ---- what keeps the completion popup open --------------------------------

    /**
     * This predicate is the autopopup.
     *
     * `TextCompletionContributor.invokeAutoPopup` opens the list only when the provider's
     * `acceptChar` returns `ADD_TO_PREFIX`, and the platform's default returns null — so getting
     * this wrong is not a cosmetic issue, it is the difference between completion appearing as you
     * type and completion only ever appearing on Ctrl+Space.
     */
    @Test
    fun `the characters a package name is made of keep the popup open`() {
        for (c in "abzABZ09") {
            assertTrue(EnvRequirements.continuesPackageName(c), "'$c' is part of a name")
        }
        for (c in "-_.") {
            assertTrue(EnvRequirements.continuesPackageName(c), "'$c' is a PEP 503 separator")
        }
    }

    /** Once a specifier, an extra or a marker begins, the catalogue has nothing more to offer. */
    @Test
    fun `the characters that end a name do not`() {
        for (c in " \t>=<![](),;@/'\"") {
            assertFalse(EnvRequirements.continuesPackageName(c), "'$c' ends the name")
        }
    }

    /** Every character of a real name has to be accepted, or the popup closes mid-word. */
    @Test
    fun `every character of the names this completes is accepted`() {
        for (name in listOf("basedpython", "Flask-SQLAlchemy", "zope.interface", "ruamel_yaml", "urllib3")) {
            assertTrue(
                name.all { EnvRequirements.continuesPackageName(it) },
                "$name contains a character that would close the popup",
            )
        }
    }

    // ---- pinning a version --------------------------------------------------

    @Test
    fun `a plain pin is read back`() {
        assertEquals("0.28.1", EnvRequirements.pinnedVersion("httpx==0.28.1"))
        assertEquals("0.28.1", EnvRequirements.pinnedVersion("httpx[http2]==0.28.1"))
    }

    /**
     * A range was typed by hand and is not a choice the picker made, so it reports none rather than
     * showing a version the user did not select.
     */
    @Test
    fun `anything other than a plain pin reads as no selection`() {
        assertNull(EnvRequirements.pinnedVersion("httpx"))
        assertNull(EnvRequirements.pinnedVersion("httpx>=0.27"))
        assertNull(EnvRequirements.pinnedVersion("httpx>=0.27,<1.0"))
        assertNull(EnvRequirements.pinnedVersion("httpx==0.27,!=0.28"))
        assertNull(EnvRequirements.pinnedVersion("httpx===0.28.1"))
    }

    @Test
    fun `picking a version pins it and keeps the extras`() {
        assertEquals("httpx==0.28.1", EnvRequirements.withVersion("httpx", "0.28.1"))
        assertEquals("httpx[http2]==0.28.1", EnvRequirements.withVersion("httpx[http2]", "0.28.1"))
        assertEquals(
            "httpx[http2,cli]==0.28.1",
            EnvRequirements.withVersion("httpx[http2,cli]>=0.27", "0.28.1"),
        )
    }

    /** The picker and the field must not end up holding two different answers. */
    @Test
    fun `picking a version replaces whatever specifier was there`() {
        assertEquals("httpx==0.28.1", EnvRequirements.withVersion("httpx>=0.27,<1.0", "0.28.1"))
        assertEquals("httpx==0.29", EnvRequirements.withVersion("httpx==0.28.1", "0.29"))
    }

    @Test
    fun `choosing any removes the pin`() {
        assertEquals("httpx", EnvRequirements.withVersion("httpx==0.28.1", null))
        assertEquals("httpx[http2]", EnvRequirements.withVersion("httpx[http2]==0.28.1", null))
        assertEquals("httpx", EnvRequirements.withVersion("httpx>=0.27", ""))
    }

    @Test
    fun `an environment marker survives pinning`() {
        assertEquals(
            """httpx==0.28.1 ; python_version < "3.11"""",
            EnvRequirements.withVersion("""httpx ; python_version < "3.11"""", "0.28.1"),
        )
    }

    /** The two pickers edit different parts of one string and must not undo each other. */
    @Test
    fun `extras and a version compose in either order`() {
        val viaExtrasFirst = EnvRequirements.withVersion(
            EnvRequirements.withExtras("httpx", listOf("http2")),
            "0.28.1",
        )
        val viaVersionFirst = EnvRequirements.withExtras(
            EnvRequirements.withVersion("httpx", "0.28.1"),
            listOf("http2"),
        )

        assertEquals("httpx[http2]==0.28.1", viaExtrasFirst)
        assertEquals(viaExtrasFirst, viaVersionFirst)
        assertEquals("0.28.1", EnvRequirements.pinnedVersion(viaExtrasFirst))
        assertEquals(listOf("http2"), EnvRequirements.extrasOf(viaExtrasFirst))
    }

    @Test
    fun `a requirement with no name cannot be pinned`() {
        val url = "git+https://github.com/x/y@main"
        assertEquals(url, EnvRequirements.withVersion(url, "1.0"))
        assertNull(EnvRequirements.pinnedVersion(url))
    }

    /** What the dialog does on every tick has to be stable under repetition. */
    @Test
    fun `writing the same extras twice changes nothing the second time`() {
        val once = EnvRequirements.withExtras("httpx>=0.27", listOf("http2", "cli"))
        assertEquals(once, EnvRequirements.withExtras(once, listOf("http2", "cli")))
        assertEquals(listOf("http2", "cli"), EnvRequirements.extrasOf(once))
    }
}
