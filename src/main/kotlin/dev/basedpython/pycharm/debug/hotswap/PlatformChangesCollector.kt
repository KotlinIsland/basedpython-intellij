package dev.basedpython.pycharm.debug.hotswap

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.hotswap.SourceFileChangesCollector
import com.intellij.xdebugger.hotswap.SourceFileChangesListener
import com.intellij.xdebugger.impl.hotswap.SourceFileChangeFilter
import com.intellij.xdebugger.impl.hotswap.SourceFileChangesCollectorImpl
import kotlinx.coroutines.CoroutineScope
import java.lang.reflect.Constructor

/**
 * The platform's own file-change collector, built by whichever constructor the running IDE has.
 *
 * ## why this is not a constructor call
 *
 * [SourceFileChangesCollectorImpl] is the only implementation of the public
 * [SourceFileChangesCollector] the platform ships, and it is what makes reverting an edit put the
 * hot reload toolbar away again — it remembers the content each file had at the last reset and
 * compares against local history. There is no public factory for it and no other way to get one, so
 * a plugin that wants that behaviour constructs the `impl` class, and an `impl` class is one whose
 * shape the platform is free to change. It did:
 *
 * | build | constructor |
 * | --- | --- |
 * | 262 | `(CoroutineScope, SourceFileChangesListener, vararg SourceFileChangeFilter<VirtualFile>)` |
 * | 263 | `(Project, CoroutineScope, SourceFileChangesListener, List<SourceFileChangeFilter<VirtualFile>>, List<SourceFileChangeCompatibilityChecker>)` |
 *
 * Both were read off the class files rather than guessed — `javap` on `PY-263.3889`'s
 * `intellij.platform.debugger.impl.jar` for the second, and the `NoSuchMethodError` a 263 IDE threw
 * at a build compiled against 262 for the first, which quotes the descriptor the compiler emitted.
 *
 * This plugin declares `262` to `263.*` and is one artifact across that range, so a compiled call
 * to either constructor is a `NoSuchMethodError` on half of it. Looking the constructor up is the
 * only shape that is right on both.
 *
 * The 263 constructor's two lists both have defaults; the checkers list is passed empty, which is
 * what the default is. Compatibility checking is how the platform lets a provider say *in advance*
 * that an edit cannot be reloaded — bpd decides that when the replacement is attempted and says so
 * itself, so there is nothing for a checker here to answer.
 *
 * ## why a failure here is not thrown
 *
 * The platform calls `createChangesCollector` from `HotSwapSessionImpl.init`, which
 * `XDebuggerManagerImpl.startSession` runs while starting the session — so what a throw from here
 * costs is not hot reload, it is **the whole debug session**. That is how this was found: a
 * `NoSuchMethodError` out of this call and `by run` would not debug at all on a 2026.3 EAP. A
 * convenience that watches for edits may not be the reason a debugger will not start, so a build
 * with neither constructor watches nothing, says so in the log, and lets the session run.
 */
internal object PlatformChangesCollector {
    private val LOG = Logger.getInstance(PlatformChangesCollector::class.java)

    fun over(
        project: Project,
        coroutineScope: CoroutineScope,
        listener: SourceFileChangesListener,
        filters: List<SourceFileChangeFilter<VirtualFile>>,
    ): SourceFileChangesCollector<VirtualFile> {
        val impl = SourceFileChangesCollectorImpl::class.java
        // Built here rather than at the call it belongs to, because its `javaClass` is the exact
        // `SourceFileChangeFilter[]` the 262 lookup has to name and there is no honest way to spell
        // that as a class literal.
        val asArray = filters.toTypedArray()
        try {
            impl.constructorOrNull(
                Project::class.java,
                CoroutineScope::class.java,
                SourceFileChangesListener::class.java,
                List::class.java,
                List::class.java,
            )?.let { return it.newInstance(project, coroutineScope, listener, filters, emptyList<Any>()) }

            impl.constructorOrNull(
                CoroutineScope::class.java,
                SourceFileChangesListener::class.java,
                asArray.javaClass,
            )?.let { return it.newInstance(coroutineScope, listener, asArray) }
        } catch (e: ReflectiveOperationException) {
            LOG.error("the platform's hot reload change collector would not be built; nothing will be watched", e)
            return WatchesNothing
        } catch (e: LinkageError) {
            // The same family as the `NoSuchMethodError` this class exists for — the constructor
            // resolved but something it names no longer does. Caught for the same reason and not a
            // wider `Throwable`, which would swallow the cancellation a session going away throws.
            LOG.error("the platform's hot reload change collector would not link; nothing will be watched", e)
            return WatchesNothing
        }

        LOG.error(
            "${impl.name} has neither constructor this plugin knows " +
                "(${impl.constructors.joinToString { it.toGenericString() }}); nothing will be watched",
        )
        return WatchesNothing
    }

    private fun <T> Class<T>.constructorOrNull(vararg types: Class<*>): Constructor<T>? =
        try {
            getConstructor(*types)
        } catch (_: NoSuchMethodException) {
            null
        }

    /**
     * What is watched when the platform's collector cannot be built: nothing.
     *
     * No toolbar, no button, and the debug session it was asked for starts and runs — see the class
     * comment for why that trade is the right way round.
     */
    private object WatchesNothing : SourceFileChangesCollector<VirtualFile> {
        override fun getChanges(): Set<VirtualFile> = emptySet()

        override fun resetChanges() = Unit

        override fun dispose() = Unit
    }
}
