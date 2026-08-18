package dev.basedpython.pycharm.debug

import com.intellij.execution.CantRunException
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.util.PathUtil
import com.intellij.platform.dap.DapBreakpointsDescription
import com.intellij.platform.dap.DapClient
import com.intellij.platform.dap.DapCommandProcessor
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapEventConsumer
import com.intellij.platform.dap.DapExceptionBreakpoint
import com.intellij.platform.dap.DapExceptionInfo
import com.intellij.platform.dap.DapInitializationException
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.DapThreadState
import com.intellij.platform.dap.DebugAdapterDescriptor
import com.intellij.platform.dap.DebugAdapterId
import com.intellij.platform.dap.DebugAdapterSupportProvider
import com.intellij.platform.dap.connection.DebugAdapterHandle
import com.intellij.platform.dap.connection.DebugAdapterSocketConnection
import com.intellij.platform.dap.xdebugger.DapXDebugProcess
import com.intellij.platform.dap.xdebugger.DapXDebugSessionState
import com.intellij.platform.dap.xdebugger.DapXSuspendContext
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.frame.XDropFrameHandler
import com.intellij.xdebugger.frame.XSuspendContext
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.debug.bpd.ByBpdConnection
import dev.basedpython.pycharm.debug.bpd.ByBpdWrapper
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.run.ByCommandLineState
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import kotlin.time.Duration.Companion.milliseconds

/** Identifies this adapter to the platform's DAP infrastructure and to `initialize`'s `adapterID`. */
object ByDebugAdapter : DebugAdapterId("basedpython", "basedpython Debugger")

class ByDebugAdapterSupportProvider : DebugAdapterSupportProvider<ByDebugAdapter> {
    override val adapterId: ByDebugAdapter = ByDebugAdapter

    override fun createDebugAdapterDescriptor(project: Project): DebugAdapterDescriptor<ByDebugAdapter> =
        ByDebugAdapterDescriptor(project)
}

/**
 * Drives one `.by` debug session.
 *
 * The shape of the session is dictated by what `by run` is: a transpile step followed by
 * `<python> _by_runner.py <module>` in a temp directory. The IDE cannot insert `-m debugpy` into
 * that command line, so instead it launches `by run` exactly as a normal run would, hands the
 * process a `sitecustomize.py` through `PYTHONPATH`, and *attaches* to the port that bootstrap
 * opens. Hence `DapStartRequest.Attach` even though this looks and behaves like a launch.
 *
 * A fresh instance per session — the platform creates one from
 * [ByDebugAdapterSupportProvider.createDebugAdapterDescriptor] each time — which is what lets it
 * hold the session's port and, once the debuggee reports in, its source maps.
 */
class ByDebugAdapterDescriptor(private val project: Project) : DebugAdapterDescriptor<ByDebugAdapter>() {

    override val id: ByDebugAdapter = ByDebugAdapter

    private var setup: ByDebugSetup? = null
    private var mappings: List<ByFileMapping> = emptyList()

    /** Adds `setPydevdSourceMap`; see [ByDebugProtocolServer]. */
    override val debugAdapterServerClass: Class<out IDebugProtocolServer> = ByDebugProtocolServer::class.java

    override val breakpointsDescription: DapBreakpointsDescription = object : DapBreakpointsDescription(
        sourceBreakpointType = ByLineBreakpointType::class.java,
        exceptionBreakpointType = ByExceptionBreakpointType::class.java,
    ) {
        /**
         * DAP does not say *which* exception breakpoint a stop belongs to, and the platform needs
         * one to attach the stop to. There is exactly one exception breakpoint here — the type's
         * single default — so any exception stop is that one.
         */
        override fun doesExceptionMatchBreakpoint(
            exceptionInfo: DapExceptionInfo,
            breakpoint: DapExceptionBreakpoint,
        ): Boolean = breakpoint.ideBreakpoint.type is ByExceptionBreakpointType
    }

    /**
     * Points the process the run configuration is about to start at this session's port.
     *
     * The platform calls this after building the state and before executing it, which is the only
     * moment where the environment of a process somebody else launches can still be changed.
     */
    override fun configureProfileState(environment: ExecutionEnvironment, state: RunProfileState) {
        val holder = environment.runProfile as? UserDataHolder
        val setup = holder?.getUserData(ByDebugSetup.KEY)
            ?: throw ExecutionException(BasedPythonBundle.message("debug.error.noSetup"))
        holder.putUserData(ByDebugSetup.KEY, null)
        this.setup = setup

        val commandLine = state as? ByCommandLineState
            ?: throw ExecutionException(BasedPythonBundle.message("debug.error.unsupportedState"))

        when (setup.backend) {
            ByDebugBackend.DEBUGPY -> {
                commandLine.infrastructureEnv[ByDebugSetup.ENV_PORT] = setup.port.toString()
                commandLine.infrastructureEnv[ByDebugSetup.ENV_INFO_OUT] = setup.infoFile.toString()
                // pydevd warns on stderr about frozen modules on every start otherwise, which
                // reads like a failure in the run console.
                commandLine.infrastructureEnv[PYDEVD_DISABLE_FILE_VALIDATION] = "1"
                commandLine.pythonPathPrefix += setup.bootstrapDir.toString()
            }

            // bpd is reached through `PYTHON` rather than `PYTHONPATH`, because it does not run
            // *inside* the interpreter — it **is** the interpreter `by run` starts. See
            // `ByBpdWrapper` for why that is the only place a debugger fits.
            ByDebugBackend.BPD -> {
                commandLine.infrastructureEnv[ENV_PYTHON] = setup.wrapper.toString()
                commandLine.infrastructureEnv[ByBpdWrapper.ENV_PYTHON] =
                    setup.python ?: DEFAULT_PYTHON
                commandLine.infrastructureEnv[ByBpdWrapper.ENV_PORT] = setup.port.toString()
                commandLine.infrastructureEnv[ByBpdWrapper.ENV_RECORD] = setup.infoFile.toString()
                commandLine.infrastructureEnv[ByBpdWrapper.ENV_BPD] =
                    setup.bpd?.toString() ?: throw ExecutionException(
                        BasedPythonBundle.message("debug.bpd.error.notFound"),
                    )
            }
        }
    }

    /**
     * Waits for the bootstrap to report that it is listening, then connects.
     *
     * By the time this returns, [mappings] is populated — [createClient], which needs them, is
     * called immediately after this in `DapDebugSession.initialize`.
     */
    override suspend fun launchDebugAdapter(
        environment: ExecutionEnvironment,
        executionResult: ExecutionResult?,
        sessionId: String,
    ): DebugAdapterHandle {
        val setup = setup ?: throw ExecutionException(BasedPythonBundle.message("debug.error.noSetup"))
        val processHandler = executionResult?.processHandler

        if (setup.backend == ByDebugBackend.BPD) {
            // No source maps to invert and none to publish: bpd reads `_by_sourcemap.py` itself,
            // from the filesystem the program is on, and reports `.by` locations from the agent.
            // `mappings` stays empty and `BySourceMapPublisher` sends nothing, which is right —
            // sending pydevd's request to bpd would be sending it a request it does not have
            return ByBpdConnection.open(setup.infoFile, processHandler)
        }

        val info = awaitDebuggeeInfo(setup.infoFile) { processHandler?.isProcessTerminated != true }
            ?: fail(BasedPythonBundle.message("debug.error.noReport"), null, processHandler)

        if (!info.isListening) {
            fail(
                info.message ?: BasedPythonBundle.message("debug.error.bootstrapFailedGeneric"),
                info.python,
                processHandler,
            )
        }

        mappings = ByLineMapping.invert(info.mappedFiles)
        reportMappingProblems(info)

        return DebugAdapterSocketConnection(
            host = LOCALHOST,
            port = setup.port,
            connectionAttempts = CONNECTION_ATTEMPTS,
            intervalBetweenAttempts = CONNECTION_INTERVAL_MS.milliseconds,
        ) {
            // The IDE started this process, so the IDE ends it. Detaching and leaving the program
            // running would be the wrong reading of the Stop button for a launch-shaped session.
            processHandler?.destroyProcess()
        }
    }

    override fun createClient(
        eventConsumer: DapEventConsumer,
        environment: ExecutionEnvironment,
        executionResult: ExecutionResult?,
        commandProcessor: DapCommandProcessor,
        sessionScope: CoroutineScope,
    ): DapClient = ByDapClient(
        BySourceMapPublisher(eventConsumer, commandProcessor, mappings),
        onMoved = { moved -> report(moved, executionResult) },
    )

    /**
     * Puts what a jump or a restart really did on the run console.
     *
     * The console rather than a notification: it is where the rest of the session's account of
     * itself is, and this belongs in sequence with it — a balloon over the editor would be reporting
     * the same move twice, once beside the code and once away from it. Nothing is printed for a move
     * that went where it was asked and disturbed nothing; see [report].
     *
     * bpd sent these as prose until told this plugin reads them ([BySourceMapPublisher]), so this is
     * a rewrite of a line rather than a second copy of one.
     */
    private fun report(moved: ByMoved, executionResult: ExecutionResult?) {
        val text = moved.report() ?: return
        val console = executionResult?.executionConsole as? ConsoleView ?: return
        val type =
            if (moved.refused) ConsoleViewContentType.ERROR_OUTPUT
            else ConsoleViewContentType.SYSTEM_OUTPUT
        console.print("$text\n", type)
    }

    override fun createXDebugProcess(
        session: XDebugSession,
        dapDebugSession: DapDebugSession,
        xDebugProcessScope: CoroutineScope,
        globalScope: CoroutineScope,
        debugAdapterDescriptor: DebugAdapterDescriptor<*>,
        executionEnvironment: ExecutionEnvironment,
        executionResult: ExecutionResult?,
        startRequestType: DapStartRequest,
        startRequestArguments: Map<String, Any?>,
    ): DapXDebugProcess = ByDapXDebugProcess(
        session,
        dapDebugSession,
        xDebugProcessScope,
        globalScope,
        debugAdapterDescriptor,
        executionEnvironment,
        executionResult,
        startRequestType,
        startRequestArguments,
        // The run profile's copy is gone by now — `configureProfileState` consumes it — but this is
        // the descriptor's own field and lives as long as the session. A session with no setup at
        // all never reached `launchDebugAdapter`, so the value is moot, and null is the safe way to
        // be wrong: it costs at most a duplicate of output the console already has, and a hot
        // reload button for a session nothing could have been reloaded in.
        backend = setup?.backend,
    )

    /**
     * Report why the session cannot start, then abort without the platform turning it into an
     * "Unhandled exception" box.
     *
     * `DapDebugSession.initialize` wraps whatever [launchDebugAdapter] throws in a
     * `DapInitializationException` whose `userVisible` flag is `e !is CustomProcessedCantRunException`,
     * and `DapXDebugProcess` rethrows the user-visible ones out of a coroutine — where they surface
     * as an IDE error naming `CoroutineScheduler` and `Rete`. A missing `debugpy` is an ordinary,
     * one-command-away situation; it gets a notification with that command on it instead.
     *
     * The debuggee is killed on the way out. The bootstrap reports a failure at interpreter startup
     * — before the program body runs — so without this the user would press Debug, get no
     * breakpoints, and still have the program run to completion with all its side effects.
     */
    private fun fail(message: String, python: String?, processHandler: ProcessHandler?): Nothing {
        processHandler?.destroyProcess()
        reportDebugStartFailure(project, message, ByDebugpyInstall.plan(project, python))
        throw CantRunException.CustomProcessedCantRunException()
    }

    /**
     * A session can be perfectly healthy and still have nothing to map — an older `by` that does
     * not emit `_by_sourcemap.py`, say. Debugging still works against the generated `.py`, but no
     * breakpoint set in a `.by` file will ever bind, and silence about that would look like a bug
     * in the debugger.
     */
    private fun reportMappingProblems(info: ByDebuggeeInfo) {
        // Ordered by how specific the explanation is. A collision is the *reason* a mapping is
        // missing, so saying "no source map" instead would send the user looking in the wrong
        // place entirely — which is exactly what it used to do.
        val collisions = info.realCollisions
        val detail = when {
            collisions.isNotEmpty() -> describeCollisions(collisions)
            info.message != null -> info.message
            mappings.isEmpty() -> BasedPythonBundle.message("debug.warning.noMappedLines")
            else -> return
        }
        LOG.warn("basedpython debug session started with a mapping problem: $detail")
        ByCli.notifyWarning(
            project,
            BasedPythonBundle.message("debug.warning.noMapping.title"),
            detail,
        )
    }

    /**
     * Names the sources that collided and which of them actually ran, because the survivor is the
     * only one whose code exists at runtime.
     */
    private fun describeCollisions(collisions: List<ByGeneratedCollision>): String =
        collisions.joinToString("\n") { collision ->
            val sources = collision.sources.orEmpty().map { PathUtil.getFileName(it) to it }
            val survivor = sources.last().second
            BasedPythonBundle.message(
                "debug.warning.collision",
                sources.joinToString(", ") { it.second },
                PathUtil.getFileName(collision.generated.orEmpty()),
                survivor,
            )
        }

    private companion object {
        private val LOG = Logger.getInstance(ByDebugAdapterDescriptor::class.java)

        private const val LOCALHOST = "127.0.0.1"

        /** What `by run` reads the interpreter out of. */
        private const val ENV_PYTHON = "PYTHON"

        /** What `by run` falls back to when `PYTHON` names nothing. */
        private const val DEFAULT_PYTHON = "python3"
        private const val PYDEVD_DISABLE_FILE_VALIDATION = "PYDEVD_DISABLE_FILE_VALIDATION"

        /** The bootstrap only writes its report once the port is open, so this is a formality. */
        private const val CONNECTION_ATTEMPTS = 5
        private const val CONNECTION_INTERVAL_MS = 200L
    }
}

/**
 * The stock DAP process with the run configuration's own process and console put back.
 *
 * `DapXDebugProcess` assumes the adapter owns the debuggee and so builds a console over its own
 * process handler. Here the IDE launched `by run` itself, and that process is what the user needs
 * to see: the transpile step and its diagnostics, which never travel over DAP whichever backend is
 * running. Reusing its handler also makes the debug session end when `by run` ends, and Stop kill
 * the right process.
 *
 * What that console must *also* carry is the program's own output, and where that comes from is not
 * the same for both backends — see [backend] and [ByDebugBackend.ownsDebuggeeOutput].
 *
 * Internal rather than private because [dev.basedpython.pycharm.debug.hotswap.ByHotSwapEnabler] has
 * to recognise one: the platform hands its extension point a bare `XDebugProcess`, and which
 * debugger is behind it is a fact only this class holds.
 *
 * @param backend which debugger drives this session, or null for one that never started
 */
internal class ByDapXDebugProcess(
    session: XDebugSession,
    dapDebugSession: DapDebugSession,
    private val xDebugProcessScope: CoroutineScope,
    private val globalScope: CoroutineScope,
    debugAdapterDescriptor: DebugAdapterDescriptor<*>,
    private val executionEnvironment: ExecutionEnvironment,
    private val result: ExecutionResult?,
    private val startRequestType: DapStartRequest,
    private val startRequestArguments: Map<String, Any?>,
    val backend: ByDebugBackend?,
) : DapXDebugProcess(
    session,
    dapDebugSession,
    xDebugProcessScope,
    globalScope,
    debugAdapterDescriptor,
    executionEnvironment,
    result,
    startRequestType,
    startRequestArguments,
) {
    /**
     * The last capabilities the adapter announced.
     *
     * Kept rather than asked for, because the questions that need it are synchronous — the platform
     * asks whether an action is enabled while it is building a toolbar — and `capabilities` is a
     * `Flow`. Volatile because the collector and the questions are on different threads; null until
     * `initialize` has been answered, which every reader has to treat as "not yet", never as "no".
     */
    @Volatile
    private var capabilities: Capabilities? = null

    init {
        xDebugProcessScope.launch {
            dapDebugSession.capabilities.collect { capabilities = it }
        }
    }

    /**
     * Suspensions that arrived for a thread other than the one on screen — see [shouldApplyNow].
     *
     * Ours because the platform's is private and, once this class stops calling
     * `super.sessionInitialized`, never filled. [resume] drains this one exactly as the platform
     * drains that one.
     */
    private val deferredSuspensions = ConcurrentLinkedQueue<DapXSuspendContext>()

    /**
     * The `stopped` event behind the suspension last shown, so the same one is not shown twice.
     *
     * Only ever read and written by the single thread-list collector, hence no synchronisation.
     */
    private var lastApplied: StoppedEventArguments? = null

    /**
     * Starts the session, and installs the four listeners `DapXDebugProcess` would have.
     *
     * **`super` is deliberately not called.** The base does exactly four things here — watch for the
     * session to stop, run the start sequence, listen to the thread list, listen to output — and two
     * of them are the bugs this plugin cannot otherwise reach:
     *
     *  - the start sequence catches only `DapInitializationException`, so an adapter that *answers*
     *    `launch` with an error has its message dropped and the session never stopped. That is how a
     *    bpd that will not debug this build produced a live-looking tab and an "Unhandled exception"
     *    naming `CoroutineScheduler`, with the one sentence saying what to do nowhere
     *  - the thread-state listener queues any suspension that arrives while the session is already
     *    suspended, including the `stopped` DAP prescribes after `restartFrame` and `goto`
     *
     * Everything the base does *elsewhere* is inherited untouched: stepping, run to cursor, the
     * breakpoint handlers, the variables tree, expression evaluation, the editors provider. In
     * particular [applySuspendContext] is the platform's, so log points, breakpoint conditions and
     * suspend policies keep working exactly as they did — this is the one call that matters and it is
     * `protected`, which is what makes overriding a lifecycle method the whole of the change rather
     * than the start of a rewrite.
     *
     * See `scratch.ij-dap-issues.md`; when the platform fixes these, this override goes away.
     */
    override fun sessionInitialized() {
        xDebugProcessScope.launch(CoroutineName("basedpython stop-watch")) {
            dapDebugSession.sessionStopped.await()
            session.stop()
        }
        xDebugProcessScope.launch(CoroutineName("basedpython start")) { start() }
        launchThreadStateListener()
        launchOutputListener()
    }

    /**
     * The base class's start sequence, with the failure it does not report reported.
     *
     * `DapDebugSession.start` throws whatever the adapter answered — lsp4j raises a
     * `ResponseErrorException` carrying the adapter's own message, which is the only account of why
     * anything went wrong. The base lets it escape a coroutine, so it lands in the log as an
     * unhandled exception and the session stays up. Here it stops the session and says the sentence.
     */
    private suspend fun start() {
        try {
            if (!initBreakpointsCustomWay()) session.initBreakpoints()
            sessionState.value = DapXDebugSessionState.Connecting
            dapDebugSession.initialize(executionEnvironment, result)
            dapDebugSession.start(startRequestType, startRequestArguments)
            sessionState.value = DapXDebugSessionState.Running
            session.rebuildViews()
        } catch (e: DapInitializationException) {
            // The platform's own case, handled the platform's own way: it has already been through
            // `launchDebugAdapter`, where this plugin reports what it can itself.
            session.stop()
            if (e.userVisible) e.message?.let(session::reportError)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The case the platform has no branch for. `message` is what the adapter wrote for a
            // person — DAP puts it in the error response for exactly this — so it is shown as-is
            // rather than wrapped in a sentence of ours that would say less.
            PROCESS_LOG.info("the debug adapter refused to start the session", e)
            session.reportError(e.message ?: BasedPythonBundle.message("debug.error.startRefused"))
            session.stop()
        }
    }

    /**
     * Mirrors the platform's listener, and changes the one decision in it — see [shouldApplyNow].
     *
     * How the thread is found, how the child scope is named and how the context is built are the
     * platform's, reproduced rather than improved: the disagreement is about what to do with a
     * suspension, not about how to recognise one, and a second way of reading the same state would
     * be a second thing to keep in step with it.
     */
    private fun launchThreadStateListener() {
        xDebugProcessScope.launch(CoroutineName("basedpython threads")) {
            var suspension = 0
            dapDebugSession.threads.collect { state ->
                suspension++
                val thread = state.stoppedThread() ?: return@collect
                // One application per `stopped` event, not per emission of the thread list.
                //
                // `threads` is a StateFlow republished whenever the list changes at all, and under a
                // non-stop adapter threads come and go while you sit at a breakpoint. The platform's
                // rule hid that: it queued everything while suspended. Applying by thread identity
                // alone would re-run `applySuspendContext` for a stop already on screen, which
                // re-prints its log points, tears the variables tree down and rebuilds it, and — if
                // the breakpoint's suspend policy says not to stop — resumes the program from under
                // a session the user still sees as paused.
                //
                // Keyed on the raw event by identity, because that object is what one `stopped`
                // produced: a thread-list refresh carries it across, and nothing but a new event
                // makes another.
                val raw = (thread.state as? DapThreadState.Paused)?.rawEvent
                if (raw != null && raw === lastApplied) return@collect
                val context = presentationFactory.createSuspendContext(
                    dapDebugSession.commandProcessor.withChildScope("suspension-$suspension"),
                    state.threads,
                    thread,
                )
                if (shouldApplyNow(session.isSuspended, session.displayedThreadId(), thread.id)) {
                    lastApplied = raw
                    applySuspendContext(context)
                } else {
                    // Marked as applied even though it is only deferred: it has been accounted for,
                    // and leaving it unmarked would let the next republication of the same stop add
                    // a second copy of it to the queue — one Resume each to get through them.
                    lastApplied = raw
                    deferredSuspensions.add(context)
                }
            }
        }
    }

    /**
     * The platform's output listener, routed through [formatAndPrintOutput] as its own is.
     *
     * On **`globalScope`**, which is where the base puts it and is not a detail: `stopAsync` cancels
     * `xDebugProcessScope` *before* it tells the adapter to stop, so a pump on that scope drops
     * whatever is still in the channel at the moment Stop is pressed or the program ends. Under bpd
     * these events are the program's only voice, so that would be the last thing it printed.
     */
    private fun launchOutputListener() {
        globalScope.launch(CoroutineName("basedpython output")) {
            for (event in dapDebugSession.output) {
                event?.let(::formatAndPrintOutput)
            }
        }
    }

    /**
     * Resume, or show a suspension that was held back while this one was on screen.
     *
     * The platform's own shape: a queued suspension is what Resume produces, and the program runs on
     * only when there is none. Overridden because the queue is [deferredSuspensions] now — the
     * platform's is private, and after [sessionInitialized] stops calling `super` nothing ever adds
     * to it, so its `resume` would run the program on while ours still held a thread nobody had been
     * shown.
     */
    override fun resume(context: XSuspendContext?) {
        val deferred = deferredSuspensions.poll()
        if (deferred == null) {
            dapDebugSession.resume()
            return
        }
        xDebugProcessScope.launch(CoroutineName("basedpython deferred")) {
            applySuspendContext(deferred)
        }
    }

    override fun doGetProcessHandler(): ProcessHandler? = result?.processHandler ?: super.doGetProcessHandler()

    override fun createConsole(): ExecutionConsole = result?.executionConsole ?: super.createConsole()

    /**
     * *Reset Frame*, which the platform's DAP client does not wire up to `restartFrame` — see
     * [ByRestartFrameHandler], including why the action does something narrower here than its name
     * promises.
     *
     * One handler for the life of the process: [com.intellij.xdebugger.frame.XDropFrameHandler] is
     * asked about a frame at a time and holds nothing itself, and a fresh instance per call would
     * make identical questions look like different ones.
     */
    private val restartFrameHandler: XDropFrameHandler by lazy {
        ByRestartFrameHandler(session.project) { capabilities?.supportsRestartFrame }
    }

    override fun getDropFrameHandler(): XDropFrameHandler = restartFrameHandler

    /**
     * Prints an adapter `output` event, when it is the program's only voice — and files it by what
     * DAP says the category means rather than by what the base class assumes.
     *
     * Under debugpy nothing here is printed: the console is attached to the real `by run` process,
     * which the interpreter is a child of, so every one of these is a *second* copy of text the
     * user already has — and debugpy's adapter opens each session with two bare events reading
     * `ptvsd` and `debugpy` that landed in front of the program's first line.
     *
     * Under bpd the opposite holds and dropping them was a bug: bpd starts the interpreter itself
     * and captures its streams, and the wrapper points `bpd dap`'s stdout at the record file, so a
     * program's output reaches the IDE **only** as these events. A `print` went nowhere at all.
     * (`by run`'s own diagnostics are unaffected either way — they are on the process the IDE
     * started, and were never on this path.)
     *
     * The categories are [ByAdapterOutput]'s to interpret; the base class maps everything that is
     * not `console` or `stderr` onto stdout, which would print `telemetry` at a person and bury
     * `important` — the category bpd reserves for the messages that must not scroll past.
     */
    override fun formatAndPrintOutput(outEvent: OutputEventArguments) {
        if (backend?.ownsDebuggeeOutput != true) return
        val text = outEvent.output ?: return
        val contentType = when (ByAdapterOutput.registerFor(outEvent.category)) {
            ByOutputRegister.NORMAL -> ConsoleViewContentType.NORMAL_OUTPUT
            ByOutputRegister.SYSTEM -> ConsoleViewContentType.SYSTEM_OUTPUT
            // The console has no register for "not an error, but do not let this scroll past", and
            // of the three it has this is the only prominent one. Being read matters more here
            // than the colour being literally true.
            ByOutputRegister.PROMINENT -> ConsoleViewContentType.ERROR_OUTPUT
            ByOutputRegister.HIDDEN -> return
        }
        session.consoleView?.print(text, contentType)
    }

    /**
     * `DapXDebugProcess` supplies a line-breakpoint handler only, so exception breakpoints need
     * theirs adding here or nothing would ever send them to the adapter.
     */
    private val handlers: Array<XBreakpointHandler<*>> by lazy {
        super.getBreakpointHandlers() + ByExceptionBreakpointHandler(dapDebugSession)
    }

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = handlers

    private companion object {
        private val PROCESS_LOG = Logger.getInstance(ByDapXDebugProcess::class.java)
    }
}
