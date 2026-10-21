package dev.basedpython.pycharm.run.target

import com.intellij.execution.target.LanguageRuntimeType
import com.intellij.execution.target.TargetEnvironmentConfiguration
import com.intellij.execution.target.TargetEnvironmentsManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.junit5.fixture.projectFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A run target's basedpython runtime through an unload of the plugin and its loading again: while
 * the plugin is gone the target holds nothing of its classes, and keeps the runtime as written.
 *
 * The unload is the runtime type's extension being removed, as an unload removes it, after what the
 * plugin is told before one.
 */
@TestApplication
@TestFixtures
class ByTargetRuntimesTest {

    /** Open, as the projects whose targets an unload goes through are. */
    private val project by projectFixture(openAfterCreation = true)

    private lateinit var target: TestTarget

    @BeforeEach
    fun addTarget() {
        target = TestTarget().apply {
            displayName = "remote"
            addLanguageRuntime(ByLanguageRuntimeConfiguration().also { it.byPath = "/opt/by/bin/by" })
        }
        TargetEnvironmentsManager.getInstance(project).addTarget(target)
    }

    @AfterEach
    fun removeTarget() {
        TargetEnvironmentsManager.getInstance(project).removeTarget(target)
    }

    @Test
    fun `the runtime is kept as an entry while the plugin is gone, and is a runtime again once it is back`() {
        ByTargetRuntimes.beforeUnload()
        val unloaded = Disposer.newDisposable("basedpython unloaded")
        try {
            ExtensionTestUtil.maskExtensions(
                LanguageRuntimeType.EXTENSION_NAME,
                LanguageRuntimeType.EXTENSION_NAME.extensionList.filterNot { it is ByLanguageRuntimeType },
                unloaded,
            )

            assertTrue(target.runtimes.resolvedConfigs().none { it is ByLanguageRuntimeConfiguration })
            val entry = target.runtimes.state.configs.single { it.typeId == ByLanguageRuntimeType.TYPE_ID }
            val byPath = entry.innerState!!.getChildren("option").single { it.getAttributeValue("name") == "byPath" }
            assertEquals("/opt/by/bin/by", byPath.getAttributeValue("value"))
        } finally {
            Disposer.dispose(unloaded)
        }

        ByTargetRuntimes.afterLoad()

        assertEquals("/opt/by/bin/by", ByLanguageRuntimeType.runtimeOf(target)?.byPath)
    }

    /** Without an unload, loading changes nothing: the runtime is the one the target was given. */
    @Test
    fun `a runtime already resolved is left as it is`() {
        val runtime = ByLanguageRuntimeType.runtimeOf(target)

        ByTargetRuntimes.afterLoad()

        assertTrue(runtime === ByLanguageRuntimeType.runtimeOf(target))
    }
}

/** A target of no type the platform knows, which a target list keeps as it is given it. */
private class TestTarget : TargetEnvironmentConfiguration("basedpython-test-target") {
    override var projectRootOnTarget: String = ""
}
