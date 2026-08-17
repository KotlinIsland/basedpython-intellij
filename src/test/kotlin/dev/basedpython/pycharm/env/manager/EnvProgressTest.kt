package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading per-package progress out of the tool's own output.
 *
 * Every line below is verbatim from `uv add scipy pandas` on a cold cache, piped rather than
 * attached to a terminal — which is the case that matters, since that is how the plugin runs it and
 * a piped uv prints plain lines instead of redrawing progress bars.
 */
class EnvProgressTest {

    private fun parse(line: String) = EnvProgressLine.parse(line)

    @Test
    fun `a download is announced as it starts, with its size`() {
        assertEquals(
            EnvProgressEvent.Downloading("scipy", "19.5MiB"),
            parse("Downloading scipy (19.5MiB)"),
        )
        assertEquals(EnvProgressEvent.Downloading("numpy", null), parse("Downloading numpy"))
    }

    /** uv indents the completion lines by one space. */
    @Test
    fun `a download is announced again as it finishes`() {
        assertEquals(EnvProgressEvent.Downloaded("numpy"), parse(" Downloaded numpy"))
        assertEquals(EnvProgressEvent.Downloaded("numpy"), parse("Downloaded numpy"))
    }

    @Test
    fun `the trailing block says what was installed and removed`() {
        assertEquals(EnvProgressEvent.Installed("numpy", "2.5.2"), parse(" + numpy==2.5.2"))
        assertEquals(
            EnvProgressEvent.Uninstalled("urllib3", "2.7.0"),
            parse(" - urllib3==2.7.0"),
        )
    }

    /** Counts, not names: `Downloading 5 packages` is a summary and names no package. */
    @Test
    fun `summary lines are not read as packages`() {
        assertNull(parse("Resolved 7 packages in 249ms"))
        assertNull(parse("Prepared 5 packages in 3.12s"))
        assertNull(parse("Installed 5 packages in 67ms"))
        assertNull(parse("Audited 3 packages in 1ms"))
        assertNull(parse("Downloading 5 packages"))
        assertNull(parse(""))
        assertNull(parse("error: something went wrong"))
    }

    // ---- the state it builds up ---------------------------------------------

    /** The real sequence, in order, for two concurrent downloads. */
    @Test
    fun `a package is busy from its download until it is installed`() {
        var progress = EnvProgress()
        assertTrue(progress.isEmpty)

        progress = progress.with(EnvProgressEvent.Downloading("scipy", "19.5MiB"))
        progress = progress.with(EnvProgressEvent.Downloading("numpy", "5.1MiB"))
        assertEquals(EnvPackageActivity.DOWNLOADING, progress.activityOf("scipy"))
        assertEquals(EnvPackageActivity.DOWNLOADING, progress.activityOf("numpy"))

        // Downloaded but not yet installed — still busy, and the row should still spin.
        progress = progress.with(EnvProgressEvent.Downloaded("numpy"))
        assertEquals(EnvPackageActivity.PREPARING, progress.activityOf("numpy"))
        assertEquals(EnvPackageActivity.DOWNLOADING, progress.activityOf("scipy"))

        progress = progress.with(EnvProgressEvent.Installed("numpy", "2.5.2"))
        assertNull(progress.activityOf("numpy"), "the + block is printed after the work")
        assertEquals(EnvPackageActivity.DOWNLOADING, progress.activityOf("scipy"))
    }

    /** The tree spells names as the index does; the tool spells them as the wheel does. */
    @Test
    fun `names are matched however either side spells them`() {
        val progress = EnvProgress().with(EnvProgressEvent.Downloading("flask_sqlalchemy", null))

        assertEquals(EnvPackageActivity.DOWNLOADING, progress.activityOf("Flask-SQLAlchemy"))
        assertEquals(EnvPackageActivity.DOWNLOADING, progress.activityOf("flask.sqlalchemy"))
    }

    /**
     * uv says nothing per package while uninstalling — only the trailing `-` block — so without
     * naming them up front a removal would show no motion at all.
     */
    @Test
    fun `packages can be marked busy before the tool mentions them`() {
        var progress = EnvProgress().starting(listOf("httpx", "rich"), EnvPackageActivity.REMOVING)

        assertEquals(EnvPackageActivity.REMOVING, progress.activityOf("httpx"))
        assertEquals(EnvPackageActivity.REMOVING, progress.activityOf("rich"))

        progress = progress.with(EnvProgressEvent.Uninstalled("httpx", "0.28.1"))
        assertNull(progress.activityOf("httpx"))
        assertEquals(EnvPackageActivity.REMOVING, progress.activityOf("rich"))
    }

    @Test
    fun `the headline names what is happening now`() {
        val downloading = EnvProgress().with(EnvProgressEvent.Downloading("scipy", "19.5MiB"))
        assertEquals("scipy (19.5MiB)", downloading.headline)

        val plain = EnvProgress().with(EnvProgressEvent.Downloading("scipy", null))
        assertEquals("scipy", plain.headline)
    }

    @Test
    fun `clearing ends every activity`() {
        val progress = EnvProgress()
            .with(EnvProgressEvent.Downloading("scipy", null))
            .cleared()

        assertTrue(progress.isEmpty)
        assertNull(progress.activityOf("scipy"))
    }
}
