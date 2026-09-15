package dev.basedpython.pycharm.env.manager.index

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The service as the platform builds it.
 *
 * [PackageIndexCache] has a second constructor so that tests can point it at a temporary directory,
 * and a light service is instantiated reflectively on first use — a constructor the platform cannot
 * pick is an exception the first time a user opens *Add Package*, not a compile error.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class PackageIndexCacheServiceTest {

    @Suppress("unused")
    private val fixture by codeInsightFixture()

    @Test
    fun `the platform can build the service`() {
        assertSame(PackageIndexCache.getInstance(), PackageIndexCache.getInstance())
    }
}
