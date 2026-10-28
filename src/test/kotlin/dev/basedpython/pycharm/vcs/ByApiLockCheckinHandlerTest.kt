package dev.basedpython.pycharm.vcs

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which commits are checked for a stale `api.lock`. */
class ByApiLockCheckinHandlerTest {

    @Test
    fun `a commit is checked when it touches a source by reads or the lockfile`() {
        for (name in listOf("api.lock", "a.by", "a.byi", "b.py", "c.pyi")) assertTrue(ByApiLockCheckinHandler.checks(name), name)
        for (name in listOf("README.md", "uv.lock", "pyproject.toml")) assertFalse(ByApiLockCheckinHandler.checks(name), name)
    }
}
