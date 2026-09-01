package dev.basedpython.pycharm.debug.hotswap

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.hotswap.SourceFileChangesListener
import com.intellij.xdebugger.impl.hotswap.SourceFileChangeFilter
import com.intellij.xdebugger.impl.hotswap.SourceFileChangesCollectorImpl
import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That the IDE this is built against still has a constructor [PlatformChangesCollector] can use.
 *
 * The one thing that class can get wrong, and the one thing nothing else would notice. It looks a
 * public `impl` constructor up by name at runtime, so a platform that renames or reshapes it again
 * compiles perfectly and fails in a user's debug session — which is exactly how the 262 to 263
 * change was found, as a `NoSuchMethodError` out of `XDebuggerManagerImpl.startSession` on a
 * 2026.3 EAP.
 *
 * This asks the class file the same question the code asks, so the next such change is a failing
 * build in the release that introduces it rather than a debugger that will not start.
 */
class PlatformChangesCollectorTest {

    /** The shape 2026.3 introduced: the project first, and the two lists it grew. */
    @Test
    fun `the 2026_3 constructor is found, or the 2026_2 one is`() {
        val impl = SourceFileChangesCollectorImpl::class.java
        val filters = emptyArray<SourceFileChangeFilter<*>>()

        val new = impl.has(
            Project::class.java,
            CoroutineScope::class.java,
            SourceFileChangesListener::class.java,
            List::class.java,
            List::class.java,
        )
        val old = impl.has(
            CoroutineScope::class.java,
            SourceFileChangesListener::class.java,
            filters.javaClass,
        )

        assertTrue(
            new || old,
            "${impl.name} has neither constructor PlatformChangesCollector knows, so hot reload " +
                "would watch nothing on this build. Its constructors are now: " +
                impl.constructors.joinToString { it.toGenericString() },
        )
    }

    private fun Class<*>.has(vararg types: Class<*>) =
        try {
            getConstructor(*types)
            true
        } catch (_: NoSuchMethodException) {
            false
        }
}
