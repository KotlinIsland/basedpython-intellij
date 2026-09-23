package dev.basedpython.pycharm.debug

import com.intellij.execution.CantRunException
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.dap.DapBreakpointsDescription
import com.intellij.platform.dap.DapBreakpointsSupport
import com.intellij.platform.dap.DapCustomization
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapExceptionBreakpoint
import com.intellij.platform.dap.DapExceptionInfo
import com.intellij.platform.dap.DapExecutionUiContext
import com.intellij.platform.dap.DapExecutionUiSupport
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.DebugAdapterDescriptor
import com.intellij.platform.dap.DebugAdapterId
import com.intellij.platform.dap.DebugAdapterSupportProvider
import com.intellij.platform.dap.connection.DebugAdapterHandle
import com.intellij.platform.dap.connection.DebugAdapterSocketConnection
import com.intellij.platform.dap.xdebugger.DapXDebugProcess
import com.intellij.util.PathUtil
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.jetbrains.dap.impl.DapClientHandlersBuilder
import com.jetbrains.dap.protocol.OutputEventArguments
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.debug.bpd.ByBpdConnection
import dev.basedpython.pycharm.debug.bpd.ByBpdRecord
import dev.basedpython.pycharm.debug.bpd.ByBpdWrapper
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.debug.recompose.ByRecompositionLink
import dev.basedpython.pycharm.debug.recompose.ByRecompositionRequests
import dev.basedpython.pycharm.debug.recompose.ByRecompositionSession
import dev.basedpython.pycharm.run.ByCommandLineState
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CoroutineScope
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
 * hold the session's port and what the debuggee reported.
 *
 * ## nothing here races the platform
 *
 * Everything a session needs in place before the program runs a line is in place by construction,
 * never by getting a request to the adapter ahead of the platform's own `setBreakpoints` and
 * `configurationDone` — the platform promises nothing about that order, and 263.5701 took away the
 * traffic observer the plugin once used to win it:
 *
 *  - **source maps** are never sent. bpd reads `_by_sourcemap.py` itself at `launch`, out of the
 *    directory beside the program, and holds a `.by` breakpoint that arrives before it as
 *    `pending`; under debugpy the bootstrap hands pydevd the maps inside the debuggee before it
 *    reports the port, so before anything can connect ([ByDebuggeeInfo.mapped])
 *  - **what this plugin reads** of bpd's events, and the **recomposition watch**, ride the `launch`
 *    itself ([startArguments]) — the one request that cannot arrive after the program has started,
 *    because it is what starts it
 *  - **bpd's events** are read by name whenever they arrive
 *    ([DapCustomization.registerProtocolHandlers]), which is never before the `launch` that says
 *    this plugin reads them
 */
class ByDebugAdapterDescriptor(private val project: Project) : DebugAdapterDescriptor<ByDebugAdapter>() {

    override val id: ByDebugAdapter = ByDebugAdapter

    private var setup: ByDebugSetup? = null

    /** What a bpd session's wrapper recorded, once [launchDebugAdapter] has read it. */
    @Volatile
    private var bpdRecord: ByBpdRecord.Ready? = null

    /**
     * This session's link to bpd's compose runtime: the identity every recomposition call of this
     * session carries, so that with two sessions running one's records never reach the other's
     * window. Made in [createXDebugProcess], from the session that is its executor, and read by the
     * events that session's adapter sends.
     */
    @Volatile
    private var recompositionLink: ByRecompositionRequests? = null

    override val customization: DapCustomization = object : DapCustomization() {
        override val breakpointsSupport: DapBreakpointsSupport = ByBreakpointsSupport
        override val executionUiSupport: DapExecutionUiSupport = ByOutputSupport { setup?.backend }

        /**
         * bpd's own events, bound to the client's dispatch by name. Nothing else is listened to:
         * the platform handles every event the base protocol has.
         */
        override fun registerProtocolHandlers(builder: DapClientHandlersBuilder) {
            ByBpdEvents.register(
                builder,
                bpdEvents(
                    onMoved = ::report,
                    onRecomposed = { event ->
                        recompositionLink?.let { ByRecompositionSession.getInstance(project).append(it, event) }
                    },
                ),
            )
        }
    }

    /**
     * The line and exception breakpoint types.
     *
     * On the descriptor rather than on [ByBreakpointsSupport], because the descriptor is where both
     * ends of the supported range read it: 263.5701 declares it here and nowhere else, and 263.5153,
     * which also offers it on the support, reads the descriptor's when the support has none.
     */
    @Suppress("OVERRIDE_DEPRECATION")
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
     * The handler that sends exception breakpoints.
     *
     * `DapXDebugProcess` makes a handler for the source breakpoint type and, when there is one, the
     * function breakpoint type; everything else it takes from here. Without
     * [ByExceptionBreakpointHandler] the exception type would be a checkbox that changed nothing.
     */
    private object ByBreakpointsSupport : DapBreakpointsSupport() {
        override fun createAdditionalBreakpointHandlers(
            dapSession: DapDebugSession,
            session: XDebugSession,
        ): List<XBreakpointHandler<*>> = listOf(ByExceptionBreakpointHandler(dapSession))
    }

    /**
     * Points the process the run configuration is about to start at this session's port.
     *
     * The platform calls this after building the state and before executing it, which is the only
     * moment where the environment of a process somebody else launches can still be changed.
     */
    override fun configureProfileState(environment: ExecutionEnvironment, state: RunProfileState) {
        val setup = ByDebugSetups.getInstance(project).take(environment.runProfile)
            ?: throw ExecutionException(BasedPythonBundle.message("debug.error.noSetup"))
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

            // bpd is reached by *being* the process `by run` starts rather than by running inside
            // it, so the wrapper is `by run`'s launcher: `by run` chooses the interpreter as it
            // would for a plain run and hands it to the wrapper in front of the program. See
            // `ByBpdWrapper` for why that is the only place a debugger fits.
            ByDebugBackend.BPD -> {
                commandLine.infrastructureArgs += listOf(BY_LAUNCHER_FLAG, setup.wrapper.toString())
                commandLine.infrastructureEnv[ByBpdWrapper.ENV_PORT] = setup.port.toString()
                commandLine.infrastructureEnv[ByBpdWrapper.ENV_RECORD] = setup.infoFile.toString()
                // empty rather than absent, so a variable of the same name in the IDE's own
                // environment cannot stand in for an answer the IDE did not give
                commandLine.infrastructureEnv[ByBpdWrapper.ENV_BPD] = setup.bpd?.besideBy?.toString().orEmpty()
                commandLine.infrastructureEnv[ByBpdWrapper.ENV_BPD_FALLBACK] =
                    setup.bpd?.onPath?.toString().orEmpty()
                // what `by run` says when it ends before the wrapper has recorded anything — a
                // refused interpreter, a transpile error — is in no record, and a start that fails
                // there never shows the console it went to
                commandLine.infrastructureListeners += setup.said
                commandLine.infrastructureListeners += EndedBeforeAttaching(project, setup)
            }
        }
    }

    /**
     * Waits for the bootstrap to report that it is listening, then connects.
     *
     * Under debugpy the report is written only once pydevd holds every `.by` file's map, so what
     * this connects to already translates a `.by` breakpoint whenever the platform sends one.
     */
    override suspend fun launchDebugAdapter(
        environment: ExecutionEnvironment,
        executionResult: ExecutionResult?,
        sessionId: String,
    ): DebugAdapterHandle {
        val setup = setup ?: throw ExecutionException(BasedPythonBundle.message("debug.error.noSetup"))
        this.executionResult = executionResult
        val processHandler = executionResult?.processHandler
        ByDebugSetups.getInstance(project).releaseWith(setup, processHandler)

        if (setup.backend == ByDebugBackend.BPD) {
            // No source maps to hand anything: bpd reads `_by_sourcemap.py` itself at `launch`, from
            // the filesystem the program is on, holds a `.by` breakpoint set before then as
            // `pending`, and reports `.by` locations from the agent
            val connection = try {
                ByBpdConnection.open(setup.infoFile, processHandler, setup.said)
            } catch (e: ExecutionException) {
                // Every one of these is a sentence written for the user — no bpd found, the wrapper
                // exited, the record never completed — and the platform shows none of them: it
                // reports a failed adapter launch as "failed to launch" with the adapter's name.
                // Said once: `by run` ending on its own is also noticed by [EndedBeforeAttaching]
                val message = e.message ?: BasedPythonBundle.message("debug.error.noSetup")
                if (setup.settle()) fail(message, install = null, processHandler)
                processHandler?.destroyProcess()
                throw CantRunException.CustomProcessedCantRunException()
            }
            setup.settle()
            bpdRecord = connection.record
            return connection
        }

        val info = awaitDebuggeeInfo(setup.infoFile) { processHandler?.isProcessTerminated != true }
            ?: fail(BasedPythonBundle.message("debug.error.noReport"), ByDebugpyInstall.plan(project, null), processHandler)

        if (!info.isListening) {
            fail(
                info.message ?: BasedPythonBundle.message("debug.error.bootstrapFailedGeneric"),
                ByDebugpyInstall.plan(project, info.python),
                processHandler,
            )
        }

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

    /**
     * The run configuration's own process and console, once [launchDebugAdapter] has been handed
     * them. What a jump's report is printed on.
     */
    @Volatile
    private var executionResult: ExecutionResult? = null

    /**
     * Puts what a jump or a restart really did on the run console.
     *
     * The console rather than a notification: it is where the rest of the session's account of
     * itself is, and this belongs in sequence with it — a balloon over the editor would be reporting
     * the same move twice, once beside the code and once away from it. Nothing is printed for a move
     * that went where it was asked and disturbed nothing; see [report].
     *
     * bpd sends these as prose to a client that has not said it reads them, and this one says so in
     * the `launch` ([ByBpdEvents.UNDERSTOOD]), so this is a rewrite of a line rather than a second
     * copy of one.
     */
    private fun report(moved: ByMoved) {
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
    ): DapXDebugProcess {
        val link = ByRecompositionRequests(dapDebugSession).also { recompositionLink = it }
        return ByDapXDebugProcess(
            session,
            dapDebugSession,
            xDebugProcessScope,
            globalScope,
            debugAdapterDescriptor,
            executionEnvironment,
            executionResult,
            startRequestType,
            startArguments(startRequestArguments),
            // The run profile's copy is gone by now — `configureProfileState` consumes it — but
            // this is the descriptor's own field and lives as long as the session. A session with
            // no setup at all never reached `launchDebugAdapter`, so the value is moot, and null is
            // the safe way to be wrong: it costs at most a duplicate of output the console already
            // has, and a hot reload button for a session nothing could have been reloaded in.
            backend = setup?.backend,
            // The *path*, not what is in it. The wrapper writes the temp directory `by run` chose
            // into this file, and `by run` has not chosen one yet when the process is built — so
            // reading it here is reading a file that does not exist. It is read when hot reload
            // asks, by which time the program is running and the record is complete.
            recordFile = setup?.infoFile,
            recompositionLink = link,
        )
    }

    /**
     * The arguments the session is started with: [provided], as the launch-arguments provider built
     * them, completed from what `by run` recorded when the session is a bpd one.
     *
     * The provider runs before `by run` has chosen the program, so it cannot name it, and the
     * platform takes the arguments now, when the process is built — before `by run` has started.
     * What it does with them happens later: it reads them to build the `launch` request, after
     * `initialize`, by which time [launchDebugAdapter] has read the record that names the program.
     * So a bpd session's arguments are a map whose contents are that record's, read the first time
     * anything reads the map. A bpd session with no record by then is one whose adapter was never
     * reached, and has nothing to launch.
     *
     * The launch also says what this plugin reads of bpd's events and whether to stream the
     * recompositions ([ByBpdRecord.Ready.launchArguments]). The watch is read from the preference
     * here, when the platform builds the `launch`.
     */
    private fun startArguments(provided: Map<String, Any?>): Map<String, Any?> {
        if (setup?.backend != ByDebugBackend.BPD) return provided
        return ByRecordedArguments {
            val record = bpdRecord ?: throw ExecutionException(BasedPythonBundle.message("debug.error.noSetup"))
            record.launchArguments(
                provided,
                watchRecompositions = ByRecompositionSession.getInstance(project).watchesFromTheStart,
            )
        }
    }

    /**
     * Report why the session cannot start, then abort without the platform reporting it again.
     *
     * The platform reports a failed adapter launch itself, as "failed to launch" and the adapter's
     * name, and says nothing more: whatever sentence the exception carried stays in the log. A
     * missing `debugpy` is an ordinary, one-command-away situation, and a bpd that could not be
     * found is a sentence about where it was looked for; each gets a notification that says so,
     * with the command on it when there is one. [CantRunException.CustomProcessedCantRunException]
     * is the platform's word for "already reported", which it stops the session on quietly.
     *
     * The debuggee is killed on the way out. The bootstrap reports a failure at interpreter startup
     * — before the program body runs — so without this the user would press Debug, get no
     * breakpoints, and still have the program run to completion with all its side effects.
     */
    private fun fail(message: String, install: ByDebugpyInstall?, processHandler: ProcessHandler?): Nothing {
        processHandler?.destroyProcess()
        reportDebugStartFailure(project, message, install)
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
            (info.mapped ?: 0) == 0 -> BasedPythonBundle.message("debug.warning.noMappedLines")
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

        /** What `by run` starts the program through, in front of the interpreter it chose. */
        private const val BY_LAUNCHER_FLAG = "--launcher"
        private const val PYDEVD_DISABLE_FILE_VALIDATION = "PYDEVD_DISABLE_FILE_VALIDATION"

        /** The bootstrap only writes its report once the port is open, so this is a formality. */
        private const val CONNECTION_ATTEMPTS = 5
        private const val CONNECTION_INTERVAL_MS = 200L
    }
}

/**
 * Says why a bpd session could not start when `by run` ends on its own before the wrapper's record
 * was complete — a refused interpreter, a transpile error — in `by run`'s own words.
 *
 * On the process rather than in [ByDebugAdapterDescriptor.launchDebugAdapter], because the platform
 * stops the debug session the moment the process ends: that cancels the adapter launch's wait
 * before it sees the end, or — when `by run` refuses faster than the session gets that far — before
 * the launch has begun at all. A cancelled start is one the platform reports nothing about. The
 * launch's own failure and this share [ByDebugSetup.settle], so a start is reported once.
 */
private class EndedBeforeAttaching(private val project: Project, private val setup: ByDebugSetup) : ProcessListener {
    override fun processTerminated(event: ProcessEvent) {
        val why = ByBpdConnection.endedBeforeAttaching(setup.infoFile, setup.said) ?: return
        if (setup.settle()) reportDebugStartFailure(project, why, install = null)
    }
}

/**
 * A map whose entries are computed the first time anything reads it.
 *
 * What [ByDebugAdapterDescriptor.startArguments] hands the platform for a bpd session: the platform
 * takes a session's start arguments when it builds the process and reads them only to send
 * `launch`, and the program they name is not known until between the two.
 */
private class ByRecordedArguments(resolve: () -> Map<String, Any?>) : AbstractMap<String, Any?>() {
    private val resolved by lazy(resolve)

    override val entries: Set<Map.Entry<String, Any?>> get() = resolved.entries
}

/**
 * The stock DAP process with the run configuration's own process put back.
 *
 * `DapXDebugProcess` assumes the adapter owns the debuggee and so gives the session a process
 * handler of its own. Here the IDE launched `by run` itself, and that process is what the user
 * needs to see: the transpile step and its diagnostics, which never travel over DAP whichever
 * backend is running. Reusing its handler also makes the debug session end when `by run` ends, and
 * Stop kill the right process. Its console is put back by the descriptor's customization
 * ([ByDebugAdapterDescriptor]), which is also where the program's output is filed.
 *
 * Internal rather than private because [dev.basedpython.pycharm.debug.hotswap.ByHotSwapEnabler] and
 * [dev.basedpython.pycharm.debug.recompose.ByRecompositionListener] have to recognise one: the
 * platform hands their extension points a bare `XDebugProcess`, and which debugger is behind it is a
 * fact only this class holds.
 *
 * @param backend which debugger drives this session, or null for one that never started
 */
internal class ByDapXDebugProcess(
    session: XDebugSession,
    dapDebugSession: DapDebugSession,
    xDebugProcessScope: CoroutineScope,
    globalScope: CoroutineScope,
    debugAdapterDescriptor: DebugAdapterDescriptor<*>,
    executionEnvironment: ExecutionEnvironment,
    private val result: ExecutionResult?,
    startRequestType: DapStartRequest,
    startRequestArguments: Map<String, Any?>,
    val backend: ByDebugBackend?,
    /**
     * The file the wrapper writes what `by run` chose into.
     *
     * Where the build directory comes from, and the one thing about a bpd session the IDE cannot
     * work out for itself: the directory is chosen inside `by run`, so nothing here knows it until
     * the program has started. Kept as the path rather than its contents because this is built
     * *before* that happens — see [dev.basedpython.pycharm.debug.bpd.ByBpdRecord.buildDirectoryOf].
     * Null for a session that is not one of `by run`'s.
     */
    val recordFile: java.nio.file.Path?,
    /** What this session's recomposition requests are sent through, and known by. */
    internal val recompositionLink: ByRecompositionLink,
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
    override fun doGetProcessHandler(): ProcessHandler? = result?.processHandler ?: super.doGetProcessHandler()
}

/**
 * The run configuration's console, and the adapter's `output` events filed on it when they are the
 * program's only voice — by what DAP says each category means rather than by what the platform
 * assumes.
 *
 * Under debugpy nothing is printed: the console is attached to the real `by run` process, which the
 * interpreter is a child of, so every event is a *second* copy of text the user already has — and
 * debugpy's adapter opens each session with two bare events reading `ptvsd` and `debugpy` that
 * landed in front of the program's first line.
 *
 * Under bpd the opposite holds and dropping them was a bug: bpd starts the interpreter itself and
 * captures its streams, and the wrapper points `bpd dap`'s stdout at the record file, so a program's
 * output reaches the IDE **only** as these events. (`by run`'s own diagnostics are unaffected either
 * way — they are on the process the IDE started, and were never on this path.)
 *
 * The categories are [ByAdapterOutput]'s to interpret; the platform maps everything that is not
 * `console` or `stderr` onto stdout, which would print `telemetry` at a person and bury `important`
 * — the category bpd reserves for the messages that must not scroll past.
 *
 * The platform prints these from a pump on its global scope, not the process's, so output still in
 * the channel when Stop is pressed or the program ends is not dropped — under bpd, that would be the
 * last thing the program printed.
 */
private class ByOutputSupport(private val backend: () -> ByDebugBackend?) : DapExecutionUiSupport() {

    override fun createConsole(context: DapExecutionUiContext): ExecutionConsole =
        context.executionResult?.executionConsole ?: super.createConsole(context)

    override fun formatAndPrintOutput(context: DapExecutionUiContext, event: OutputEventArguments) {
        if (backend()?.ownsDebuggeeOutput != true) return
        val contentType = when (ByAdapterOutput.registerFor(event.category?.value)) {
            ByOutputRegister.NORMAL -> ConsoleViewContentType.NORMAL_OUTPUT
            ByOutputRegister.SYSTEM -> ConsoleViewContentType.SYSTEM_OUTPUT
            // The console has no register for "not an error, but do not let this scroll past", and
            // of the three it has this is the only prominent one. Being read matters more here
            // than the colour being literally true.
            ByOutputRegister.PROMINENT -> ConsoleViewContentType.ERROR_OUTPUT
            ByOutputRegister.HIDDEN -> return
        }
        context.session.consoleView?.print(event.output, contentType)
    }
}
