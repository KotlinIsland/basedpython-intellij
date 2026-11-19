package dev.basedpython.pycharm.debug.bpd

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.platform.dap.DapProcessStarter
import com.intellij.platform.dap.DapReverseRequestContext
import com.intellij.platform.dap.DapStartDebuggingSupport
import com.intellij.platform.dap.DapStartRequest
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.jetbrains.dap.protocol.DapRequestFailedException
import com.jetbrains.dap.protocol.StartDebuggingRequestArguments
import com.jetbrains.dap.protocol.StartDebuggingRequestKind
import com.jetbrains.dap.protocol.StartDebuggingResponse
import dev.basedpython.pycharm.debug.ByDebugAdapter
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * A child of a debugged program that bpd asked the IDE to take up: a process the program started —
 * `runserver`'s reloader child, which serves every request and renders every template, a
 * `multiprocessing` worker, a `subprocess.run([sys.executable, …])` — held by bpd before it has run a
 * line of its own, until a session arrives for it.
 *
 * bpd asks with DAP's `startDebugging` reverse request, carrying an `attach` configuration: the
 * parent's launch configuration, rewritten to name the child's session (`bpdSession`) and where to
 * reach the adapter (`bpdConnect`). The session is a second connection to the same `bpd dap
 * --listen`, since a fresh adapter would have a fresh engine that does not hold the child.
 *
 * @property parent what the parent session's wrapper recorded: the address and token the child's
 *   connection is made with ([ByBpdConnection.child])
 * @property configuration the `attach` arguments, bpd's own, as the platform takes start arguments
 * @property recordFile the parent's record file, through which a hot reload in the child finds
 *   `by run`'s build directory — the child runs from the same one
 * @property stopsOnEntry whether the configuration asked for a stop before the program runs: bpd
 *   holds a taken-up child and announces where it is held whatever the configuration says, so it is
 *   the IDE that lets the child go, unless the user asked to stop there
 */
internal class ByChildSession(
    val parent: ByBpdRecord.Ready,
    val configuration: Map<String, Any?>,
    val recordFile: Path?,
    val stopsOnEntry: Boolean,
) {

    /** What bpd's request was read as: a child to take up, or why not, in a sentence for bpd's log. */
    sealed interface Parsed {
        data class Child(val session: ByChildSession) : Parsed

        data class Refused(val why: String) : Parsed
    }

    companion object {
        private const val SESSION = "bpdSession"
        private const val CONNECT = "bpdConnect"

        /**
         * Read bpd's `startDebugging` configuration against the parent's record.
         *
         * Refused unless the request names a session and the listener the parent is connected to:
         * a reverse request that pointed the IDE at another address, or presented another token,
         * is not one bpd makes — every session of a debuggee shares one listener and one token — and
         * the IDE does not dial an address because the debuggee's adapter named it.
         */
        fun parse(configuration: JsonObject, parent: ByBpdRecord.Launch?, recordFile: Path?): Parsed {
            val ready = parent as? ByBpdRecord.Ready
                ?: return Parsed.Refused(
                    "this client reached bpd by having bpd dial it, and a child's session would need a " +
                        "second connection bpd cannot accept that way",
                )
            (configuration[SESSION] as? JsonPrimitive)?.longOrNull
                ?: return Parsed.Refused("the configuration names no `$SESSION` to take up")
            val connect = configuration[CONNECT] as? JsonObject
                ?: return Parsed.Refused("the configuration names no `$CONNECT` to reach the adapter by")
            val host = (connect["host"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val port = (connect["port"] as? JsonPrimitive)?.intOrNull
            val token = (connect["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (host != ready.host || port != ready.port) {
                return Parsed.Refused(
                    "the configuration names $host:$port, and this session's adapter listens on " +
                        "${ready.host}:${ready.port}; a child's session is taken up at the listener its " +
                        "parent's is on",
                )
            }
            if (token != ready.token) {
                return Parsed.Refused("the configuration carries a token other than this session's")
            }
            val stopsOnEntry = (configuration["stopOnEntry"] as? JsonPrimitive)?.booleanOrNull == true
            @Suppress("UNCHECKED_CAST")
            val arguments = plain(configuration) as Map<String, Any?>
            return Parsed.Child(ByChildSession(ready, arguments, recordFile, stopsOnEntry))
        }

        /**
         * A JSON value as the plain maps, lists, strings, numbers and booleans the platform turns a
         * session's start arguments into JSON from — the same shape every other session's arguments
         * here have. An integer stays an integer: `bpdSession` is matched by bpd as one.
         */
        internal fun plain(element: JsonElement): Any? = when (element) {
            JsonNull -> null
            is JsonObject -> element.mapValues { (_, value) -> plain(value) }
            is JsonArray -> element.map(::plain)
            is JsonPrimitive -> when {
                element.isString -> element.content
                else -> element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
            }
        }
    }
}

/**
 * Answers bpd's `startDebugging` by starting a debug session for the child, beside the parent's.
 *
 * Supplying this is also what makes the platform's client say in `initialize` that it answers the
 * request (`supportsStartDebuggingRequest`), which bpd requires before it accepts `debugChildren`:
 * a debugged child stops, and an adapter whose client could not take it up would leave it held for
 * good.
 *
 * Not the platform's own starter (`startChildDapSession`): it starts the child through the same
 * descriptor factory with nothing to tell the descriptor that this session is a child, so it would
 * run the parent's whole setup — find a `by run` to point at a port, wait for a wrapper's record —
 * for a session whose process is already running. This one starts the same kind of session through
 * the same public pieces ([DapProcessStarter], [XDebuggerManager.newSessionBuilder]) and hands the
 * descriptor the child as it is built ([take]).
 *
 * @param parent what the parent session's wrapper recorded, once it has
 * @param recordFile the parent's record file
 */
internal class ByStartDebuggingSupport(
    private val parent: () -> ByBpdRecord.Launch?,
    private val recordFile: () -> Path?,
) : DapStartDebuggingSupport() {

    /** The tabs of this session's children that have ended, for the next child to take over. */
    private val tabs = ByChildTabs<RunContentDescriptor>()

    override suspend fun startDebugging(
        context: DapReverseRequestContext,
        args: StartDebuggingRequestArguments,
    ): StartDebuggingResponse {
        if (args.request != StartDebuggingRequestKind.Attach) {
            throw DapRequestFailedException("a child is taken up with `attach`; bpd asked for `${args.request}`")
        }
        val configuration = args.configuration as? JsonObject
            ?: throw DapRequestFailedException("the startDebugging configuration is not a JSON object")
        val child = when (val parsed = ByChildSession.parse(configuration, parent(), recordFile())) {
            is ByChildSession.Parsed.Refused -> {
                LOG.warn("refused bpd's startDebugging: ${parsed.why}")
                throw DapRequestFailedException(parsed.why)
            }
            is ByChildSession.Parsed.Child -> parsed.session
        }
        withContext(Dispatchers.EDT) {
            val session = try {
                start(context.environment, child)
            } catch (e: ExecutionException) {
                throw DapRequestFailedException(e.message ?: e.javaClass.simpleName)
            }
            // a child does not outlive the session that took it up: Stop on the parent, or its
            // program ending, ends the child's session too, as the platform's own starter does
            context.sessionScope.coroutineContext.job.invokeOnCompletion {
                ApplicationManager.getApplication().invokeLater { if (!session.isStopped) session.stop() }
            }
        }
        return StartDebuggingResponse()
    }

    private fun start(parent: ExecutionEnvironment, child: ByChildSession): XDebugSession {
        val project = parent.project
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        val environment = ExecutionEnvironmentBuilder(project, executor)
            .runProfile(parent.runProfile)
            .runner(parent.runner)
            .target(parent.executionTarget)
            .build()
        // the child's process is the parent's program's, already running: there is nothing to start
        val nothingToRun = RunProfileState { _, _ -> null }
        val starter = DapProcessStarter(
            environment,
            executor,
            nothingToRun,
            ByDebugAdapter,
            DapStartRequest.Attach,
            child.configuration,
        )
        val builder = XDebuggerManager.getInstance(project).newSessionBuilder(starter)
            .sessionName(BasedPythonBundle.message("debug.child.sessionName", parent.runProfile.name))
            .icon(parent.runProfile.icon)
            .environment(environment)
            // not `showToolWindowOnSuspendOnly`, though a child arrives on every start of a reloader:
            // in 263 a session built with it makes no tab until it suspends, and it is making the tab
            // (`XDebugSessionImpl.initSessionTab`) that calls `sessionInitialized`, which is what
            // starts a DAP session talking — so the session never sends `initialize` and never
            // suspends. Measured: the child's DAP session had no message loop and no trace file. The
            // platform's own `startChildDapSession` passes it, and hangs the same way
            .showTab(true)
        tabs.reusable()?.let(builder::contentToReuse)
        pending[environment] = child
        val started = try {
            builder.startSession()
        } finally {
            pending.remove(environment)
        }
        val session = started.session
        val tab = started.runContentDescriptor
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionStopped() {
                tab?.let(tabs::ended)
            }
        })
        return session
    }

    companion object {
        private val LOG = Logger.getInstance(ByStartDebuggingSupport::class.java)

        /**
         * The children whose sessions are being built, by the environment each is built in.
         * Read once, by the descriptor the platform creates for that environment
         * ([dev.basedpython.pycharm.debug.ByDebugAdapterDescriptor.configureProfileState]), from
         * inside [start] — so an entry lives exactly as long as the call that put it there.
         */
        private val pending = ConcurrentHashMap<ExecutionEnvironment, ByChildSession>()

        /** The child [environment] is being built for, or null for a session that is not a child's. */
        fun take(environment: ExecutionEnvironment): ByChildSession? = pending.remove(environment)
    }
}

/**
 * The tabs of a session's children that have ended, which the next child takes over.
 *
 * `runserver` starts its serving child again whenever it reloads, and a `multiprocessing` pool
 * starts its workers again whenever it is replaced, each a child bpd asks the IDE to take up; a tab
 * per child would leave one finished tab behind per reload. The newest one that is still open is
 * reused, so a reloading server keeps one tab for its child; a child that starts while every
 * earlier one is still running gets a tab of its own.
 */
internal class ByChildTabs<T : Disposable> {
    private val ended = ArrayDeque<T>()

    /**
     * [descriptor]'s child has ended. Kept until its tab is closed — by the user, or by a child
     * that takes it over — which disposes the descriptor and takes it off the list.
     */
    fun ended(descriptor: T) {
        val registered = Disposer.tryRegister(descriptor) { synchronized(this) { ended.remove(descriptor) } }
        if (registered) synchronized(this) { ended.addLast(descriptor) }
    }

    /** The newest ended child's tab that is still open, or null when there is none. */
    @Synchronized
    fun reusable(): T? = ended.removeLastOrNull()
}
