package dev.basedpython.pycharm.debug

import com.intellij.execution.CantRunException
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
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
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.DebugAdapterDescriptor
import com.intellij.platform.dap.DebugAdapterId
import com.intellij.platform.dap.DebugAdapterSupportProvider
import com.intellij.platform.dap.connection.DebugAdapterHandle
import com.intellij.platform.dap.connection.DebugAdapterSocketConnection
import com.intellij.platform.dap.xdebugger.DapXDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.debug.bpd.ByBpdConnection
import dev.basedpython.pycharm.debug.bpd.ByBpdWrapper
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.run.ByCommandLineState
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CoroutineScope
import org.eclipse.lsp4j.debug.OutputEventArguments
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
    ): DapClient = ByDapClient(BySourceMapPublisher(eventConsumer, commandProcessor, mappings))

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
 * process handler, forwarding whatever the adapter reports as `output` events. Here the IDE
 * launched `by run` itself, and that process is what the user needs to see: the transpile step, its
 * diagnostics, and the program's own stdout and stderr, none of which travel over DAP. Reusing its
 * handler also makes the debug session end when `by run` ends, and Stop kill the right process.
 */
private class ByDapXDebugProcess(
    session: XDebugSession,
    dapDebugSession: DapDebugSession,
    xDebugProcessScope: CoroutineScope,
    globalScope: CoroutineScope,
    debugAdapterDescriptor: DebugAdapterDescriptor<*>,
    executionEnvironment: ExecutionEnvironment,
    private val result: ExecutionResult?,
    startRequestType: DapStartRequest,
    startRequestArguments: Map<String, Any?>,
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

    override fun createConsole(): ExecutionConsole = result?.executionConsole ?: super.createConsole()

    /**
     * Drops the adapter's `output` events on the floor.
     *
     * The base class forwards every one of them to the console, which is right when the adapter
     * owns the debuggee and its output only ever arrives over DAP. Here the console is already
     * attached to the real `by run` process, so anything printed this way is a *second* copy at
     * best — and debugpy's adapter opens every session by emitting two bare events reading `ptvsd`
     * and `debugpy`, which landed in front of the program's own first line.
     */
    override fun formatAndPrintOutput(outEvent: OutputEventArguments) = Unit

    /**
     * `DapXDebugProcess` supplies a line-breakpoint handler only, so exception breakpoints need
     * theirs adding here or nothing would ever send them to the adapter.
     */
    private val handlers: Array<XBreakpointHandler<*>> by lazy {
        super.getBreakpointHandlers() + ByExceptionBreakpointHandler(dapDebugSession)
    }

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = handlers
}
